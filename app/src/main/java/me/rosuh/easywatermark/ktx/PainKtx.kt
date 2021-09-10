package me.rosuh.easywatermark.ktx

import android.graphics.*
import android.graphics.Shader.TileMode
import android.text.TextPaint
import me.rosuh.easywatermark.model.WaterMarkConfig

/**
 * 因为预览和实际图像之间存在缩放，所以在预览时要除去缩放比。而在保存时，就不需要了
 * Because there is a zoom between the preview and the actual image, the zoom ratio should be removed when previewing
 * When saving, it’s not needed
 * @author hi@rosuh.me
 * @date 2020/9/8
 */
fun Paint.applyConfig(
    config: WaterMarkConfig?,
    isScale: Boolean = true
): Paint {
    val size = config?.textSize ?: 14f
    textSize = if (isScale) {
        size
    } else {
        size * ((config?.imageScale?.get(0) ?: 1f))
    }
    color = config?.textColor ?: Color.RED
    alpha = config?.alpha ?: 128
    style = config?.textStyle?.obtainSysStyle() ?: Paint.Style.FILL
    typeface =
        Typeface.create(typeface, config?.textTypeface?.obtainSysTypeface() ?: Typeface.NORMAL)
    isAntiAlias = true
    isDither = true
    textAlign = Paint.Align.CENTER

    val textShader: Shader = LinearGradient(
        0f, 0f, measureText(config?.text), textSize, intArrayOf(
            Color.parseColor("#F97C3C"),
            Color.parseColor("#FDB54E"),
            Color.parseColor("#64B678"),
            Color.parseColor("#478AEA"),
            Color.parseColor("#8446CC")
        ), null, TileMode.CLAMP
    )
    shader = textShader
    return this
}

fun TextPaint.applyConfig(
    config: WaterMarkConfig?,
    isScale: Boolean = true
): TextPaint {
    return (this as Paint).applyConfig(config, isScale) as TextPaint
}