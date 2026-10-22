package com.photocleanse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.photocleanse.review.listFolders
import com.photocleanse.review.validFolderName
import com.photocleanse.scan.Volumes
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal in-app folder browser (the app already has all-files access, so plain paths work). */
@Composable
fun FolderPickerDialog(initial: String?, showHidden: Boolean, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val roots = remember { Volumes.mountedRoots(ctx) }
    var dir by remember { mutableStateOf(initial?.let(::File)?.takeIf { it.isDirectory }) }
    var version by remember { mutableIntStateOf(0) }
    var showNew by remember { mutableStateOf(false) }

    val current = dir
    // Reading a folder on shared storage can take a moment: do it off the main thread so a tap shows its result
    // (the new path and "Loading…") at once instead of looking ignored.
    val loaded by produceState<Pair<File?, List<Pair<String, String>>>?>(null, current, version) {
        value = null
        val list = withContext(Dispatchers.IO) {
            if (current == null) roots.map { it.path to it.path }
            else listFolders(current, showHidden, checkChildren = false).map { it.path to it.name }
        }
        value = current to list
    }
    val entries = loaded?.takeIf { it.first == current }?.second

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose photos folder") },
        text = {
            Column {
                Text(current?.path ?: "Pick a storage", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
                    if (current != null) {
                        item {
                            Text(
                                "↑ ..", Modifier.fillMaxWidth().clickable {
                                    dir = if (roots.any { it.path == current.path }) null else current.parentFile
                                }.padding(vertical = 12.dp),
                            )
                        }
                    }
                    if (entries == null) item { Text("Loading…", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall) }
                    else if (entries.isEmpty()) item { Text("No sub-folders", Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.bodySmall) }
                    items(entries ?: emptyList(), key = { it.first }) { (path, label) ->
                        Text(label, Modifier.fillMaxWidth().clickable { dir = File(path) }.padding(vertical = 12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { current?.let { onPick(it.path) } }, enabled = current != null) { Text("Use this folder") } },
        dismissButton = {
            Column {
                if (current != null) TextButton(onClick = { showNew = true }) { Text("＋ New folder") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )

    if (showNew && current != null) {
        NewFolderDialog(
            parent = current.name.ifEmpty { current.path },
            onCreate = { name ->
                val ok = validFolderName(name) && File(current, name.trim()).let { it.isDirectory || it.mkdirs() }
                if (ok) { version++; showNew = false }
                ok
            },
            onDismiss = { showNew = false },
        )
    }
}
