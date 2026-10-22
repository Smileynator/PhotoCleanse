package com.photocleanse.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.photocleanse.MainViewModel
import com.photocleanse.review.Decision
import com.photocleanse.review.MAX_BATCH_SIZE
import com.photocleanse.review.MIN_BATCH_SIZE
import kotlin.math.roundToInt
import java.io.File
import java.text.NumberFormat

internal val Green = Color(0xFF2E9E5B)
internal val Red = Color(0xFFD64545)

/** Same request is used for display and prefetch so the memory cache hits. */
internal fun fullRequest(ctx: Context, path: String) =
    ImageRequest.Builder(ctx).data(File(path)).size(2048).build()

internal fun thumbRequest(ctx: Context, path: String) =
    ImageRequest.Builder(ctx).data(File(path)).size(256).build()

internal fun fmt(n: Number) = NumberFormat.getIntegerInstance().format(n)

@Composable
fun StartScreen(vm: MainViewModel, onRescan: () -> Unit) {
    val counts by vm.counts.collectAsState()
    val scan by vm.scan.collectAsState()
    val analysis by vm.analysis.collectAsState()
    val dupGroups by vm.dupGroups.collectAsState()
    var confirmReset by remember { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }
    val toFile by vm.toFile.collectAsState()
    val available = if (vm.includeHidden) counts.remaining else counts.remaining - counts.hiddenRemaining

    Column(
        Modifier.fillMaxSize().systemBarsPaddingCompat().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Cleaner-Chan", style = MaterialTheme.typography.headlineLarge)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    ProgressRing(done = counts.done, remaining = counts.remaining)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("${fmt(counts.done)} cleaned", style = MaterialTheme.typography.titleLarge)
                        Text("${fmt(counts.remaining)} to go", style = MaterialTheme.typography.titleMedium)
                        Text("${fmt(counts.total)} photos indexed", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (scan.running) {
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        "Scanning… ${fmt(scan.entriesChecked)} files checked, ${fmt(scan.photosFound)} photos found",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Button(
                    onClick = vm::startBatch,
                    enabled = available > 0,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                ) { Text("Start cleaning") }
            }
        }

        val dupState = dupStatus(scan.running, analysis.running, dupGroups)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${fmt(dupGroups)} duplicate groups", style = MaterialTheme.typography.titleMedium)
                        Text(
                            when (dupState) {
                                DupStatus.WaitingForScan -> "Waiting for the file scan to finish…"
                                DupStatus.Analysing -> "Analysing photos… more groups may appear"
                                DupStatus.NoneFound -> "No duplicates found"
                                DupStatus.Ready -> "Same photo resized or re-sent, or repeated shots of one subject"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = vm::startDuplicateBatch, enabled = dupGroups > 0) { Text("Review") }
                }
                if (dupState == DupStatus.Analysing) {
                    LinearProgressIndicator(
                        progress = { if (analysis.total == 0) 0f else analysis.done / analysis.total.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Analysing for duplicates… ${fmt(analysis.done)} / ${fmt(analysis.total)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${fmt(toFile)} kept photos to file", style = MaterialTheme.typography.titleMedium)
                        Text(
                            vm.photosRoot?.let { "Into $it" } ?: "Choose your photos folder first",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = vm::startFiling, enabled = toFile > 0 && vm.photosRoot != null) { Text("File") }
                }
                TextButton(onClick = { pickFolder = true }) {
                    Text(if (vm.photosRoot == null) "Choose photos folder…" else "Change photos folder…")
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Include hidden folders", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${fmt(counts.hiddenRemaining)} unreviewed photos in hidden folders",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = vm.includeHidden, onCheckedChange = { vm.includeHidden = it })
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Batch size", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${vm.batchLimit} photos", style = MaterialTheme.typography.titleMedium)
                }
                Slider(
                    value = vm.batchLimit.toFloat(),
                    onValueChange = { vm.setBatchLimit(it.roundToInt()) },
                    onValueChangeFinished = { vm.setBatchLimit(vm.batchLimit, persist = true) },
                    valueRange = MIN_BATCH_SIZE.toFloat()..MAX_BATCH_SIZE.toFloat(),
                    steps = (MAX_BATCH_SIZE - MIN_BATCH_SIZE) / 5 - 1,
                )
                Text(
                    "Duplicate groups are never split, so a batch can run a little over.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        vm.message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

        OutlinedButton(onClick = onRescan, enabled = !scan.running, modifier = Modifier.fillMaxWidth()) {
            Text("Rescan device")
        }
        TextButton(onClick = { confirmReset = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Reset reviewed status…")
        }
    }

    if (pickFolder) {
        FolderPickerDialog(
            initial = vm.photosRoot,
            showHidden = vm.includeHidden,
            onPick = { vm.choosePhotosRoot(it); pickFolder = false },
            onDismiss = { pickFolder = false },
        )
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset reviewed status?") },
            text = { Text("All photos will show up for review again. No files are deleted.") },
            confirmButton = { TextButton(onClick = { vm.resetReviewed(); confirmReset = false }) { Text("Reset") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun ConfirmScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val photos = vm.toDelete
    val selected = photos.count { it.id !in vm.deselected }
    BackHandler(enabled = !vm.busy) { vm.backToReview() }

    Column(Modifier.fillMaxSize().systemBarsPaddingCompat()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Confirm deletion", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::cancelToStart, enabled = !vm.busy) { Text("Discard all") }
            }
            if (vm.groupsFullyDeleted > 0) {
                Text(
                    "Warning: all copies of ${vm.groupsFullyDeleted} duplicate group(s) are selected for deletion.",
                    color = Red, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                if (photos.isEmpty()) "Nothing was marked for deletion." else "Tap a photo to keep it. $selected of ${photos.size} will be deleted.",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (vm.approvedCount > 0) Text("${vm.approvedCount} approved photos will be marked done.", style = MaterialTheme.typography.bodySmall)
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(photos, key = { it.id }) { photo ->
                val marked = photo.id !in vm.deselected
                Box(
                    Modifier.aspectRatio(1f).clip(MaterialTheme.shapes.small).clickable(enabled = !vm.busy) { vm.toggleKeep(photo.id) },
                ) {
                    AsyncImage(
                        model = thumbRequest(ctx, photo.path),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        alpha = if (marked) 1f else 0.45f,
                    )
                    Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(26.dp)) {
                        if (marked) {
                            Icon(Icons.Filled.CheckCircle, "Will be deleted", tint = Red, modifier = Modifier.fillMaxSize().background(Color.White, CircleShape))
                        } else {
                            Box(Modifier.fillMaxSize().border(BorderStroke(2.dp, Color.White), CircleShape))
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::backToReview, enabled = !vm.busy, modifier = Modifier.weight(1f).height(56.dp)) {
                Text("Back")
            }
            Button(
                onClick = vm::confirm,
                enabled = !vm.busy,
                colors = ButtonDefaults.buttonColors(containerColor = if (selected > 0) Red else Green, contentColor = Color.White),
                modifier = Modifier.weight(2f).height(56.dp),
            ) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text(if (selected > 0) "Delete $selected photos" else "Finish")
            }
        }
    }
}
