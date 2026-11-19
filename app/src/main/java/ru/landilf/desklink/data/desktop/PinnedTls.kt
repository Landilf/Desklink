package ru.landilf.desklink.data.desktop

import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/** Upgrades an established protocol connection to TLS with a pre-verified certificate pin. */
internal object PinnedTls {
    fun upgrade(socket: Socket, host: String, port: Int, certificateSha256: ByteArray): SSLSocket {
        require(certificateSha256.size == 32) { "Expected a SHA-256 certificate fingerprint" }
        val pin = certificateSha256.clone()
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                throw CertificateException("Client certificates are not accepted")
            }
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val certificate = chain?.firstOrNull() ?: throw CertificateException("Server certificate missing")
                certificate.checkValidity()
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                if (!MessageDigest.isEqual(pin, actual)) throw CertificateException("Server certificate fingerprint mismatch")
            }
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(trust), SecureRandom())
        val tls = context.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        try {
            tls.useClientMode = true
            tls.startHandshake()
            return tls
        } catch (error: Exception) {
            tls.close()
            throw error
        }
    }
}
