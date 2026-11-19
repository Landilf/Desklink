package ru.landilf.desklink.data.desktop

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

/** Connects to wayvnc using VeNCrypt 0.2 X509Plain with a verified certificate pin. */
internal object VeNCryptConnector {
    fun connect(host: String, port: Int, fingerprint: ByteArray, username: String, password: String): RfbClient {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, port), 10_000)
            socket.soTimeout = 15_000
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val version = ByteArray(12).also(input::readFully).toString(StandardCharsets.US_ASCII)
            if (version != "RFB 003.008\n") throw IOException("Unsupported RFB version: $version")
            output.write("RFB 003.008\n".toByteArray(StandardCharsets.US_ASCII)); output.flush()
            val count = input.readUnsignedByte()
            if (count == 0) throw IOException("Server rejected connection")
            val methods = ByteArray(count).also(input::readFully)
            if (methods.none { it.toInt() == 19 }) throw IOException("Server does not support VeNCrypt")
            output.writeByte(19); output.flush()
            if (input.readUnsignedByte() != 0 || input.readUnsignedByte() < 2) throw IOException("Unsupported VeNCrypt version")
            output.writeByte(0); output.writeByte(2); output.flush()
            if (input.readUnsignedByte() != 0) throw IOException("VeNCrypt version rejected")
            val subtypes = input.readUnsignedByte()
            var found = false
            repeat(subtypes) { if (input.readInt() == 262) found = true }
            if (!found) throw IOException("Server does not support X509Plain")
            output.writeInt(262); output.flush()
            if (input.readUnsignedByte() != 1) throw IOException("VeNCrypt subtype rejected")
            val tls = PinnedTls.upgrade(socket, host, port, fingerprint)
            val secureIn = DataInputStream(tls.inputStream)
            val secureOut = DataOutputStream(tls.outputStream)
            val userBytes = username.toByteArray(StandardCharsets.UTF_8)
            val passwordBytes = password.toByteArray(StandardCharsets.UTF_8)
            try {
                secureOut.writeInt(userBytes.size); secureOut.writeInt(passwordBytes.size)
                secureOut.write(userBytes); secureOut.write(passwordBytes); secureOut.flush()
            } finally { passwordBytes.fill(0) }
            if (secureIn.readInt() != 0) {
                val size = secureIn.readInt()
                if (size !in 0..4096) throw IOException("Authentication failed")
                throw IOException(ByteArray(size).also(secureIn::readFully).toString(StandardCharsets.UTF_8))
            }
            tls.soTimeout = 0
            return RfbClient(secureIn, secureOut) { tls.close() }.also { it.initializeAuthenticated() }
        } catch (error: Exception) {
            socket.close()
            throw error
        }
    }
}
