package me.rosuh.easywatermark.utils

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import kotlin.math.max


@Throws(FileNotFoundException::class, OutOfMemoryError::class)
@Synchronized
suspend fun decodeBitmapFromUri(resolver: ContentResolver, uri: Uri): Bitmap? =
    withContext(Dispatchers.IO) {
        resolver.openInputStream(uri).use {
            return@use BitmapFactory.decodeStream(it)
        }

    }

suspend fun decodeSampledBitmapFromResource(
    resolver: ContentResolver,
    uri: Uri,
    reqWidth: Int,
    reqHeight: Int,
    scale: FloatArray = FloatArray(1) { 1f }
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        return@withContext BitmapFactory.Options().run {
            inJustDecodeBounds = true
            resolver.openInputStream(uri).use { `is` ->
                BitmapFactory.decodeStream(`is`, null, this)
            }

            // Calculate inSampleSize
            inSampleSize = calculateInSampleSize(this, reqWidth, reqHeight)

            // Decode bitmap with inSampleSize set
            inJustDecodeBounds = false

            scale[0] = max(outWidth.toFloat() / reqWidth, outHeight.toFloat() / reqHeight)
            // fixme make the code more elegant
            resolver.openInputStream(uri).use { inputStream ->
                val b = BitmapFactory.decodeStream(inputStream, null, this)

                val exif =
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        ExifInterface(resolver.openFile(uri, "r", null)!!.fileDescriptor)
                    } else {
                        ExifInterface(uri.path!!)
                    }
                val orientation: Int = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )

                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL -> {
                        // do not need to rotate bitmap
                        return@withContext b
                    }
                    else -> {
                    }
                }

                val rotatedBitmap = Bitmap.createBitmap(b!!, 0, 0, b.width, b.height, matrix, false)
                if (rotatedBitmap != b && !b.isRecycled) {
                    b.recycle()
                }
                return@use rotatedBitmap
            }
        }
    } catch (fne: FileNotFoundException) {
        return@withContext null
    } catch (oom: OutOfMemoryError) {
        Log.i(this::class.simpleName, "Decoding sampled bitmap from resource throw oom")
        return@withContext null
    }
    // First decode with inJustDecodeBounds=true to check dimensions
}

fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
    // Raw height and width of image
    val (height: Int, width: Int) = options.run { outHeight to outWidth }
    var inSampleSize = 1

    val isLand = options.outWidth > options.outHeight
    val ratio = options.outWidth.toFloat() / options.outHeight

    val ratioWidth = if (isLand) {
        options.outWidth.coerceAtMost(reqWidth)
    } else {
        (options.outHeight.coerceAtMost(reqHeight) * ratio).toInt()
    }

    val ratioHeight = if (isLand) {
        (options.outWidth.coerceAtMost(reqWidth) / ratio).toInt()
    } else {
        options.outHeight.coerceAtMost(reqHeight)
    }

    if (height > ratioHeight || width > ratioWidth) {

        val halfHeight: Int = height / 2
        val halfWidth: Int = width / 2

        // Calculate the largest inSampleSize value that is a power of 2 and keeps both
        // height and width larger than the requested height and width.
        while (halfHeight / inSampleSize >= ratioHeight && halfWidth / inSampleSize >= ratioWidth) {
            inSampleSize *= 2
        }
    }

    return inSampleSize
}

// Get a MemoryInfo object for the device's current memory status.
fun getAvailableMemory(context: Context): ActivityManager.MemoryInfo {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    return ActivityManager.MemoryInfo().also { memoryInfo ->
        activityManager.getMemoryInfo(memoryInfo)
    }
}