package ru.landilf.desklink.presentation.desktop

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.content.Context
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.landilf.desklink.R
import ru.landilf.desklink.data.desktop.RfbClient
import ru.landilf.desklink.data.desktop.VeNCryptConnector
import ru.landilf.desklink.domain.model.SshProfile

@Composable
fun DesktopScreen(profile: SshProfile, startWayvnc: suspend (SshProfile, String) -> Unit, stopWayvnc: (SshProfile, String) -> Unit, back: () -> Unit) {
    val context = LocalContext.current
    val host = profile.vncHost.ifBlank { profile.host }
    val port = profile.vncPort
    val fingerprint = profile.vncFingerprint
    val username = profile.vncUsername.ifBlank { profile.username }
    var password by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var client by remember { mutableStateOf<RfbClient?>(null) }
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    var input by remember { mutableStateOf("") }
    var connecting by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(true) }
    var startError by remember { mutableStateOf("") }
    var startAttempt by remember { mutableIntStateOf(0) }
    val sessionName = remember { "desklink-vnc-" + java.util.UUID.randomUUID().toString().replace("-", "") }
    val disposed = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val stopRequested = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    fun requestStop() {
        if (stopRequested.compareAndSet(false, true)) stopWayvnc(profile, sessionName)
    }
    val scope = rememberCoroutineScope()
    LaunchedEffect(profile.id, startAttempt) {
        starting = true; startError = ""
        try {
            withContext(NonCancellable) { startWayvnc(profile, sessionName) }
        }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { startError = e.message.orEmpty().ifBlank { context.getString(R.string.desktop_start_error) } }
        finally { starting = false; if (disposed.get()) stopWayvnc(profile, sessionName) }
    }
    DisposableEffect(Unit) { onDispose {
        disposed.set(true)
        requestStop()
        runCatching { client?.close() }
    } }
    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row {
            TextButton(onClick = {
                requestStop()
                runCatching { client?.close() }
                client = null
                back()
            }) { Text(stringResource(R.string.close)) }
            Text(profile.name, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        if (starting) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.desktop_starting))
                }
            }
        } else if (startError.isNotBlank()) {
            Text(startError, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { startAttempt++ }) { Text(stringResource(R.string.desktop_retry)) }
        } else if (client == null) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.desktop_connect_title), style = MaterialTheme.typography.headlineSmall)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(profile.name, style = MaterialTheme.typography.titleMedium)
                        Text("$username@$host:$port", style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(if (fingerprint.isBlank()) R.string.vnc_fingerprint_missing else R.string.vnc_fingerprint_saved), style = MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(password, { password = it }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.vnc_password)) }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (status.isNotBlank()) Text(status, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Button(enabled = !connecting, onClick = {
                    val pin = fingerprint.filterNot { it == ':' || it.isWhitespace() }
                    if (!pin.matches(Regex("[0-9a-fA-F]{64}")) || port !in 1..65535 || host.isBlank()) {
                        status = context.getString(R.string.desktop_invalid); return@Button
                    }
                    status = context.getString(R.string.status_connecting); connecting = true
                    scope.launch {
                        try {
                            val connected = withContext(Dispatchers.IO) {
                                VeNCryptConnector.connect(host.trim(), port, pin.chunked(2).map { it.toInt(16).toByte() }.toByteArray(), username, password)
                            }
                            client = connected; password = ""; status = connected.desktopName
                            withContext(Dispatchers.IO) {
                                while (true) {
                                    if (!connected.readUpdate()) continue
                                    val fb = connected.framebuffer
                                    val bitmap = Bitmap.createBitmap(fb.pixels, fb.width, fb.height, Bitmap.Config.ARGB_8888)
                                    withContext(Dispatchers.Main) { frame = bitmap }
                                }
                            }
                        } catch (e: Exception) { status = e.message.orEmpty() }
                        finally { client?.close(); client = null; connecting = false }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.connect)) }
            }
        } else if (frame == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.desktop_wait_frame))
                }
            }
        } else {
            AndroidView(modifier = Modifier.weight(1f).fillMaxWidth(), factory = { DesktopView(it) }, update = { it.frame = frame; it.client = client; it.invalidate() })
            Row {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), label = { Text(stringResource(R.string.desktop_input)) })
                TextButton(onClick = {
                    val target = client
                    val text = input
                    scope.launch(Dispatchers.IO) {
                        text.codePoints().toArray().forEach { cp -> val key = if (cp <= 0xff) cp else 0x01000000 or cp; target?.key(key, true); target?.key(key, false) }
                    }
                    input = ""
                }) { Text(stringResource(R.string.desktop_send)) }
                TextButton(onClick = { val target = client; scope.launch(Dispatchers.IO) { target?.key(0xff0d, true); target?.key(0xff0d, false) } }) { Text("↵") }
            }
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf("Esc" to 0xff1b, "Tab" to 0xff09, "←" to 0xff51, "↑" to 0xff52,
                    "→" to 0xff53, "↓" to 0xff54, "Home" to 0xff50, "End" to 0xff57,
                    "PgUp" to 0xff55, "PgDn" to 0xff56, "Ctrl+C" to 3).forEach { (label, keysym) ->
                    TextButton(onClick = {
                        val target = client
                        scope.launch(Dispatchers.IO) {
                            if (keysym == 3) {
                                target?.key(0xffe3, true); target?.key('c'.code, true)
                                target?.key('c'.code, false); target?.key(0xffe3, false)
                            } else { target?.key(keysym, true); target?.key(keysym, false) }
                        }
                    }) { Text(label) }
                }
            }
        }
    }
}

private class DesktopView(context: Context) : View(context) {
    var frame: Bitmap? = null
    var client: RfbClient? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var zoom = 1f
    private var dx = 0f; private var dy = 0f
    private var lastX = 0f; private var lastY = 0f
    private var multiTouch = false
    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean { zoom = (zoom * detector.scaleFactor).coerceIn(1f, 5f); invalidate(); return true }
    })
    override fun onDraw(canvas: Canvas) {
        val bitmap = frame ?: return
        val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom
        canvas.save(); canvas.translate(dx, dy); canvas.scale(scale, scale); canvas.drawBitmap(bitmap, 0f, 0f, paint); canvas.restore()
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        val bitmap = frame ?: return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX = event.x; lastY = event.y; multiTouch = false }
            MotionEvent.ACTION_POINTER_DOWN -> { multiTouch = true }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount > 1) { dx += event.x - lastX; dy += event.y - lastY; lastX = event.x; lastY = event.y; invalidate() }
            MotionEvent.ACTION_UP -> {
                if (!multiTouch && !scaler.isInProgress && kotlin.math.abs(event.x - lastX) < 12 && kotlin.math.abs(event.y - lastY) < 12) {
                    val scale = minOf(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height) * zoom
                    val x = ((event.x - dx) / scale).toInt(); val y = ((event.y - dy) / scale).toInt()
                    val target = client
                    Thread { runCatching { target?.pointer(x, y, 1); target?.pointer(x, y, 0) } }.start()
                }
            }
        }
        return true
    }
}
