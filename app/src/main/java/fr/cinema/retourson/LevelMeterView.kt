package fr.cinema.retourson

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import java.util.Locale
import kotlin.math.log10

/**
 * Barre de niveau crête de -60 à 0 dBFS avec mémoire de crête, graduations chiffrées
 * et valeur de la crête affichée à droite.
 */
class LevelMeterView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var db = MIN_DB
    private var holdDb = MIN_DB
    private var holdTime = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val scaled = resources.displayMetrics.scaledDensity
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(140, 140, 140)
        textSize = 11 * scaled
        textAlign = Paint.Align.CENTER
    }
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 20 * scaled
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }

    /** [peak] : crête linéaire 0..1 depuis la dernière mise à jour (toutes les ~50 ms). */
    fun setPeak(peak: Float) {
        val newDb = if (peak <= 0f) MIN_DB else (20f * log10(peak)).coerceIn(MIN_DB, 0f)
        val oldDb = db
        val oldHold = holdDb
        db = if (newDb > db) newDb else maxOf(newDb, db - 1.5f)
        val now = SystemClock.uptimeMillis()
        if (newDb >= holdDb || now - holdTime > 1500) {
            holdDb = newDb
            holdTime = now
        }
        // Rien ne bouge (silence, micro coupé) : pas de redessin.
        if (db != oldDb || holdDb != oldHold) invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val valueWidth = 78 * density
        val w = width - valueWidth
        val labelHeight = 16 * density
        val h = height - labelHeight

        paint.color = Color.rgb(40, 40, 40)
        canvas.drawRect(0f, 0f, w, h, paint)

        fun x(d: Float) = (d - MIN_DB) / -MIN_DB * w
        val level = x(db)
        val yellow = x(-12f)
        val red = x(-6f)
        paint.color = GREEN
        canvas.drawRect(0f, 0f, minOf(level, yellow), h, paint)
        if (level > yellow) {
            paint.color = YELLOW
            canvas.drawRect(yellow, 0f, minOf(level, red), h, paint)
        }
        if (level > red) {
            paint.color = RED
            canvas.drawRect(red, 0f, level, h, paint)
        }

        paint.color = Color.WHITE
        val hx = x(holdDb)
        canvas.drawRect(hx - 1.5f * density, 0f, hx + 1.5f * density, h, paint)

        paint.color = Color.argb(120, 0, 0, 0)
        for (d in TICKS) {
            val tx = x(d)
            canvas.drawRect(tx, 0f, tx + density, h, paint)
        }
        val labelY = height - 3 * density
        for (d in LABELS) {
            labelPaint.textAlign = if (d == 0f) Paint.Align.RIGHT else Paint.Align.CENTER
            canvas.drawText(d.toInt().toString(), x(d), labelY, labelPaint)
        }

        valuePaint.color = when {
            holdDb > -6f -> RED
            holdDb > -12f -> YELLOW
            holdDb <= MIN_DB -> Color.GRAY
            else -> Color.WHITE
        }
        val text = if (holdDb <= MIN_DB) "-∞ dB" else String.format(Locale.FRANCE, "%.0f dB", holdDb)
        canvas.drawText(text, width.toFloat(), h / 2 + valuePaint.textSize / 3, valuePaint)
    }

    companion object {
        private const val MIN_DB = -60f
        private val TICKS = floatArrayOf(-54f, -48f, -42f, -36f, -30f, -24f, -18f, -12f, -6f)
        private val LABELS = floatArrayOf(-48f, -36f, -24f, -18f, -12f, -6f, 0f)
        private val GREEN = Color.rgb(60, 200, 90)
        private val YELLOW = Color.rgb(230, 200, 40)
        private val RED = Color.rgb(230, 50, 50)
    }
}
