package ru.landilf.desklink

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.landilf.desklink.data.desktop.VeNCryptConnector
import javax.net.ssl.SSLHandshakeException

/** Opt-in test against a wayvnc instance configured by the developer. */
class WayvncIntegrationTest {
    @Test(timeout = 20000)
    fun connectsAndReadsFrame() {
        val fingerprint = System.getenv("DESKLINK_VNC_TEST_PIN")
        assumeTrue(!fingerprint.isNullOrBlank())
        val pin = fingerprint!!.replace(":", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        VeNCryptConnector.connect("127.0.0.1", 5909, pin, "test", "test-only").use { client ->
            assertTrue(client.framebuffer.width > 0)
            assertTrue(client.framebuffer.height > 0)
            while (!client.readUpdate()) { /* Skip bell and clipboard events. */ }
            assertEquals(16, client.lastEncoding)
        }
    }

    @Test(timeout = 20000)
    fun rejectsWrongCertificate() {
        assumeTrue(!System.getenv("DESKLINK_VNC_TEST_PIN").isNullOrBlank())
        assertThrows(SSLHandshakeException::class.java) {
            VeNCryptConnector.connect("127.0.0.1", 5909, ByteArray(32), "test", "test-only")
        }
    }
}
