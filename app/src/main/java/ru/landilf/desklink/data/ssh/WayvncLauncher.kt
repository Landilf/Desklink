package ru.landilf.desklink.data.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.Resource
import ru.landilf.desklink.domain.model.SshProfile
import ru.landilf.desklink.domain.repository.ConnectionRepository
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.TimeUnit

class WayvncLauncher(private val repo: ConnectionRepository) {
    suspend fun start(profile: SshProfile, sessionName: String) = execute(profile, sessionName, false)
    suspend fun stop(profile: SshProfile, sessionName: String) = execute(profile, sessionName, true)

    private suspend fun execute(profile: SshProfile, sessionName: String, stop: Boolean) = withContext(Dispatchers.IO) {
        require(sessionName.matches(Regex("desklink-vnc-[0-9a-f]{32}")))
        val address = profile.vncHost.ifBlank { profile.host }.trim()
        if (!address.matches(Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")) ||
            address.split('.').any { it.toInt() !in 0..255 } || profile.vncPort !in 1..65535
        ) throw IOException("VNC bind address must be an IPv4 address")
        val known = repo.fingerprint(profile.host, profile.port)
            ?: throw IOException("SSH host key is not trusted yet; connect using SSH first")
        synchronized(net.schmizz.sshj.common.SecurityUtils::class.java) {
            if (java.security.Security.getProvider("BC") !is org.bouncycastle.jce.provider.BouncyCastleProvider) {
                java.security.Security.removeProvider("BC")
                java.security.Security.insertProviderAt(org.bouncycastle.jce.provider.BouncyCastleProvider(), 1)
            }
        }
        SSHClient().use { ssh ->
            ssh.connectTimeout = 12000
            ssh.timeout = 15000
            ssh.addHostKeyVerifier(object : HostKeyVerifier {
                override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
                override fun verify(hostname: String, port: Int, hostKey: java.security.PublicKey): Boolean {
                    val bytes = Buffer.PlainBuffer().putPublicKey(hostKey).compactData
                    val fingerprint = "SHA256:" + Base64.getEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))
                    return MessageDigest.isEqual(known.toByteArray(), fingerprint.toByteArray())
                }
            })
            ssh.connect(profile.host, profile.port)
            val keyText = repo.privateKey(profile)
            if (keyText.isNotBlank()) {
                val passphrase = repo.passphrase(profile)
                ssh.authPublickey(profile.username, ssh.loadKeys(keyText, null, object : PasswordFinder {
                    override fun reqPassword(resource: Resource<*>): CharArray = passphrase.toCharArray()
                    override fun shouldRetry(resource: Resource<*>): Boolean = false
                }))
            } else ssh.authPassword(profile.username, repo.password(profile))
            val script = if (stop) """
                runtime="${'$'}{XDG_RUNTIME_DIR:-/run/user/${'$'}(id -u)}"
                tmux -S "${'$'}runtime/desklink-tmux.sock" kill-session -t '=$sessionName' 2>/dev/null || true
            """.trimIndent() else """
                set -eu
                runtime="${'$'}{XDG_RUNTIME_DIR:-/run/user/${'$'}(id -u)}"
                display="${'$'}(systemctl --user show-environment 2>/dev/null | sed -n 's/^WAYLAND_DISPLAY=//p' | head -n 1)"
                if [ -z "${'$'}display" ]; then
                  for path in "${'$'}runtime"/wayland-[0-9]*; do
                    if [ -S "${'$'}path" ]; then display="${'$'}{path##*/}"; break; fi
                  done
                fi
                [ -n "${'$'}display" ] || { echo 'No Wayland display found' >&2; exit 1; }
                export XDG_RUNTIME_DIR="${'$'}runtime" WAYLAND_DISPLAY="${'$'}display"
                tmux -S "${'$'}runtime/desklink-tmux.sock" new-session -d -s $sessionName -e "XDG_RUNTIME_DIR=${'$'}runtime" -e "WAYLAND_DISPLAY=${'$'}display" "exec wayvnc --exit-on-disconnect $address ${profile.vncPort}"
                sleep 1
                tmux -S "${'$'}runtime/desklink-tmux.sock" has-session -t '=$sessionName' 2>/dev/null || { echo 'wayvnc exited immediately; check its configuration and listen address' >&2; exit 1; }
            """.trimIndent()
            val quoted = "'" + script.replace("'", "'\\''") + "'"
            ssh.startSession().use { session ->
                val command = session.exec("sh -c $quoted")
                command.join(15, TimeUnit.SECONDS)
                val exit = command.exitStatus ?: throw IOException("Timed out while starting wayvnc")
                if (exit != 0) throw IOException(command.errorStream.bufferedReader().readText().take(1000).ifBlank { "wayvnc startup failed" })
            }
        }
    }
}
