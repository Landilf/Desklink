package ru.landilf.shellink

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import com.termux.terminal.TextStyle

/** Canvas front end for Termux's Apache-2.0 VT engine; SSH bytes go to the emulator unchanged. */
class TerminalCanvasView(context: Context, private val state: TerminalState, private val resizeRemote: (Int, Int) -> Unit) : View(context) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 14f * resources.displayMetrics.scaledDensity }
    private val cellW = text.measureText("M").coerceAtLeast(1f)
    private val cellH = text.fontMetrics.let { it.descent - it.ascent }.coerceAtLeast(1f)
    private val bg = Paint(); private var scrollRows = 0
    private val palette = intArrayOf(0xff000000.toInt(),0xffcd0000.toInt(),0xff00cd00.toInt(),0xffcdcd00.toInt(),0xff0000ee.toInt(),0xffcd00cd.toInt(),0xff00cdcd.toInt(),0xffe5e5e5.toInt(),0xff7f7f7f.toInt(),0xffff0000.toInt(),0xff00ff00.toInt(),0xffffff00.toInt(),0xff5c5cff.toInt(),0xffff00ff.toInt(),0xff00ffff.toInt(),0xffffffff.toInt())

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cols = (w / cellW).toInt().coerceAtLeast(2); val rows = (h / cellH).toInt().coerceAtLeast(2)
        state.emulator.resize(cols, rows)
        resizeRemote(cols, rows)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(0xff101216.toInt())
        val emulator = state.emulator; val buffer = emulator.screen
        val rows = emulator.mRows; val cols = emulator.mColumns
        val scroll = scrollRows.coerceAtMost(buffer.activeTranscriptRows)
        val top = -scroll
        for (visible in 0 until rows) {
            val sourceRow = visible + top
            if (sourceRow >= rows || sourceRow < -buffer.activeTranscriptRows) continue
            for (col in 0 until cols) {
                val style = buffer.getStyleAt(sourceRow, col)
                var fore = color(TextStyle.decodeForeColor(style)); var back = color(TextStyle.decodeBackColor(style))
                val effects = TextStyle.decodeEffect(style)
                if (effects and TextStyle.CHARACTER_ATTRIBUTE_INVERSE != 0) { val t = fore; fore = back; back = t }
                val x = col * cellW; val y = visible * cellH
                bg.color = back; canvas.drawRect(x, y, x + cellW + 1f, y + cellH, bg)
                if (effects and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE == 0) {
                    val ch = buffer.getSelectedText(col, sourceRow, col, sourceRow)
                    if (ch.isNotEmpty()) { text.color = fore; text.isFakeBoldText = effects and TextStyle.CHARACTER_ATTRIBUTE_BOLD != 0; canvas.drawText(ch.take(2), x, y - text.fontMetrics.ascent, text) }
                }
            }
        }
        if (scroll == 0 && emulator.shouldCursorBeVisible()) {
            bg.color = 0xffeeeeee.toInt()
            val x = emulator.cursorCol * cellW; val y = emulator.cursorRow * cellH
            canvas.drawRect(x, y, x + 2f, y + cellH, bg)
        }
    }
    private fun color(code: Int): Int {
        if (code in state.emulator.mColors.mCurrentColors.indices) return state.emulator.mColors.mCurrentColors[code]
        if (code and -0x1000000 == -0x1000000) return code
        if (code < 16) return palette[code.coerceAtLeast(0)]
        if (code < 232) {
            val n = code - 16; fun c(v: Int) = if (v == 0) 0 else 55 + 40 * v
            return android.graphics.Color.rgb(c(n / 36), c((n / 6) % 6), c(n % 6))
        }
        val v = 8 + (code - 232) * 10
        return android.graphics.Color.rgb(v, v, v)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) { scrollRows = (scrollRows - event.getAxisValue(MotionEvent.AXIS_VSCROLL).toInt() * 3).coerceAtLeast(0); invalidate(); return true }
        if (event.action == MotionEvent.ACTION_MOVE && event.historySize > 0) {
            val delta = (event.getHistoricalY(0) - event.y).toInt()
            if (kotlin.math.abs(delta) > cellH) { scrollRows = (scrollRows + if (delta > 0) 2 else -2).coerceAtLeast(0); invalidate() }
        }
        return true
    }
}
