package ru.landilf.desklink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.landilf.desklink.data.desktop.RawFramebuffer
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException

class RawFramebufferTest {
    @Test fun decodesRectangleAtOffset() {
        val framebuffer = RawFramebuffer(3, 2)
        val data = byteArrayOf(3, 2, 1, 0, 6, 5, 4, 0)
        framebuffer.readRectangle(DataInputStream(ByteArrayInputStream(data)), 1, 1, 2, 1)
        assertEquals(0xff010203.toInt(), framebuffer.pixels[4])
        assertEquals(0xff040506.toInt(), framebuffer.pixels[5])
        assertEquals(0xff000000.toInt(), framebuffer.pixels[0])
    }

    @Test fun rejectsRectangleOutsideFramebuffer() {
        val framebuffer = RawFramebuffer(2, 2)
        assertThrows(IOException::class.java) {
            framebuffer.readRectangle(DataInputStream(ByteArrayInputStream(byteArrayOf())), 1, 1, 2, 1)
        }
    }
}
