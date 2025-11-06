package com.example.gendercolorvoice

import android.content.Context
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class SolidColorView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private var animator: ValueAnimator? = null

    fun setColor(color: Int) {
        val from = paint.color
        if (from == color) return
        animator?.cancel()
        animator = ValueAnimator.ofObject(ArgbEvaluator(), from, color).apply {
            duration = 220 // ms, short smooth fade
            addUpdateListener {
                paint.color = it.animatedValue as Int
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(paint.color)
    }
}
