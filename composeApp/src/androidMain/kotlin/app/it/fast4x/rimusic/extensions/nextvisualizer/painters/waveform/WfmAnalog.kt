package app.it.fast4x.rimusic.extensions.nextvisualizer.painters.waveform

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import app.it.fast4x.rimusic.extensions.nextvisualizer.painters.Painter
import app.it.fast4x.rimusic.extensions.nextvisualizer.utils.VisualizerHelper

class WfmAnalog(
    val colorPaint: Int = Color.WHITE,
    override var paint: Paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colorPaint;style = Paint.Style.STROKE;strokeWidth = 2f
    },
    //
    var startHz: Int = 0,
    var endHz: Int = 2000,
    //
    var num: Int = 256,
    //
    var ampR: Float = 1f
) : Painter() {

    private val path = Path()
    private var skipFrame = false
    private val fft = DoubleArray(256)
    private lateinit var waveform : ByteArray

    override fun calc(helper: VisualizerHelper) {
        val filled = helper.fillFftMagnitudeRange(startHz, endHz, fft)
        
        var quiet = true
        for (i in 0 until filled) {
            if (fft[i] > 5f) {
                quiet = false
                break
            }
        }

        if (quiet) {
            skipFrame = true
            return
        } else {
            skipFrame = false
        }

        waveform = helper.getWave()
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    override fun draw(canvas: Canvas, helper: VisualizerHelper) {
        if (skipFrame) return

        val width = canvas.width.toFloat()

        val point = waveform.size / (num + 1)
        val sliceWidth = width / num

        path.moveTo(0f, (-waveform[0].toUByte().toInt() + 128f) * ampR)
        for (i in 1..num)
            path.lineTo(sliceWidth * i, (-waveform[point * i].toUByte().toInt() + 128f) * ampR)
        drawHelper(canvas, "a", 0f, .5f) { canvas.drawPath(path, paint) }
        path.reset()
    }
}


