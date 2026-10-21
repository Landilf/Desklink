package ru.landilf.shellink

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

data class SshProfile(val id: String = "", val name: String, val host: String, val port: Int, val username: String,
                      val tmux: Boolean = false, val tmuxName: String = "codex")

class TerminalState {
    val emulator = TerminalEmulator(object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) {}
        override fun titleChanged(oldTitle: String?, newTitle: String?) {}
        override fun onCopyTextToClipboard(text: String?) {}
        override fun onPasteTextFromClipboard() {}
        override fun onBell() {}
        override fun onColorsChanged() {}
    }, 80, 24, 10000, object : TerminalSessionClient {
        override fun onTextChanged(changedSession: TerminalSession) {}
        override fun onTitleChanged(changedSession: TerminalSession) {}
        override fun onSessionFinished(finishedSession: TerminalSession) {}
        override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {}
        override fun onPasteTextFromClipboard(session: TerminalSession?) {}
        override fun onBell(session: TerminalSession) {}
        override fun onColorsChanged(session: TerminalSession) {}
        override fun onTerminalCursorStateChange(state: Boolean) {}
        override fun getTerminalCursorStyle(): Int = 0
        override fun logError(tag: String, message: String) {}
        override fun logWarn(tag: String, message: String) {}
        override fun logInfo(tag: String, message: String) {}
        override fun logDebug(tag: String, message: String) {}
        override fun logVerbose(tag: String, message: String) {}
        override fun logStackTraceWithMessage(tag: String, message: String, e: Exception) {}
        override fun logStackTrace(tag: String, e: Exception) {}
    })
    val updates = MutableStateFlow(0L)
    fun append(bytes: ByteArray, count: Int) { emulator.append(bytes, count); updates.value++ }
}

data class UiState(val profiles: List<SshProfile> = emptyList(), val active: SshProfile? = null,
                   val connected: Boolean = false, val status: String = "Добавьте подключение", val terminal: TerminalState? = null,
                   val hostPrompt: String? = null)

