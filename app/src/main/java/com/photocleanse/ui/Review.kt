package com.photocleanse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.photocleanse.DupMeta
import com.photocleanse.MainViewModel
import com.photocleanse.ReviewItem
import com.photocleanse.dupes.DupKind
import com.photocleanse.review.Decision
import java.io.File

private val Gold = Color(0xFFFFC83D)
private val GroupColors = listOf(
    Color(0xFF4FC3F7), Color(0xFFBA68C8), Color(0xFFFFB74D), Color(0xFF81C784), Color(0xFFF06292), Color(0xFFA1887F),
)

private fun kindLabel(k: DupKind) = when (k) {
    DupKind.EXACT -> "Exact copy"
    DupKind.NEAR -> "Near duplicate"
    DupKind.SIMILAR -> "Similar shots"
}

private fun mb(bytes: Long) = if (bytes >= 1_048_576) "%.1f MB".format(bytes / 1_048_576.0) else "${bytes / 1024} KB"

@Composable
fun ReviewScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val path = vm.currentPath ?: return
    val item = vm.currentItem ?: return
    BackHandler { vm.cancelToStart() }

    // Warm the memory cache for the next few photos so moving on is instant.
    LaunchedEffect(vm.currentIndex) {
        vm.upcomingPaths.forEach { ctx.imageLoader.enqueue(fullRequest(ctx, it)) }
    }

    Column(Modifier.fillMaxSize().systemBarsPaddingCompat()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${vm.currentIndex + 1} / ${vm.batchSize}  ·  ${vm.decisions.size} decided",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(File(path).name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            TextButton(onClick = vm::undo, enabled = vm.canUndo) { Text("Undo") }
            TextButton(onClick = vm::skipRest) { Text("Skip rest") }
        }
        Filmstrip(vm)
        LinearProgressIndicator(
            progress = { vm.decisions.size / vm.batchSize.toFloat() },
            modifier = Modifier.fillMaxWidth(),
        )
        Box(Modifier.weight(1f)) {
            key(path) { ZoomableImage(fullRequest(ctx, path)) }
            vm.decisions[item.photo.id]?.let { d ->
                Surface(
                    color = (if (d == Decision.DELETE) Red else Green).copy(alpha = 0.85f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                ) {
                    Text(
                        if (d == Decision.DELETE) "Marked: delete" else "Marked: approve",
                        Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            item.meta?.let { DupStats(item, it, Modifier.align(Alignment.BottomStart).padding(8.dp)) }
        }
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Button(
                onClick = { vm.decide(Decision.DELETE) },
                colors = ButtonDefaults.buttonColors(containerColor = Red, contentColor = Color.White),
                modifier = Modifier.weight(1f).height(60.dp),
            ) {
                Icon(Icons.Filled.Delete, null); Spacer(Modifier.size(8.dp)); Text("Delete")
            }
            Button(
                onClick = { vm.decide(Decision.APPROVE) },
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
                modifier = Modifier.weight(1f).height(60.dp),
            ) {
                Icon(Icons.Filled.Check, null); Spacer(Modifier.size(8.dp)); Text("Approve")
            }
        }
    }
}

/** Photo roll of the whole batch: tap to jump to (and compare with) any photo. Coloured bars mark duplicate groups. */
@Composable
private fun Filmstrip(vm: MainViewModel) {
    val ctx = LocalContext.current
    val state = rememberLazyListState()
    LaunchedEffect(vm.currentIndex) { state.animateScrollToItem((vm.currentIndex - 2).coerceAtLeast(0)) }

    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(78.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(vm.items, key = { _, it -> it.photo.id }) { i, item ->
            val decision = vm.decisions[item.photo.id]
            val groupColor = item.meta?.let { GroupColors[(it.groupNo - 1) % GroupColors.size] } ?: Color.Transparent
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(62.dp).clip(MaterialTheme.shapes.small)
                        .border(if (i == vm.currentIndex) 3.dp else 0.dp, Color.White, MaterialTheme.shapes.small)
                        .clickable { vm.goTo(i) },
                ) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(File(item.photo.path)).size(160).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (decision != null) {
                        val c = if (decision == Decision.DELETE) Red else Green
                        Box(Modifier.fillMaxSize().background(c.copy(alpha = 0.40f)))
                        Icon(
                            if (decision == Decision.DELETE) Icons.Filled.Close else Icons.Filled.Check, null,
                            tint = Color.White, modifier = Modifier.align(Alignment.Center).size(24.dp),
                        )
                    }
                    if (item.meta?.isBest == true) {
                        Icon(Icons.Filled.Star, "Best copy", tint = Gold, modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(16.dp))
                    }
                }
                Box(Modifier.padding(top = 3.dp).width(62.dp).height(4.dp).background(groupColor))
            }
        }
    }
}

@Composable
private fun DupStats(item: ReviewItem, m: DupMeta, modifier: Modifier) {
    Surface(color = Color.Black.copy(alpha = 0.68f), shape = MaterialTheme.shapes.medium, modifier = modifier) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "Group ${m.groupNo}/${m.groupCount} · ${kindLabel(m.kind)} · ${m.groupSize} photos",
                style = MaterialTheme.typography.labelMedium, color = Color.White,
            )
            if (m.isBest) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Star, null, tint = Gold, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("Best copy of this group", color = Gold, style = MaterialTheme.typography.titleSmall)
                }
            } else {
                Text(m.badges.joinToString(" · "), color = Color(0xFFFF8A80), style = MaterialTheme.typography.titleSmall)
            }
            Text(
                "${m.width}×${m.height} · ${mb(item.photo.size)} · sharpness ${"%.0f".format(m.sharpness)}",
                style = MaterialTheme.typography.bodySmall, color = Color.White,
            )
        }
    }
}
