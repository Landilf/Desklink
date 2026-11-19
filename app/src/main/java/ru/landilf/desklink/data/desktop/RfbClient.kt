package ru.landilf.desklink.data.desktop

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** RFB 3.8, for a connection already authenticated and encrypted by the transport. */
internal class RfbClient(input: InputStream, output: OutputStream, private val closeTransport: (() -> Unit)? = null) : Closeable {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)
    lateinit var framebuffer: RawFramebuffer
        private set
    var desktopName: String = ""
        private set

    fun initialize() {
        val version = ByteArray(12).also(input::readFully).toString(StandardCharsets.US_ASCII)
        if (version != "RFB 003.008\n") throw IOException("Unsupported RFB version: $version")
        output.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII)); output.flush()
        val count = input.readUnsignedByte()
        if (count == 0) throw IOException(readString())
        val methods = ByteArray(count).also(input::readFully)
        if (methods.none { it.toInt() == 1 }) throw IOException("RFB server does not offer transport-protected security type")
        output.writeByte(1); output.flush()
        if (input.readInt() != 0) throw IOException(readString())
        initializeAuthenticated()
    }

    fun initializeAuthenticated() {
        output.writeByte(1); output.flush()
        val width = input.readUnsignedShort()
        val height = input.readUnsignedShort()
        ByteArray(16).also(input::readFully)
        desktopName = readString()
        framebuffer = try { RawFramebuffer(width, height) } catch (e: IllegalArgumentException) { throw IOException("Invalid framebuffer dimensions", e) }
        synchronized(output) {
            output.writeByte(0); output.write(byteArrayOf(0, 0, 0))
            output.write(byteArrayOf(32, 24, 0, 1, 0, -1, 0, -1, 0, -1, 16, 8, 0, 0, 0, 0))
            output.writeByte(2); output.writeByte(0); output.writeShort(1); output.writeInt(0)
            output.flush()
        }
        requestUpdate(false)
    }

    fun readUpdate(): Boolean {
        when (val type = input.readUnsignedByte()) {
            0 -> {
                input.readUnsignedByte()
                val rectangles = input.readUnsignedShort()
                repeat(rectangles) {
                    val x = input.readUnsignedShort(); val y = input.readUnsignedShort()
                    val width = input.readUnsignedShort(); val height = input.readUnsignedShort()
                    val encoding = input.readInt()
                    if (encoding != 0) throw IOException("Unsupported RFB encoding: $encoding")
                    framebuffer.readRectangle(input, x, y, width, height)
                }
                requestUpdate(true)
                return true
            }
            2 -> return false
            3 -> { input.skipBytes(3); val size = input.readInt(); if (size !in 0..1_048_576) throw IOException("Invalid clipboard length"); input.readFully(ByteArray(size)); return false }
            else -> throw IOException("Unsupported RFB server message: $type")
        }
    }

    fun pointer(x: Int, y: Int, buttons: Int) = synchronized(output) {
        output.writeByte(5); output.writeByte(buttons and 255)
        output.writeShort(x.coerceIn(0, framebuffer.width - 1)); output.writeShort(y.coerceIn(0, framebuffer.height - 1)); output.flush()
    }

    fun key(keysym: Int, down: Boolean) = synchronized(output) {
        output.writeByte(4); output.writeByte(if (down) 1 else 0); output.writeShort(0); output.writeInt(keysym); output.flush()
    }

    private fun requestUpdate(incremental: Boolean) = synchronized(output) {
        output.writeByte(3); output.writeByte(if (incremental) 1 else 0); output.writeShort(0); output.writeShort(0)
        output.writeShort(framebuffer.width); output.writeShort(framebuffer.height); output.flush()
    }

    private fun readString(): String {
        val length = input.readInt()
        if (length !in 0..1_048_576) throw IOException("Invalid RFB string length")
        return ByteArray(length).also(input::readFully).toString(StandardCharsets.UTF_8)
    }

    override fun close() {
        try { closeTransport?.invoke() } finally { try { input.close() } finally { output.close() } }
    }
}