class TerminalViewModel(private val repo: ProfileStore) : ViewModel() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()
    private var client: SSHClient? = null
    private var session: Session? = null
    private var shell: Session.Shell? = null

    init { scope.launch { repo.profiles.collect { _state.value = _state.value.copy(profiles = it) } } }

    fun save(profile: SshProfile, password: String, key: String = "", passphrase: String = "") = scope.launch {
        repo.save(profile, password, key, passphrase)
    }
    fun remove(profile: SshProfile) = scope.launch { repo.remove(profile) }
    fun connect(profile: SshProfile) {
        _state.value = _state.value.copy(active = profile, status = "Подключение…", hostPrompt = null)
        scope.launch {
            try {
                val credential = repo.password(profile)
                val privateKeyText = repo.privateKey(profile)
                val passphrase = repo.passphrase(profile)
                val ssh = SSHClient()
                ssh.connectTimeout = 12000
                ssh.timeout = 0
                ssh.addHostKeyVerifier(object : HostKeyVerifier {
                    override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
                    override fun verify(hostname: String, port: Int, hostKey: java.security.PublicKey): Boolean = runBlocking {
                    val sshWireKey = net.schmizz.sshj.common.Buffer.PlainBuffer().putPublicKey(hostKey).compactData
                    val fp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(sshWireKey))
                    val known = repo.fingerprint(hostname, port)
                    when {
                        known == fp -> true
                        known != null -> {
                            val answer = CompletableDeferred<Boolean>()
                            pendingTrust = answer
                            withContext(Dispatchers.Main) { _state.value = _state.value.copy(status = "Ключ сервера изменился — проверьте", hostPrompt = "Ожидался $known, получен $fp") }
                            answer.await().also { if (it) repo.saveFingerprint(hostname, port, fp) }
                        }
                        else -> {
                            val answer = CompletableDeferred<Boolean>()
                            pendingTrust = answer
                            withContext(Dispatchers.Main) { _state.value = _state.value.copy(hostPrompt = "$hostname:$port\n$fp", status = "Новый ключ SSH") }
                            answer.await().also { if (it) repo.saveFingerprint(hostname, port, fp) }
                        }
                    }
                    }
                })
                ssh.connect(profile.host, profile.port)
                if (privateKeyText.isNotBlank()) {
                    val keyProvider = ssh.loadKeys(privateKeyText, null, object : PasswordFinder {
                        override fun reqPassword(resource: net.schmizz.sshj.userauth.password.Resource<*>): CharArray = passphrase.toCharArray()
                        override fun shouldRetry(resource: net.schmizz.sshj.userauth.password.Resource<*>): Boolean = false
                    })
                    ssh.authPublickey(profile.username, keyProvider)
                } else ssh.authPassword(profile.username, credential)
                val sess = ssh.startSession()
                sess.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
                val sh = sess.startShell()
                client = ssh; session = sess; shell = sh
                _state.value = _state.value.copy(connected = true, terminal = TerminalState(), status = "Подключено")
                if (profile.tmux) write("tmux new-session -A -s ${profile.tmuxName.replace(Regex("[^A-Za-z0-9_-]"), "")}\r")
                val out = sh.inputStream
                val buffer = ByteArray(8192)
                while (isActive) {
                    val n = withContext(Dispatchers.IO) { out.read(buffer) }
                    if (n < 0) break
                    val chunk = buffer.copyOf(n)
                    withContext(Dispatchers.Main) { _state.value.terminal?.append(chunk, n) }
                }
                closeSshResources()
                _state.value = _state.value.copy(connected = false, status = "Соединение закрыто")
            } catch (t: Throwable) {
                disconnect()
                _state.value = _state.value.copy(connected = false, status = t.message ?: "Ошибка подключения")
            }
        }
    }
    private var pendingTrust: CompletableDeferred<Boolean>? = null
    fun trust(allow: Boolean) { pendingTrust?.complete(allow); pendingTrust = null; _state.value = _state.value.copy(hostPrompt = null) }
    fun write(text: String) { scope.launch(Dispatchers.IO) { try { shell?.let { it.outputStream.write(text.toByteArray(Charsets.UTF_8)); it.outputStream.flush() } } catch (_: IOException) {} } }
    fun resize(cols: Int, rows: Int) { scope.launch(Dispatchers.IO) { runCatching { shell?.changeWindowDimensions(cols, rows, 0, 0) } } }
    fun disconnect() {
        val oldShell = shell; val oldSession = session; val oldClient = client
        shell = null; session = null; client = null
        CoroutineScope(Dispatchers.IO).launch { closeResources(oldShell, oldSession, oldClient) }
        _state.value = _state.value.copy(connected = false, status = "Отключено", terminal = null)
    }
    private fun closeSshResources() {
        val oldShell = shell; val oldSession = session; val oldClient = client
        shell = null; session = null; client = null
        closeResources(oldShell, oldSession, oldClient)
    }
    private fun closeResources(oldShell: Session.Shell?, oldSession: Session?, oldClient: SSHClient?) {
        runCatching { oldShell?.close() }; runCatching { oldSession?.close() }; runCatching { oldClient?.disconnect() }
    }
    override fun onCleared() { disconnect(); scope.cancel() }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState)
        val store = ProfileStore(applicationContext)
        setContent { MaterialTheme(colorScheme = darkColorScheme()) {
            val vm: TerminalViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T = TerminalViewModel(store) as T
            })
            RemoteTerminal(vm)
        } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun RemoteTerminal(vm: TerminalViewModel) {
    val state by vm.state.collectAsState()
    var dialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<SshProfile?>(null) }
    var name by remember { mutableStateOf("") }; var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") }; var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }; var tmux by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") }; var keyPass by remember { mutableStateOf("") }
    var terminalInput by remember { mutableStateOf("") }
    var bracketedPaste by remember { mutableStateOf(false) }
    var tmuxName by remember { mutableStateOf("codex") }
    var draft by remember { mutableStateOf("") }; var showDraft by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) }; var alt by remember { mutableStateOf(false) }
    Scaffold(topBar = { TopAppBar(title = { Text(state.active?.name ?: "Remote Terminal") }, actions = {
        Text(if (state.connected) "● онлайн" else "○ офлайн")
        if (state.connected) TextButton(onClick = vm::disconnect) { Text("Отключить") }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp)) {
            if (!state.connected || state.terminal == null) {
                Row { Text("Подключения", style = MaterialTheme.typography.titleLarge); Spacer(Modifier.weight(1f)); Button(onClick = {
                    editingId = null; name = ""; host = ""; port = "22"; user = ""; password = ""; keyText = ""; keyPass = ""; tmux = false; tmuxName = "codex"; dialog = true
                }) { Text("＋ Добавить") } }
                Text(state.status, Modifier.padding(vertical = 8.dp))
                LazyColumn { items(state.profiles, key = { it.id }) { profile ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Column(Modifier.padding(12.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium); Text("${profile.username}@${profile.host}:${profile.port}")
                        Row {
                            Button(onClick = { vm.connect(profile) }) { Text("Подключиться") }
                            TextButton(onClick = {
                                editingId = profile.id; name = profile.name; host = profile.host; port = profile.port.toString(); user = profile.username
                                tmux = profile.tmux; tmuxName = profile.tmuxName; password = ""; keyText = ""; keyPass = ""; dialog = true
                            }) { Text("Изменить") }
                            TextButton(onClick = { deleteTarget = profile }) { Text("Удалить") }
                        }
                    } }
                } }
            } else {
                val terminal = state.terminal ?: return@Column
                TerminalSurface(terminal, Modifier.weight(1f).fillMaxWidth(), vm::resize)
                OutlinedTextField(value = terminalInput, onValueChange = { next ->
                    if (next.length >= terminalInput.length && next.startsWith(terminalInput)) {
                        val chunk = next.substring(terminalInput.length)
                        if (ctrl && chunk.length == 1) { vm.write((chunk[0].uppercaseChar().code and 31).toChar().toString()); ctrl = false }
                        else if (alt && chunk.isNotEmpty()) { vm.write("\u001b$chunk"); alt = false }
                        else vm.write(chunk)
                    }
                    else if (next.length < terminalInput.length) repeat(terminalInput.length - next.length) { vm.write("\u007f") }
                    terminalInput = next
                }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Ввод в терминал") })
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("Esc" to "\u001b", "Tab" to "\t", "Ctrl" to "", "Alt" to "", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "Home" to "\u001b[H", "End" to "\u001b[F", "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~", "Enter" to "\r").forEach { (label, seq) ->
                        TextButton(onClick = { when (label) {
                            "Ctrl" -> ctrl = !ctrl
                            "Alt" -> alt = !alt
                            else -> {
                                val payload = if (ctrl) {
                                ctrl = false
                                when (label) { "↑" -> "\u001b[1;5A"; "↓" -> "\u001b[1;5B"; "→" -> "\u001b[1;5C"; "←" -> "\u001b[1;5D"; else -> seq }
                                } else if (alt) { alt = false; "\u001b$seq" } else seq
                                vm.write(payload)
                                if (label == "Enter") terminalInput = ""
                            }
                        } }) { Text(if (label == "Ctrl" && ctrl) "Ctrl●" else if (label == "Alt" && alt) "Alt●" else label) }
                    }
                }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { showDraft = true }) { Text("Длинный текст") }
                    listOf("Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Ctrl+L" to "\u000c", "Ctrl+Z" to "\u001a").forEach { (label, data) -> TextButton(onClick = { vm.write(data) }) { Text(label) } }
                }
            }
        }
    }
    if (dialog) AlertDialog(onDismissRequest = { dialog = false }, title = { Text(if (editingId == null) "Новое SSH-подключение" else "Изменить подключение") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            OutlinedTextField(name, { name = it }, label = { Text("Название") }); OutlinedTextField(host, { host = it }, label = { Text("Хост") }); OutlinedTextField(port, { port = it }, label = { Text("Порт") }); OutlinedTextField(user, { user = it }, label = { Text("Пользователь") }); OutlinedTextField(password, { password = it }, label = { Text("Пароль") })
            OutlinedTextField(keyText, { keyText = it }, label = { Text("Приватный SSH-ключ (OpenSSH/PEM)") }, minLines = 3)
            OutlinedTextField(keyPass, { keyPass = it }, label = { Text("Парольная фраза ключа") })
            Row { Checkbox(tmux, { tmux = it }); Text("Подключаться к tmux") }
            if (tmux) OutlinedTextField(tmuxName, { tmuxName = it }, label = { Text("Имя tmux-сессии") })
        }
    }, confirmButton = { TextButton(onClick = { val p = SshProfile(id = editingId.orEmpty(), name = name.ifBlank { host }, host = host, port = port.toIntOrNull() ?: 22, username = user, tmux = tmux, tmuxName = tmuxName); vm.save(p, password, keyText, keyPass); dialog = false }) { Text("Сохранить") } }, dismissButton = { TextButton(onClick = { dialog = false }) { Text("Отмена") } })
    if (deleteTarget != null) AlertDialog(onDismissRequest = { deleteTarget = null }, title = { Text("Удалить подключение?") }, text = { Text(deleteTarget!!.name) }, confirmButton = {
        TextButton(onClick = { vm.remove(deleteTarget!!); deleteTarget = null }) { Text("Удалить") }
    }, dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } })
    if (state.hostPrompt != null) AlertDialog(onDismissRequest = { vm.trust(false) }, title = { Text(state.status) }, text = { Text(state.hostPrompt!!) }, confirmButton = { TextButton(onClick = { vm.trust(true) }) { Text("Доверять ключу") } }, dismissButton = { TextButton(onClick = { vm.trust(false) }) { Text("Отклонить") } })
    if (showDraft) AlertDialog(onDismissRequest = { showDraft = false }, title = { Text("Текст для терминала") }, text = { Column {
        OutlinedTextField(draft, { draft = it }, modifier = Modifier.height(220.dp), label = { Text("Многострочный текст") })
        Row { Checkbox(bracketedPaste, { bracketedPaste = it }); Text("Использовать bracketed paste") }
    } }, confirmButton = {
        val paste = if (bracketedPaste) "\u001b[200~$draft\u001b[201~" else draft
        TextButton(onClick = { vm.write(paste); showDraft = false }) { Text("Вставить без Enter") }
        TextButton(onClick = { vm.write(paste + "\r"); showDraft = false }) { Text("Вставить и Enter") }
    })
}

@Composable private fun TerminalSurface(state: TerminalState, modifier: Modifier, resize: (Int, Int) -> Unit) {
    val context = LocalContext.current
    val version by state.updates.collectAsState()
    androidx.compose.ui.viewinterop.AndroidView(modifier = modifier, factory = { TerminalCanvasView(context, state, resize) }, update = { it.invalidate(); version.hashCode() })
}
