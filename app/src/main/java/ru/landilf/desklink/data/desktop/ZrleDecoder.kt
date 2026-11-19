package ru.landilf.desklink.data.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** One persistent zlib stream per RFB connection, as required by ZRLE. */
internal class ZrleDecoder : AutoCloseable {
    private val inflater = Inflater()

    fun read(input: DataInputStream, framebuffer: RawFramebuffer, x: Int, y: Int, width: Int, height: Int) {
        framebuffer.validateRectangle(x, y, width, height)
        val length = input.readInt()
        val maxSize = (width.toLong() * height * 4 + ((width + 63L) / 64) * ((height + 63L) / 64) * 512 + 65536).coerceAtMost(80L * 1024 * 1024).toInt()
        if (length !in 1..maxSize) throw IOException("Invalid ZRLE data length")
        val compressed = ByteArray(length).also(input::readFully)
        inflater.setInput(compressed)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        try {
            while (!inflater.needsInput()) {
                val count = inflater.inflate(buffer)
                if (count == 0) throw IOException("Incomplete ZRLE stream")
                if (bytes.size().toLong() + count > maxSize) throw IOException("ZRLE output too large")
                bytes.write(buffer, 0, count)
            }
        } catch (e: DataFormatException) { throw IOException("Invalid ZRLE stream", e) }
        val data = DataInputStream(ByteArrayInputStream(bytes.toByteArray()))
        for (tileY in 0 until height step 64) for (tileX in 0 until width step 64) {
            val w = minOf(64, width - tileX); val h = minOf(64, height - tileY)
            val mode = data.readUnsignedByte()
            fun color(): Int {
                val b = data.readUnsignedByte(); val g = data.readUnsignedByte(); val r = data.readUnsignedByte()
                return (0xff shl 24) or (r shl 16) or (g shl 8) or b
            }
            fun put(index: Int, value: Int) { framebuffer.pixels[(y + tileY + index / w) * framebuffer.width + x + tileX + index % w] = value }
            fun runLength(remaining: Int): Int {
                var n = 1
                do { val b = data.readUnsignedByte(); n += b; if (n > remaining) throw IOException("Invalid ZRLE run"); if (b != 255) return n } while (true)
            }
            when (mode) {
                0 -> repeat(w * h) { put(it, color()) }
                1 -> { val value = color(); repeat(w * h) { put(it, value) } }
                in 2..16 -> {
                    val palette = IntArray(mode) { color() }
                    val bits = if (mode == 2) 1 else if (mode <= 4) 2 else 4
                    for (row in 0 until h) {
                        var packed = 0; var left = 0
                        for (col in 0 until w) {
                            if (left == 0) { packed = data.readUnsignedByte(); left = 8 }
                            left -= bits
                            val index = (packed shr left) and ((1 shl bits) - 1)
                            if (index >= palette.size) throw IOException("Invalid ZRLE palette index")
                            put(row * w + col, palette[index])
                        }
                    }
                }
                128 -> {
                    var position = 0
                    while (position < w * h) { val value = color(); val run = runLength(w * h - position); repeat(run) { put(position++, value) } }
                }
                in 130..255 -> {
                    val palette = IntArray(mode - 128) { color() }
                    var position = 0
                    while (position < w * h) {
                        val value = data.readUnsignedByte(); val index = value and 127
                        if (index >= palette.size) throw IOException("Invalid ZRLE palette index")
                        val run = if (value and 128 != 0) runLength(w * h - position) else 1
                        repeat(run) { put(position++, palette[index]) }
                    }
                }
                else -> throw IOException("Unsupported ZRLE tile: $mode")
            }
        }
        if (data.available() != 0) throw IOException("Unexpected ZRLE trailing data")
    }

    override fun close() = inflater.end()
}
