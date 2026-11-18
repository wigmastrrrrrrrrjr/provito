package com.provito.browser

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import androidx.core.graphics.PathParser

/**
 * Provito startup animation.
 *
 * Timeline (~1.7 s, one animator, all drawn on canvas - no layout cost):
 *  1. shield scales/fades in
 *  2. check mark draws itself smoothly inside the shield
 *  3. "Provito" fades in while the logo glides to the left side of the text
 *  4. the whole overlay fades out, revealing the loaded app underneath
 */
class SplashView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        private const val BG = 0xFF14141F.toInt()
        private const val SHIELD = 0xFF8E7BFF.toInt()
        private const val CHECK = 0xFF1B1B2F.toInt()
        private const val TEXT = 0xFFF3F0FF.toInt()
        private const val DURATION = 1700L
    }

    private val bgPaint = Paint().apply { color = BG }
    private val shieldPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = SHIELD
        style = Paint.Style.FILL
    }
    private val checkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = CHECK
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = TEXT
        textSize = resources.displayMetrics.scaledDensity * 30f
        isFakeBoldText = true
        textAlign = Paint.Align.LEFT
    }

    private val shieldPath: Path
    private val checkPath: Path
    private val checkMeasure: PathMeasure
    private var checkLength = 0f
    private val segment = Path()

    private var shieldAlpha = 0f
    private var shieldScale = 0.92f
    private var checkProgress = 0f
    private var textAlpha = 0f
    private var slide = 0f          // 0 = logo centered, 1 = logo beside text

    private var animator: ValueAnimator? = null
    private var textWidth = 0f

    init {
        // Shape paths from the launcher icon (108x108 viewport), centered.
        fun scaled(pathData: String): Path {
            val p = PathParser.createPathFromPathData(pathData)
            val m = Matrix()
            m.setScale(2.2f, 2.2f)
            val dst = Path()
            p.transform(m, dst)
            val b = RectF()
            dst.computeBounds(b, true)
            m.reset()
            m.setTranslate(-b.centerX(), -b.centerY())
            dst.transform(m)
            return dst
        }
        shieldPath = scaled("M54,24 L78,34 L78,52 C78,66 68,78 54,84 C40,78 30,66 30,52 L30,34 Z")
        checkPath = scaled("M50,52.5 L58,44.5 L61,47.5 L50,58.5 L45,53.5 L48,50.5 Z")
        checkMeasure = PathMeasure(checkPath, false)
        checkLength = checkMeasure.length
        textWidth = textPaint.measureText("Provito")
    }

    fun start() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = DURATION
            interpolator = LinearInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val easeOut = DecelerateInterpolator(1.4f)
                shieldAlpha = (f / 0.25f).coerceIn(0f, 1f)
                shieldScale = 0.92f + 0.08f * easeOut.getInterpolation(
                    (f / 0.25f).coerceIn(0f, 1f)
                )
                checkProgress = ((f - 0.18f) / 0.45f).coerceIn(0f, 1f)
                textAlpha = ((f - 0.45f) / 0.2f).coerceIn(0f, 1f)
                slide = easeOut.getInterpolation(((f - 0.5f) / 0.25f).coerceIn(0f, 1f))
                alpha = 1f - ((f - 0.78f) / 0.22f).coerceIn(0f, 1f)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var done = false
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (done) return
                    done = true
                    (parent as? android.view.ViewGroup)?.removeView(this@SplashView)
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f - height * 0.04f

        canvas.drawColor(BG)

        val gap = textWidth * 0.12f
        val shieldW = 105.6f * shieldScale
        val shieldHalf = shieldW / 2f
        val groupW = shieldW + gap + textWidth
        val groupLeft = cx - groupW / 2f
        val logoCenterFinal = groupLeft + shieldHalf
        val logoCenter = cx + slide * (logoCenterFinal - cx)
        val textLeft = groupLeft + shieldW + gap

        // Shield
        canvas.save()
        canvas.translate(logoCenter, cy)
        canvas.scale(shieldScale, shieldScale)
        shieldPaint.alpha = (shieldAlpha * 255).toInt()
        canvas.drawPath(shieldPath, shieldPaint)

        // Check mark drawn progressively.
        if (checkProgress > 0f) {
            segment.reset()
            checkMeasure.getSegment(0f, checkLength * checkProgress, segment, true)
            checkPaint.alpha = shieldPaint.alpha
            canvas.drawPath(segment, checkPaint)
        }
        canvas.restore()

        // "Provito"
        if (textAlpha > 0f) {
            val fm = textPaint.fontMetrics
            val baseline = cy + (fm.descent - fm.ascent) / 2f - fm.descent
            textPaint.alpha = (textAlpha * 255).toInt()
            canvas.drawText("Provito", textLeft, baseline, textPaint)
        }
    }
}
