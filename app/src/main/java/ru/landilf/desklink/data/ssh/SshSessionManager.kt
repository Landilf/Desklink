package ru.landilf.desklink.data.ssh

import ru.landilf.desklink.R

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.password.PasswordFinder
import ru.landilf.desklink.domain.model.SshProfile
import ru.landilf.desklink.domain.repository.ConnectionRepository
import ru.landilf.desklink.presentation.UiState
import ru.landilf.desklink.presentation.terminal.TerminalState
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

class SshSessionManager(
    private val repo: ConnectionRepository,
    private val context: android.content.Context
) {
    suspend fun startWayvnc(profile: SshProfile, sessionName: String) = WayvncLauncher(repo).start(profile, sessionName)
    fun stopWayvnc(profile: SshProfile, sessionName: String) = CoroutineScope(Dispatchers.IO).launch {
        runCatching { WayvncLauncher(repo).stop(profile, sessionName) }
            .onFailure { _state.value = _state.value.copy(status = context.getString(R.string.desktop_stop_error, it.message.orEmpty())) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state =
        MutableStateFlow(UiState(status = context.getString(R.string.status_add_connection)))
    val state = _state.asStateFlow()
    private var client: SSHClient? = null
    private var session: Session? = null
    @Volatile
    private var shell: Session.Shell? = null
    private val outgoing = Channel<Pair<Session.Shell, ByteArray>>(Channel.UNLIMITED)

    init {
        scope.launch { repo.profiles.collect { _state.value = _state.value.copy(profiles = it) } }
        scope.launch {
            for ((target, bytes) in outgoing) {
                try {
                    target.outputStream.write(bytes); target.outputStream.flush()
                } catch (_: IOException) {
                }
            }
        }
    }

    fun save(profile: SshProfile, password: String, key: String = "", passphrase: String = "") =
        scope.launch {
            repo.save(profile, password, key, passphrase)
        }

    fun remove(profile: SshProfile) = scope.launch { repo.remove(profile) }
    fun connect(profile: SshProfile) {
        _state.value = _state.value.copy(
            active = profile,
            status = context.getString(R.string.status_connecting),
            hostPrompt = null
        )
        scope.launch {
            try {
                val credential = repo.password(profile)
                val privateKeyText = repo.privateKey(profile)
                val passphrase = repo.passphrase(profile)
                // Android's built-in BC provider is restricted and does not expose X25519.
                // SSHJ selects providers by name, so install the bundled full provider first.
                synchronized(net.schmizz.sshj.common.SecurityUtils::class.java) {
                    if (java.security.Security.getProvider("BC") !is org.bouncycastle.jce.provider.BouncyCastleProvider) {
                        java.security.Security.removeProvider("BC")
                        java.security.Security.insertProviderAt(
                            org.bouncycastle.jce.provider.BouncyCastleProvider(),
                            1
                        )
                    }
                }
                val ssh = SSHClient()
                ssh.connectTimeout = 12000
                ssh.timeout = 0
                ssh.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
                        emptyList()

                    override fun verify(
                        hostname: String,
                        port: Int,
                        hostKey: java.security.PublicKey
                    ): Boolean = runBlocking {
                        val sshWireKey = net.schmizz.sshj.common.Buffer.PlainBuffer()
                            .putPublicKey(hostKey).compactData
                        val fp = "SHA256:" + Base64.getEncoder().withoutPadding()
                            .encodeToString(MessageDigest.getInstance("SHA-256").digest(sshWireKey))
                        val known = repo.fingerprint(hostname, port)
                        when {
                            known == fp -> true
                            known != null -> {
                                val answer = CompletableDeferred<Boolean>()
                                pendingTrust = answer
                                withContext(Dispatchers.Main) {
                                    _state.value = _state.value.copy(
                                        status = context.getString(R.string.status_key_changed),
                                        hostPrompt = context.getString(
                                            R.string.host_key_changed,
                                            known,
                                            fp
                                        )
                                    )
                                }
                                answer.await()
                                    .also { if (it) repo.saveFingerprint(hostname, port, fp) }
                            }

                            else -> {
                                val answer = CompletableDeferred<Boolean>()
                                pendingTrust = answer
                                withContext(Dispatchers.Main) {
                                    _state.value = _state.value.copy(
                                        hostPrompt = "$hostname:$port\n$fp",
                                        status = context.getString(R.string.status_new_key)
                                    )
                                }
                                answer.await()
                                    .also { if (it) repo.saveFingerprint(hostname, port, fp) }
                            }
                        }
                    }
                })
                ssh.connect(profile.host, profile.port)
                if (privateKeyText.isNotBlank()) {
                    val keyProvider = ssh.loadKeys(privateKeyText, null, object : PasswordFinder {
                        override fun reqPassword(resource: net.schmizz.sshj.userauth.password.Resource<*>): CharArray =
                            passphrase.toCharArray()

                        override fun shouldRetry(resource: net.schmizz.sshj.userauth.password.Resource<*>): Boolean =
                            false
                    })
                    ssh.authPublickey(profile.username, keyProvider)
                } else ssh.authPassword(profile.username, credential)
                val sess = ssh.startSession()
                sess.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
                val sh = sess.startShell()
                client = ssh; session = sess; shell = sh
                _state.value = _state.value.copy(
                    connected = true,
                    terminal = TerminalState(),
                    status = context.getString(R.string.status_connected)
                )
                if (profile.tmux) write(
                    "tmux new-session -A -s ${
                        profile.tmuxName.replace(
                            Regex("[^A-Za-z0-9_-]"),
                            ""
                        )
                    }\r"
                )
                val out = sh.inputStream
                val buffer = ByteArray(8192)
                while (isActive) {
                    val n = withContext(Dispatchers.IO) { out.read(buffer) }
                    if (n < 0) break
                    val chunk = buffer.copyOf(n)
                    withContext(Dispatchers.Main) { _state.value.terminal?.append(chunk, n) }
                }
                closeSshResources()
                _state.value = _state.value.copy(
                    connected = false,
                    status = context.getString(R.string.status_closed)
                )
            } catch (t: Throwable) {
                disconnect()
                _state.value = _state.value.copy(
                    connected = false,
                    status = t.message ?: context.getString(R.string.status_error)
                )
            }
        }
    }

    private var pendingTrust: CompletableDeferred<Boolean>? = null
    fun trust(allow: Boolean) {
        pendingTrust?.complete(allow); pendingTrust = null; _state.value =
            _state.value.copy(hostPrompt = null)
    }

    fun write(text: String) {
        shell?.let { outgoing.trySend(it to text.toByteArray(Charsets.UTF_8)) }
    }

    fun resize(cols: Int, rows: Int) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                shell?.changeWindowDimensions(
                    cols,
                    rows,
                    0,
                    0
                )
            }
        }
    }

    fun disconnect() {
        val oldShell = shell;
        val oldSession = session;
        val oldClient = client
        shell = null; session = null; client = null
        CoroutineScope(Dispatchers.IO).launch { closeResources(oldShell, oldSession, oldClient) }
        _state.value = _state.value.copy(
            connected = false,
            status = context.getString(R.string.status_disconnected),
            terminal = null
        )
    }

    private fun closeSshResources() {
        val oldShell = shell;
        val oldSession = session;
        val oldClient = client
        shell = null; session = null; client = null
        closeResources(oldShell, oldSession, oldClient)
    }

    private fun closeResources(
        oldShell: Session.Shell?,
        oldSession: Session?,
        oldClient: SSHClient?
    ) {
        runCatching { oldShell?.close() }; runCatching { oldSession?.close() }; runCatching { oldClient?.disconnect() }
    }

    fun close() {
        disconnect(); outgoing.close(); scope.cancel()
    }
}

