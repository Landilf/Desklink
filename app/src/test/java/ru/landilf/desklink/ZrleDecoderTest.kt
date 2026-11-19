package ru.landilf.desklink

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import ru.landilf.desklink.data.desktop.RawFramebuffer
import ru.landilf.desklink.data.desktop.ZrleDecoder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.Deflater

class ZrleDecoderTest {
    @Test fun decodesPackedPaletteAndPersistentStream() {
        val deflater = Deflater()
        val decoder = ZrleDecoder()
        val framebuffer = RawFramebuffer(3, 1)
        fun rectangle(data: ByteArray) {
            deflater.setInput(data)
            val compressed = ByteArray(512)
            val size = deflater.deflate(compressed, 0, compressed.size, Deflater.SYNC_FLUSH)
            val packet = ByteArrayOutputStream()
            DataOutputStream(packet).apply { writeInt(size); write(compressed, 0, size) }
            decoder.read(DataInputStream(ByteArrayInputStream(packet.toByteArray())), framebuffer, 0, 0, 3, 1)
        }
        rectangle(byteArrayOf(2, 0, 0, 0, 3, 2, 1, 0x40))
        assertArrayEquals(intArrayOf(0xff000000.toInt(), 0xff010203.toInt(), 0xff000000.toInt()), framebuffer.pixels)
        rectangle(byteArrayOf(1, 6, 5, 4))
        assertArrayEquals(IntArray(3) { 0xff040506.toInt() }, framebuffer.pixels)
        decoder.close(); deflater.end()
    }
}
