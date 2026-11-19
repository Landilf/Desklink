package ru.landilf.desklink.presentation.components

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map
import ru.landilf.desklink.R

private val Context.keyDataStore by preferencesDataStore(name = "terminal_keys")
private val firstKey = stringPreferencesKey("first_row")
private val secondKey = stringPreferencesKey("second_row")
val defaultKeyRows = listOf(
    listOf("Esc", "Tab", "Ctrl", "Alt", "Shift", "↑", "↓", "←", "→", "Home", "End", "PgUp", "PgDn", "Enter"),
    listOf("Draft", "TmuxSessions", "TmuxWindows", "Ctrl+C", "Ctrl+D", "Ctrl+L", "Ctrl+Z")
)

class TerminalKeysStore(private val context: Context) {
    val rows = context.keyDataStore.data.map { prefs ->
        val valid = defaultKeyRows.flatten().toSet()
        val stored = listOf(prefs[firstKey], prefs[secondKey])
        if (stored.any { it == null }) defaultKeyRows else {
            val seen = mutableSetOf<String>()
            val parsed = stored.map { row ->
                row!!.split(',').filter { it in valid && it != "Send" && seen.add(it) }.toMutableList()
            }
            defaultKeyRows.forEachIndexed { row, keys ->
                keys.filterNot { it in seen }.forEach { parsed[row].add(it); seen.add(it) }
            }
            parsed
        }
    }
    suspend fun save(rows: List<List<String>>) {
        context.keyDataStore.edit { prefs ->
            prefs[firstKey] = rows[0].joinToString(",")
            prefs[secondKey] = rows[1].joinToString(",")
        }
    }
}

@Composable
fun keyLabel(key: String): String = when (key) {
    "Draft" -> stringResource(R.string.long_text)
    "TmuxSessions" -> stringResource(R.string.tmux_sessions)
    "TmuxWindows" -> stringResource(R.string.tmux_windows)
    else -> key
}

@Composable
fun TerminalKeysDialog(rows: List<List<String>>, save: (List<List<String>>) -> Unit, dismiss: () -> Unit) {
    var current by remember { mutableStateOf(rows.map { it.toList() }) }
    fun move(row: Int, index: Int, toRow: Int, toIndex: Int) {
        val updated = current.map { it.toMutableList() }
        val item = updated[row].removeAt(index)
        updated[toRow].add(toIndex.coerceIn(0, updated[toRow].size), item)
        current = updated
    }
    AlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.key_settings)) }, text = {
        Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
            current.forEachIndexed { row, keys ->
                Text(stringResource(if (row == 0) R.string.first_row else R.string.second_row), style = MaterialTheme.typography.titleSmall)
                keys.forEachIndexed { index, key ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(keyLabel(key), Modifier.weight(1f))
                        TextButton(onClick = { move(row, index, row, index - 1) }, enabled = index > 0) { Text("↑") }
                        TextButton(onClick = { move(row, index, row, index + 1) }, enabled = index < keys.lastIndex) { Text("↓") }
                        TextButton(onClick = { move(row, index, 1 - row, current[1 - row].size) }) { Text(if (row == 0) "⇩" else "⇧") }
                    }
                }
            }
            TextButton(onClick = { current = defaultKeyRows }) { Text(stringResource(R.string.restore_defaults)) }
        }
    }, confirmButton = { TextButton(onClick = { save(current); dismiss() }) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.cancel)) } })
}
