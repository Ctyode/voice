package com.example.gendercolorvoice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.AttributeSet
import android.widget.FrameLayout
import java.io.File
import java.io.FileOutputStream

/**
 * Composite view that hosts the gradient field + overlay and exposes
 * simple APIs for plotting points and exporting a PNG screenshot.
 */
class VoiceMapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    val field = GradientFieldView(context)
    val overlay = LabelsOverlayView(context)

    init {
        addView(field, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun applyConfig(cfg: AppConfig) {
        overlay.applyZonesFromConfig(cfg)
    }

    fun setPoint(x01: Float, y01: Float) {
        field.setPoint(x01, y01)
    }

    fun clear() {
        field.clearTrail()
        overlay.stats = null
        overlay.debugLines = null
        overlay.liveLines = null
        overlay.categoryText = null
    }

    fun exportPng(outFile: File): Boolean {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(2), height.coerceAtLeast(2), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        draw(c)
        return try {
            FileOutputStream(outFile).use { fos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos)
            }
            true
        } catch (_: Throwable) { false }
    }
}

