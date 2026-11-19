package ru.landilf.desklink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import ru.landilf.desklink.data.desktop.RfbClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream

class RfbClientTest {
    @Test fun closesTransportBeforeStreams() {
        var transportClosed = false
        val input = object : InputStream() {
            override fun read(): Int = -1
            override fun close() { assertEquals(true, transportClosed) }
        }
        RfbClient(input, ByteArrayOutputStream()) { transportClosed = true }.close()
        assertEquals(true, transportClosed)
    }
    @Test fun initializesAndDecodesRawFrame() {
        val server = ByteArrayOutputStream()
        DataOutputStream(server).apply {
            writeBytes("RFB 003.008\n"); writeByte(1); writeByte(1); writeInt(0)
            writeShort(1); writeShort(1); write(ByteArray(16)); writeInt(4); writeBytes("test")
            writeByte(0); writeByte(0); writeShort(1)
            writeShort(0); writeShort(0); writeShort(1); writeShort(1); writeInt(0)
            write(byteArrayOf(3, 2, 1, 0))
        }
        val outgoing = ByteArrayOutputStream()
        val client = RfbClient(ByteArrayInputStream(server.toByteArray()), outgoing)
        client.initialize()
        client.readUpdate()
        assertEquals("test", client.desktopName)
        assertEquals(0xff010203.toInt(), client.framebuffer.pixels[0])
    }

    @Test fun rejectsUnsupportedSecurity() {
        val data = "RFB 003.008\n".toByteArray() + byteArrayOf(1, 2)
        val client = RfbClient(ByteArrayInputStream(data), ByteArrayOutputStream())
        assertThrows(IOException::class.java) { client.initialize() }
    }
}
