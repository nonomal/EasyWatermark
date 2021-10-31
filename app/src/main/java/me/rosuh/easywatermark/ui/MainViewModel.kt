package me.rosuh.easywatermark.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.TextPaint
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import androidx.palette.graphics.Palette
import dagger.hilt.android.lifecycle.HiltViewModel
import id.zelory.compressor.Compressor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rosuh.easywatermark.BuildConfig
import me.rosuh.easywatermark.MyApp
import me.rosuh.easywatermark.R
import me.rosuh.easywatermark.data.model.*
import me.rosuh.easywatermark.data.repo.UserConfigRepository
import me.rosuh.easywatermark.data.repo.WaterMarkRepository
import me.rosuh.easywatermark.ui.widget.WaterMarkImageView
import me.rosuh.easywatermark.utils.FileUtils.Companion.outPutFolderName
import me.rosuh.easywatermark.utils.bitmap.*
import me.rosuh.easywatermark.utils.ktx.applyConfig
import me.rosuh.easywatermark.utils.ktx.formatDate
import me.rosuh.easywatermark.utils.ktx.launch
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import javax.inject.Inject
import kotlin.math.ceil

@HiltViewModel
class MainViewModel @Inject constructor(
    private val userRepo: UserConfigRepository,
    private val waterMarkRepo: WaterMarkRepository
) : ViewModel() {

    var nextSelectedPos: Int = 0

    val saveResult: MutableLiveData<Result<*>> = MutableLiveData()

    val compressedResult: MutableLiveData<Result<*>> = MutableLiveData()

    val waterMark: LiveData<WaterMark> = waterMarkRepo.waterMark.asLiveData()

    private var autoScroll = true

    val imageList: LiveData<Pair<List<ImageInfo>, Boolean>> =
        waterMarkRepo.uriLivedData.map { Pair(it, autoScroll) }

    val selectedImage: MutableLiveData<ImageInfo> = MutableLiveData()

    val saveImageUri: MutableLiveData<List<ImageInfo>> = MutableLiveData()

    val saveProcess: MutableLiveData<ImageInfo> = MutableLiveData()

    private var compressedJob: Job? = null

    private var userPreferences: StateFlow<UserPreferences> = userRepo.userPreferences.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UserPreferences.DEFAULT
    )

    val outputFormat: Bitmap.CompressFormat
        get() = userPreferences.value.outputFormat

    val compressLevel: Int
        get() = userPreferences.value.compressLevel

    val isNeedShowUpgradeInfo: LiveData<Boolean> =
        userRepo.changeLogFlow.map { it != BuildConfig.VERSION_CODE.toString() }.asLiveData()

    val colorPalette: MutableLiveData<Palette> = MutableLiveData()

    private val tmpDrawableBounds by lazy { Rect() }
    private val drawableBounds by lazy { RectF() }
    private val tmpSrcBounds by lazy { RectF() }
    private val tmpDstBounds by lazy { RectF() }

    fun saveImage(
        contentResolver: ContentResolver,
        viewInfo: ViewInfo,
        imageList: List<ImageInfo>
    ) {
        viewModelScope.launch {
            if (this@MainViewModel.imageList.value?.first.isNullOrEmpty()) {
                saveResult.value = Result.failure(null, code = TYPE_ERROR_NOT_IMG)
                return@launch
            }
            saveResult.value =
                Result.success(null, code = TYPE_SAVING)
            val result = generateList(contentResolver, viewInfo, imageList)
            if (result.isFailure()) {
                saveResult.value = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                return@launch
            }
            saveImageUri.value = result.data
            saveResult.value = Result.success(code = TYPE_JOB_FINISH, data = result.data)
        }
    }

    private suspend fun generateList(
        contentResolver: ContentResolver,
        viewInfo: ViewInfo,
        infoList: List<ImageInfo>?
    ): Result<List<ImageInfo>> =
        withContext(Dispatchers.Default) {
            if (infoList.isNullOrEmpty()) {
                return@withContext Result.failure(null, TYPE_ERROR_NOT_IMG)
            }
            infoList.forEach { info ->
                try {
                    info.jobState = JobState.Ing
                    launch(Dispatchers.Main) { saveProcess.value = info }
                    info.result = generateImage(contentResolver, viewInfo, info)
                    info.jobState = JobState.Success(info.result!!)
                    launch(Dispatchers.Main) { saveProcess.value = info }
                } catch (fne: FileNotFoundException) {
                    fne.printStackTrace()
                    info.result = Result.failure(null, code = TYPE_ERROR_FILE_NOT_FOUND)
                    info.jobState = JobState.Failure(info.result!!)
                    saveProcess.postValue(info)
                } catch (oom: OutOfMemoryError) {
                    info.result = Result.failure(null, code = TYPE_ERROR_SAVE_OOM)
                    info.jobState = JobState.Failure(info.result!!)
                    saveProcess.postValue(info)
                }
                Log.i("generateList", "${info.uri} : ${info.result}")
            }
            // reset process state
            saveProcess.postValue(null)
            return@withContext Result.success(infoList)
        }

    private suspend fun generateImage(
        contentResolver: ContentResolver,
        viewInfo: ViewInfo,
        imageInfo: ImageInfo
    ): Result<Uri> =
        withContext(Dispatchers.IO) {
            val rect = decodeBitmapFromUri(contentResolver, imageInfo.uri)
            if (rect.isFailure()) {
                return@withContext Result.extendMsg(rect)
            }
            val mutableBitmap = rect.data?.bitmap?.copy(Bitmap.Config.ARGB_8888, true)
                ?: return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "Copy bitmap from uri failed."
                )

            val inSample = calculateInSampleSize(
                mutableBitmap.width,
                mutableBitmap.height,
                WaterMarkImageView.calculateDrawLimitWidth(viewInfo.width, viewInfo.paddingLeft),
                WaterMarkImageView.calculateDrawLimitHeight(viewInfo.height, viewInfo.paddingRight),
            )
            imageInfo.width = mutableBitmap.width
            imageInfo.height = mutableBitmap.height
            if (waterMark.value == null) {
                return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "config.value == null"
                )
            }
            imageInfo.inSample = inSample
            val canvas = Canvas(mutableBitmap)
            val tmpConfig = waterMark.value!!
            // generate matrxi of drawable
            val imageMatrix = generateMatrix(
                viewInfo,
                ceil((imageInfo.width.toDouble() / imageInfo.inSample)).toInt(),
                ceil((imageInfo.height.toDouble() / imageInfo.inSample)).toInt(),
                tmpDrawableBounds,
                tmpSrcBounds,
                tmpDstBounds
            )
            Log.i(
                "generateImage",
                """
                    imageMatrix = $imageMatrix,
                    inSample = $inSample,
                    imageInfo = $imageInfo
                    viewInfo = $viewInfo,
                    bitmapW = ${mutableBitmap.width}
                    bitmapH = ${mutableBitmap.height},
                """.trimIndent()
            )
            // map to drawable bounds
            imageMatrix.mapRect(drawableBounds, RectF(tmpDrawableBounds))
            drawableBounds.set(
                drawableBounds.left + viewInfo.paddingLeft,
                drawableBounds.top + viewInfo.paddingTop,
                drawableBounds.right + viewInfo.paddingRight,
                drawableBounds.bottom + viewInfo.paddingBottom,
            )
            // calculate the scale factor
            imageInfo.scaleX = mutableBitmap.width.toFloat() / drawableBounds.width()
            imageInfo.scaleY = mutableBitmap.height.toFloat() / drawableBounds.height()
            val bitmapPaint = TextPaint().applyConfig(imageInfo, tmpConfig, isScale = false)
            val layoutPaint = Paint()
            layoutPaint.shader = when (waterMark.value?.markMode) {
                WaterMarkRepository.MarkMode.Text -> {
                    WaterMarkImageView.buildTextBitmapShader(
                        imageInfo,
                        waterMark.value!!,
                        bitmapPaint,
                        Dispatchers.IO
                    )
                }
                WaterMarkRepository.MarkMode.Image -> {
                    val iconBitmapRect = decodeSampledBitmapFromResource(
                        contentResolver,
                        tmpConfig.iconUri,
                        viewInfo.width,
                        viewInfo.height
                    )
                    if (iconBitmapRect.isFailure() || iconBitmapRect.data == null) {
                        return@withContext Result.failure(
                            null,
                            code = "-1",
                            message = "decodeSampledBitmapFromResource == null"
                        )
                    }
                    val iconBitmap = iconBitmapRect.data!!.bitmap
                    WaterMarkImageView.buildIconBitmapShader(
                        imageInfo,
                        iconBitmap,
                        tmpConfig,
                        bitmapPaint,
                        scale = true,
                        Dispatchers.IO
                    )
                }
                null -> return@withContext Result.failure(
                    null,
                    code = "-1",
                    message = "Unknown markmode"
                )
            }
            canvas.drawRect(
                0f, 0f,
                mutableBitmap.width.toFloat(), mutableBitmap.height.toFloat(), layoutPaint
            )

            return@withContext if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val imageCollection =
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val imageDetail = ContentValues().apply {
                    put(
                        MediaStore.Images.Media.DISPLAY_NAME,
                        generateOutputName()
                    )
                    put(MediaStore.Images.Media.MIME_TYPE, "image/${trapOutputExtension()}")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$outPutFolderName/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }

                val imageContentUri = contentResolver.insert(imageCollection, imageDetail)
                contentResolver.openFileDescriptor(imageContentUri!!, "w", null).use { pfd ->
                    mutableBitmap.compress(
                        outputFormat,
                        compressLevel,
                        FileOutputStream(pfd!!.fileDescriptor)
                    )
                }
                imageDetail.clear()
                imageDetail.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(imageContentUri, imageDetail, null, null)
                Result.success(imageContentUri)
            } else {
                // need request write_storage permission
                // should check Pictures folder exist
                val picturesFile: File =
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
                        ?: return@withContext Result.failure(
                            null,
                            code = "-1",
                            message = "Can't get pictures directory."
                        )
                if (!picturesFile.exists()) {
                    picturesFile.mkdir()
                }
                val mediaDir = File(picturesFile, outPutFolderName)

                if (!mediaDir.exists()) {
                    mediaDir.mkdirs()
                }
                val outputFile =
                    File(mediaDir, generateOutputName())
                outputFile.outputStream().use { fileOutputStream ->
                    mutableBitmap.compress(
                        outputFormat,
                        compressLevel,
                        fileOutputStream
                    )
                }
                val outputUri = FileProvider.getUriForFile(
                    MyApp.instance,
                    "${BuildConfig.APPLICATION_ID}.fileprovider",
                    outputFile
                )
                MyApp.instance.sendBroadcast(
                    Intent(
                        Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                        Uri.fromFile(outputFile)
                    )
                )
                Result.success(outputUri)
            }
        }

    private fun generateOutputName(): String {
        return "ewm_${System.currentTimeMillis()}.${trapOutputExtension()}"
    }

    private fun trapOutputExtension(): String {
        return if (outputFormat == Bitmap.CompressFormat.PNG) "png" else "jpg"
    }

    fun selectImage(uri: Uri) {
        if (selectedImage.value?.uri == uri) {
            return
        }
        selectedImage.value = ImageInfo(uri)
    }

    fun updateImageList(list: List<Uri>) {
        launch {
            generateImageInfoList(list)?.run {
                autoScroll = true
                selectedImage.value = this.first()
                nextSelectedPos = 0
                waterMarkRepo.updateImageList(this)
            }
        }
    }

    private suspend fun generateImageInfoList(list: List<Uri>) =
        withContext(Dispatchers.Default) {
            return@withContext list.toSet()
                .map { ImageInfo(it) }
                .takeIf {
                    it.isNotEmpty()
                }
        }

    fun updateText(text: String) {
        launch {
            waterMarkRepo.updateText(text)
        }
    }

    fun updateTextSize(textSize: Float) {
        launch {
            val finalTextSize = textSize.coerceAtLeast(0f)
            waterMarkRepo.updateTextSize(finalTextSize)
        }
    }

    fun updateTextColor(color: Int) {
        launch {
            waterMarkRepo.updateColor(color)
        }
    }

    fun updateTextStyle(style: TextPaintStyle) {
        launch {
            waterMarkRepo.updateTextStyle(style)
        }
    }

    fun updateTextTypeface(typeface: TextTypeface) {
        launch {
            waterMarkRepo.updateTypeFace(typeface)
        }
    }

    fun updateAlpha(alpha: Int) {
        launch {
            val finalAlpha = alpha.coerceAtLeast(0).coerceAtMost(255)
            waterMarkRepo.updateAlpha(finalAlpha)
        }
    }

    fun updateHorizon(gap: Int) {
        launch {
            waterMarkRepo.updateHorizon(gap)
        }
    }

    fun updateVertical(gap: Int) {
        launch {
            waterMarkRepo.updateVertical(gap)
        }
    }

    fun updateDegree(degree: Float) {
        launch {
            waterMarkRepo.updateDegree(degree)
        }
    }

    fun updateIcon(iconUri: Uri) {
        launch {
            if (iconUri.toString().isNotEmpty()) {
                waterMarkRepo.updateIcon(iconUri)
            }
        }
    }

    fun saveOutput(format: Bitmap.CompressFormat, level: Int) {
        viewModelScope.launch {
            userRepo.updateFormat(format)
            userRepo.updateCompressLevel(level)
        }
        resetStatus()
    }

    fun removeImage(
        imageInfo: ImageInfo?,
        curSelectedPos: Int
    ) {
        val list = imageList.value?.first?.toMutableList() ?: return
        val removePos = list.indexOf(imageInfo)
        list.removeAt(removePos)
        val selectedPos =
            if (removePos < curSelectedPos || removePos >= (imageList.value?.first?.size
                    ?: 0) - 1
            ) {
                (curSelectedPos - 1).coerceAtLeast(0)
            } else {
                curSelectedPos
            }
        launch {
            autoScroll = false
            nextSelectedPos = selectedPos
            waterMarkRepo.updateImageList(list)
            if (removePos == curSelectedPos) {
                list.getOrNull(selectedPos)?.uri?.let { selectImage(it) }
            }
        }
    }

    fun updateColorPalette(palette: Palette) {
        colorPalette.postValue(palette)
    }

    fun resetStatus() {
        saveResult.postValue(Result.success(null))
        imageList.value?.first?.forEach {
            it.jobState = JobState.Ready
            saveProcess.value = it
        }
    }

    fun resetModeToText() {
        launch { waterMarkRepo.resetModeToText() }
    }

    fun compressImg(activity: Activity) {
        compressedJob = viewModelScope.launch(Dispatchers.IO) {
            waterMark.value?.let {
                compressedResult.postValue(Result.success(null, code = TYPE_COMPRESSING))
                val tmpFile = File.createTempFile("easy_water_mark_", "_compressed")
                activity.contentResolver.openInputStream(waterMarkRepo.imageInfoList.first().uri)
                    .use { input ->
                        tmpFile.outputStream().use { output ->
                            input?.copyTo(output)
                        }
                    }
                val compressedFile = Compressor.compress(activity, tmpFile)
                // clear tmp files
                if (tmpFile.exists()) {
                    tmpFile.delete()
                }
                try {
                    val compressedFileUri = FileProvider.getUriForFile(
                        activity,
                        "${BuildConfig.APPLICATION_ID}.fileprovider",
                        compressedFile
                    )
                    selectImage(compressedFileUri)
                    compressedResult.postValue(Result.success(null, code = TYPE_COMPRESS_OK))
                } catch (ie: IllegalArgumentException) {
                    compressedResult.postValue(
                        Result.failure(
                            null,
                            code = TYPE_COMPRESS_ERROR,
                            message = "Images creates uri failed."
                        )
                    )
                }
            } ?: kotlin.run {
                compressedResult.postValue(
                    Result.failure(
                        null,
                        code = TYPE_COMPRESS_ERROR,
                        message = "Config value is null."
                    )
                )
            }
        }
    }

    fun cancelCompressJob() {
        compressedJob?.cancel()
    }

    fun extraCrashInfo(activity: Activity, crashInfo: String?) {
        // user do not saving crash info into external storage
        // So that wo just share the internal file
        val mainContent = """
                    Dear developer, here are my crash info:
                    
                        $crashInfo
                    =====================
                    APP:
                    ${BuildConfig.VERSION_CODE}, ${BuildConfig.VERSION_NAME}, ${BuildConfig.BUILD_TYPE} 
                    Devices:
                    ${Build.VERSION.RELEASE}, ${Build.VERSION.SDK_INT}, ${Build.DEVICE}, ${Build.MODEL}, ${Build.PRODUCT}, ${Build.MANUFACTURER}
                    =====================
                    ${activity.getString(R.string.contributor_info)}
                    =====================
                    ${System.currentTimeMillis().formatDate("yyy-MM-dd")}
        """.trimIndent()
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "message/rfc822"
            putExtra(Intent.EXTRA_EMAIL, arrayOf("hi@rosuh.me"))
            putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.email_subject))
            putExtra(Intent.EXTRA_TEXT, mainContent)
        }
        try {
            activity.startActivity(
                Intent.createChooser(
                    intent,
                    activity.getString(R.string.crash_mail)
                )
            )
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            Toast.makeText(
                activity,
                activity.getString(R.string.tip_not_mail_found),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onCleared() {
        cancelCompressJob()
        super.onCleared()
    }

    fun saveUpgradeInfo() {
        launch { userRepo.saveVersionCode() }
    }

    companion object {
        const val TYPE_ERROR_NOT_IMG = "type_error_not_img"
        const val TYPE_ERROR_FILE_NOT_FOUND = "type_error_file_not_found"
        const val TYPE_ERROR_SAVE_OOM = "type_error_save_oom"
        const val TYPE_COMPRESS_ERROR = "type_CompressError"
        const val TYPE_COMPRESS_OK = "type_CompressOK"
        const val TYPE_COMPRESSING = "type_Compressing"
        const val TYPE_SAVING = "type_saving"
        const val TYPE_JOB_FINISH = "type_job_finish"
    }
}
