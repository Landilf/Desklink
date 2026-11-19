package ru.landilf.desklink.data.desktop

import java.io.DataInputStream
import java.io.IOException

/** Decodes raw RFB rectangles after the client requests little-endian 32-bit RGB pixels. */
internal class RawFramebuffer(val width: Int, val height: Int) {
    init {
        require(width in 1..MAX_SIDE && height in 1..MAX_SIDE)
        require(width.toLong() * height <= MAX_PIXELS)
    }

    val pixels = IntArray(width * height) { 0xff000000.toInt() }

    fun validateRectangle(x: Int, y: Int, rectWidth: Int, rectHeight: Int) {
        if (x < 0 || y < 0 || rectWidth <= 0 || rectHeight <= 0 ||
            x.toLong() + rectWidth > width || y.toLong() + rectHeight > height
        ) throw IOException("RFB rectangle exceeds framebuffer")
    }

    @Throws(IOException::class)
    fun readRectangle(input: DataInputStream, x: Int, y: Int, rectWidth: Int, rectHeight: Int) {
        validateRectangle(x, y, rectWidth, rectHeight)

        val row = ByteArray(rectWidth * 4)
        for (line in 0 until rectHeight) {
            input.readFully(row)
            for (column in 0 until rectWidth) {
                val offset = column * 4
                val blue = row[offset].toInt() and 0xff
                val green = row[offset + 1].toInt() and 0xff
                val red = row[offset + 2].toInt() and 0xff
                pixels[(y + line) * width + x + column] =
                    (0xff shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }
    }

    companion object {
        private const val MAX_SIDE = 8192
        private const val MAX_PIXELS = 16_777_216L
    }
}
