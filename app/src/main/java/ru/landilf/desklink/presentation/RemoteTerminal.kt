package ru.landilf.desklink.presentation

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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import ru.landilf.desklink.R
import ru.landilf.desklink.domain.model.SshProfile
import ru.landilf.desklink.presentation.components.DiagnosticsDialog
import ru.landilf.desklink.presentation.desktop.DesktopScreen
import ru.landilf.desklink.presentation.components.StatusIndicator
import ru.landilf.desklink.presentation.components.CheckStatus
import ru.landilf.desklink.presentation.components.TerminalKeysStore
import ru.landilf.desklink.presentation.components.TerminalKeysDialog
import ru.landilf.desklink.presentation.components.defaultKeyRows
import ru.landilf.desklink.presentation.components.keyLabel
import kotlinx.coroutines.launch
import ru.landilf.desklink.presentation.terminal.TerminalCanvasView
import ru.landilf.desklink.presentation.terminal.TerminalState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteTerminal(vm: TerminalViewModel) {
    val state by vm.state.collectAsState()
    var desktopProfile by remember { mutableStateOf<SshProfile?>(null) }
    desktopProfile?.let { profile -> DesktopScreen(profile, vm::startWayvnc, vm::stopWayvnc, vm::vncPassword) { desktopProfile = null }; return }
    var dialog by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<SshProfile?>(null) }
    var name by remember { mutableStateOf("") };
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("22") };
    var user by remember { mutableStateOf("") }
    var vncHost by remember { mutableStateOf("") }
    var vncPort by remember { mutableStateOf("5900") }
    var vncUsername by remember { mutableStateOf("") }
    var vncFingerprint by remember { mutableStateOf("") }
    var vncPassword by remember { mutableStateOf("") }
    var clearVncPassword by remember { mutableStateOf(false) }
    var profileTab by remember { mutableStateOf(0) }
    var password by remember { mutableStateOf("") };
    var tmux by remember { mutableStateOf(false) }
    var keyText by remember { mutableStateOf("") };
    var keyPass by remember { mutableStateOf("") }
    var terminalView by remember { mutableStateOf<TerminalCanvasView?>(null) }
    var bracketedPaste by remember { mutableStateOf(false) }
    var tmuxName by remember { mutableStateOf("codex") }
    var draft by remember { mutableStateOf("") };
    var showDraft by remember { mutableStateOf(false) }
    var ctrl by remember { mutableStateOf(false) };
    var alt by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf(false) }
    var showDiagnostics by remember { mutableStateOf(false) }
    var showKeySettings by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val keyStore = remember { TerminalKeysStore(context.applicationContext) }
    val keyRows by keyStore.rows.collectAsState(initial = defaultKeyRows)
    val uiScope = rememberCoroutineScope()
    LaunchedEffect(state.connected) {
        if (!state.connected) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text(state.active?.name ?: stringResource(R.string.terminal_title), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            actions = {
                IconButton(onClick = { showDiagnostics = true }) {
                    Icon(painterResource(R.drawable.ic_network), contentDescription = stringResource(R.string.network))
                }
                if (state.connected) IconButton(onClick = { showKeySettings = true }) {
                    Icon(painterResource(R.drawable.ic_keyboard), contentDescription = stringResource(R.string.key_settings))
                }
                StatusIndicator(if (state.connected) CheckStatus.YES else CheckStatus.NO,
                    stringResource(if (state.connected) R.string.online else R.string.offline))
                if (state.connected) IconButton(onClick = {
                    keyboard?.hide()
                    focusManager.clearFocus()
                    vm.disconnect()
                }) {
                    Icon(painterResource(R.drawable.ic_disconnect), contentDescription = stringResource(R.string.disconnect))
                }
            })
    }) { padding ->
        Column(Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(12.dp)) {
            if (!state.connected || state.terminal == null) {
                Row {
                    Text(
                        stringResource(R.string.connections),
                        style = MaterialTheme.typography.titleLarge
                    ); Spacer(Modifier.weight(1f)); Button(onClick = {
                    editingId = null; name = ""; host = ""; port = "22"; user = ""; password =
                    ""; keyText = ""; keyPass = ""; tmux = false; tmuxName = "codex";
                    vncHost = ""; vncPort = "5900"; vncUsername = ""; vncFingerprint = ""; vncPassword = ""; clearVncPassword = false; profileTab = 0; dialog = true
                }) { Text(stringResource(R.string.add)) }
                }
                Text(state.status, Modifier.padding(vertical = 8.dp))
                LazyColumn {
                    items(state.profiles, key = { it.id }) { profile ->
                        Card(Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(
                                    profile.name,
                                    style = MaterialTheme.typography.titleMedium
                                ); Text("${profile.username}@${profile.host}:${profile.port}")
                                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                    Button(onClick = { vm.connect(profile) }) {
                                        Text(
                                            stringResource(
                                                R.string.connect
                                            )
                                        )
                                    }
                                    IconButton(onClick = {
                                        editingId = profile.id; name = profile.name; host =
                                        profile.host; port = profile.port.toString(); user =
                                        profile.username
                                        tmux = profile.tmux; tmuxName = profile.tmuxName; password =
                                        ""; keyText = ""; keyPass = ""
                                        vncHost = profile.vncHost; vncPort = profile.vncPort.toString()
                                        vncUsername = profile.vncUsername; vncFingerprint = profile.vncFingerprint
                                        vncPassword = ""; clearVncPassword = false
                                        profileTab = 0; dialog = true
                                    }) {
                                        Icon(
                                            painterResource(R.drawable.ic_edit),
                                            contentDescription = stringResource(R.string.edit_connection)
                                        )
                                    }
                                    IconButton(onClick = { deleteTarget = profile }) {
                                        Icon(
                                            painterResource(R.drawable.ic_delete),
                                            contentDescription = stringResource(R.string.delete_connection)
                                        )
                                    }
                                    IconButton(onClick = { desktopProfile = profile }) {
                                        Icon(painterResource(R.drawable.ic_desktop), contentDescription = stringResource(R.string.desktop_open))
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                val terminal = state.terminal ?: return@Column
                TerminalSurface(terminal, Modifier
                    .weight(1f)
                    .fillMaxWidth(), vm::resize, { chunk ->
                    if (shift && (chunk == "\r" || chunk == "\n")) {
                        vm.write("\u001b[13;2u"); shift = false
                    } else if (ctrl && chunk.length == 1) {
                        vm.write((chunk[0].uppercaseChar().code and 31).toChar().toString()); ctrl =
                            false
                    } else if (alt && chunk.isNotEmpty()) {
                        vm.write("\u001b$chunk"); alt = false
                    } else if (shift && chunk.isNotEmpty()) {
                        vm.write(chunk.uppercase()); shift = false
                    } else vm.write(chunk)
                }) { terminalView = it }
                val sequences = mapOf(
                    "Esc" to "\u001b", "Tab" to "\t", "↑" to "\u001b[A", "↓" to "\u001b[B",
                    "←" to "\u001b[D", "→" to "\u001b[C", "Home" to "\u001b[H", "End" to "\u001b[F",
                    "PgUp" to "\u001b[5~", "PgDn" to "\u001b[6~", "Enter" to "\r",
                    "Ctrl+C" to "\u0003", "Ctrl+D" to "\u0004", "Ctrl+L" to "\u000c", "Ctrl+Z" to "\u001a",
                    "TmuxSessions" to "\u0002s", "TmuxWindows" to "\u0002w"
                )
                keyRows.forEach { row ->
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        row.filter { state.active?.tmux == true || (it != "TmuxSessions" && it != "TmuxWindows") }.forEach { key ->
                            val label = keyLabel(key)
                            TextButton(onClick = {
                                when (key) {
                                    "Draft" -> showDraft = true
                                    "Ctrl" -> ctrl = !ctrl
                                    "Alt" -> alt = !alt
                                    "Shift" -> shift = !shift
                                    else -> {
                                        val seq = sequences[key].orEmpty()
                                        val payload = if (shift && key == "Enter") {
                                            shift = false
                                            "\u001b[13;2u"
                                        } else if (ctrl) {
                                            ctrl = false
                                            when (key) {
                                                "↑" -> "\u001b[1;5A"; "↓" -> "\u001b[1;5B"
                                                "→" -> "\u001b[1;5C"; "←" -> "\u001b[1;5D"
                                                else -> seq
                                            }
                                        } else if (alt) { alt = false; "\u001b$seq" } else if (shift && key == "Tab") { shift = false; "\u001b[Z" } else { shift = false; seq }
                                        vm.write(payload)
                                        terminalView?.resetInput()
                                    }
                                }
                            }) { Text(if ((key == "Ctrl" && ctrl) || (key == "Alt" && alt) || (key == "Shift" && shift)) "$label●" else label) }
                        }
                    }
                }
            }
        }
    }
    if (showKeySettings) TerminalKeysDialog(keyRows, { rows -> uiScope.launch { keyStore.save(rows) } }) { showKeySettings = false }
    if (showDiagnostics) DiagnosticsDialog(
        state.profiles,
        state.active,
        state.connected
    ) { showDiagnostics = false }
    if (dialog) AlertDialog(
        onDismissRequest = { dialog = false },
        title = {
            Text(
                if (editingId == null) stringResource(R.string.new_connection) else stringResource(R.string.edit_connection)
            )
        },
        text = {
            Column(Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    name,
                    { name = it },
                    label = { Text(stringResource(R.string.name)) })
                androidx.compose.material3.TabRow(selectedTabIndex = profileTab) {
                    androidx.compose.material3.Tab(selected = profileTab == 0, onClick = { profileTab = 0 }, text = { Text("SSH") })
                    androidx.compose.material3.Tab(selected = profileTab == 1, onClick = { profileTab = 1 }, text = { Text("VNC") })
                }
                if (profileTab == 0) {
                OutlinedTextField(
                host,
                { host = it },
                label = { Text(stringResource(R.string.host)) }); OutlinedTextField(
                port,
                { port = it },
                label = { Text(stringResource(R.string.port)) }); OutlinedTextField(
                user,
                { user = it },
                label = { Text(stringResource(R.string.username)) }); OutlinedTextField(
                password,
                { password = it },
                label = { Text(stringResource(R.string.password)) })
                OutlinedTextField(
                    keyText,
                    { keyText = it },
                    label = { Text(stringResource(R.string.private_key)) },
                    minLines = 3
                )
                OutlinedTextField(
                    keyPass,
                    { keyPass = it },
                    label = { Text(stringResource(R.string.key_passphrase)) })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(tmux, { tmux = it })
                    Text(stringResource(R.string.connect_tmux))
                }
                if (tmux) OutlinedTextField(
                    tmuxName,
                    { tmuxName = it },
                    label = { Text(stringResource(R.string.tmux_name)) })
                } else {
                    OutlinedTextField(vncHost, { vncHost = it }, label = { Text(stringResource(R.string.vnc_host)) }, singleLine = true)
                    OutlinedTextField(vncPort, { vncPort = it }, label = { Text(stringResource(R.string.port)) }, singleLine = true)
                    OutlinedTextField(vncUsername, { vncUsername = it }, label = { Text(stringResource(R.string.vnc_username)) }, singleLine = true)
                    OutlinedTextField(vncFingerprint, { vncFingerprint = it }, label = { Text(stringResource(R.string.desktop_fingerprint)) }, minLines = 2)
                    OutlinedTextField(vncPassword, { vncPassword = it; if (it.isNotEmpty()) clearVncPassword = false }, label = { Text(stringResource(R.string.vnc_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                    Text(stringResource(R.string.vnc_password_notice), style = MaterialTheme.typography.bodySmall)
                    if (editingId != null) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(clearVncPassword, { clearVncPassword = it; if (it) vncPassword = "" })
                        Text(stringResource(R.string.vnc_password_delete))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val p = SshProfile(
                    id = editingId.orEmpty(),
                    name = name.ifBlank { host },
                    host = host,
                    port = port.toIntOrNull() ?: 22,
                    username = user,
                    tmux = tmux,
                    tmuxName = tmuxName,
                    vncHost = vncHost.trim(),
                    vncPort = vncPort.toIntOrNull() ?: 5900,
                    vncUsername = vncUsername.trim(),
                    vncFingerprint = vncFingerprint.trim()
                ); vm.save(p, password, keyText, keyPass, vncPassword, clearVncPassword); vncPassword = ""; dialog = false
            }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = {
                dialog = false
            }) { Text(stringResource(R.string.cancel)) }
        })
    if (deleteTarget != null) AlertDialog(
        onDismissRequest = { deleteTarget = null },
        title = { Text(stringResource(R.string.delete_confirm)) },
        text = { Text(deleteTarget!!.name) },
        confirmButton = {
            TextButton(onClick = { vm.remove(deleteTarget!!); deleteTarget = null }) {
                Text(
                    stringResource(R.string.delete)
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                deleteTarget = null
            }) { Text(stringResource(R.string.cancel)) }
        })
    if (state.hostPrompt != null) AlertDialog(
        onDismissRequest = { vm.trust(false) },
        title = { Text(state.status) },
        text = { Text(state.hostPrompt!!) },
        confirmButton = { TextButton(onClick = { vm.trust(true) }) { Text(stringResource(R.string.trust_key)) } },
        dismissButton = { TextButton(onClick = { vm.trust(false) }) { Text(stringResource(R.string.reject)) } })
    if (showDraft) AlertDialog(
        onDismissRequest = { showDraft = false },
        title = { Text(stringResource(R.string.terminal_text)) },
        text = {
            Column {
                OutlinedTextField(
                    draft,
                    { draft = it },
                    modifier = Modifier.height(220.dp),
                    label = { Text(stringResource(R.string.multiline_text)) })
                Row {
                    Checkbox(
                        bracketedPaste,
                        { bracketedPaste = it }); Text(stringResource(R.string.bracketed_paste))
                }
            }
        },
        confirmButton = {
            val paste = if (bracketedPaste) "\u001b[200~$draft\u001b[201~" else draft
            TextButton(onClick = {
                vm.write(paste); showDraft = false
            }) { Text(stringResource(R.string.paste_no_enter)) }
            TextButton(onClick = { vm.write(paste + "\r"); showDraft = false }) {
                Text(
                    stringResource(R.string.paste_enter)
                )
            }
        })
}

@Composable
private fun TerminalSurface(
    state: TerminalState,
    modifier: Modifier,
    resize: (Int, Int) -> Unit,
    input: (String) -> Unit,
    onView: (TerminalCanvasView) -> Unit
) {
    val context = LocalContext.current
    val version by state.updates.collectAsState()
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { TerminalCanvasView(context, state, resize, input).also(onView) },
        update = { it.sendInput = input; it.invalidate(); version.hashCode() })
}
