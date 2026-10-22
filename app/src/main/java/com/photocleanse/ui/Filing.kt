package com.photocleanse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import com.photocleanse.review.folderHue
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.photocleanse.MainViewModel
import java.io.File
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private const val KEY_UP = "\u0000up"
private const val KEY_HERE = "\u0000here"
private const val HOVER_OPEN_MS = 550L
private val FilingBlue = Color(0xFF3D5A9C)

/** `/storage/emulated/0/Pictures/Family` relative to `/storage/emulated/0/Pictures` -> `Pictures/Family`. */
internal fun relativeLabel(root: String, dir: String): String {
    val rootName = File(root).name.ifEmpty { root }
    val rest = dir.removePrefix(root).trim('/', '\\')
    return if (rest.isEmpty()) rootName else "$rootName/$rest"
}

@Composable
fun FilingScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val root = vm.photosRoot ?: return
    val navDir = vm.navDir ?: root
    val photo = vm.filingCurrent ?: return
    BackHandler { vm.cancelFiling() }

    LaunchedEffect(vm.filingIndex) {
        vm.filingUpcoming.forEach { ctx.imageLoader.enqueue(fullRequest(ctx, it)) }
    }

    val entries = remember(navDir, vm.foldersVersion, vm.folderTree) { vm.folders(navDir) }
    val bounds = remember { mutableStateMapOf<String, Rect>() }
    var gridBounds by remember { mutableStateOf<Rect?>(null) }
    var dragPos by remember { mutableStateOf<Offset?>(null) }
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var showNew by remember { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val atRoot = navDir == root

    val hovered by remember {
        derivedStateOf { dragPos?.let { p -> bounds.entries.firstOrNull { it.value.contains(p) }?.key } }
    }

    // Holding a dragged photo over a folder opens it; holding it over "Up" goes up.
    LaunchedEffect(hovered, navDir) {
        val h = hovered ?: return@LaunchedEffect
        delay(HOVER_OPEN_MS)
        when {
            h == KEY_UP -> vm.navUp()
            h == KEY_HERE -> Unit
            entries.firstOrNull { it.path == h }?.hasChildren == true -> vm.openFolder(h)
        }
    }

    // Dragging near the top/bottom edge of the folder grid scrolls it.
    val dragging = dragPos != null
    LaunchedEffect(dragging) {
        if (!dragging) return@LaunchedEffect
        val edge = with(density) { 36.dp.toPx() }
        while (true) {
            val p = dragPos
            val g = gridBounds
            if (p != null && g != null && p.x in g.left..g.right) {
                when {
                    p.y in g.top..(g.top + edge) -> gridState.scrollBy(-14f)
                    p.y in (g.bottom - edge)..g.bottom -> gridState.scrollBy(14f)
                }
            }
            delay(16)
        }
    }

    val onDrop = rememberUpdatedState { pos: Offset ->
        val hit = bounds.entries.firstOrNull { it.value.contains(pos) }?.key
        when {
            hit == null || hit == KEY_UP -> Unit
            hit == KEY_HERE -> if (!atRoot) vm.fileCurrentTo(navDir)
            else -> vm.fileCurrentTo(hit)
        }
    }

    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOrigin = it.positionInWindow() }) {
        Column(Modifier.fillMaxSize().systemBarsPaddingCompat()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${vm.filingIndex + 1} / ${vm.filingPhotos.size}  ·  ${vm.filingDestinations.size} sorted",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(File(photo.path).name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
                TextButton(onClick = vm::filingUndo, enabled = vm.filingCanUndo) { Text("Undo") }
                TextButton(onClick = vm::filingSkipRest) { Text("Skip rest") }
            }
            FilingStrip(vm)
            LinearProgressIndicator(
                progress = { vm.filingDestinations.size / vm.filingPhotos.size.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            Box(Modifier.weight(1f)) {
                key(photo.path) { ZoomableImage(fullRequest(ctx, photo.path)) }
                vm.filingDestinations[photo.id]?.let { d ->
                    Surface(
                        color = Green.copy(alpha = 0.85f),
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    ) {
                        Text(
                            "Filed → ${relativeLabel(root, d)}",
                            Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                DragHandle(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(12.dp),
                    onStart = { dragPos = it },
                    onMove = { delta -> dragPos = dragPos?.plus(delta) },
                    onEnd = { dragPos?.let(onDrop.value); dragPos = null },
                    onCancel = { dragPos = null },
                )
            }
            Column(Modifier.fillMaxWidth().background(Color(0xFF1E1E23)).padding(10.dp)) {
                Breadcrumb(root, navDir, onNavigate = vm::openFolder)
                Box(
                    Modifier.padding(top = 6.dp).onGloballyPositioned { gridBounds = it.boundsInWindow() },
                ) {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.heightIn(max = 172.dp),
                        contentPadding = PaddingValues(0.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (!atRoot) {
                            item(key = KEY_HERE) {
                                FolderTile("File here ✓", false, hovered == KEY_HERE, Green, KEY_HERE, bounds) {
                                    vm.fileCurrentTo(navDir)
                                }
                            }
                        }
                        items(entries, key = { it.path }) { e ->
                            FolderTile(e.name, e.hasChildren, hovered == e.path, null, e.path, bounds) {
                                if (e.hasChildren) vm.openFolder(e.path) else vm.fileCurrentTo(e.path)
                            }
                        }
                    }
                    if (entries.isEmpty() && (vm.folderTree == null || atRoot)) {
                        Text(
                            if (vm.folderTree == null) "Loading folders…" else "No sub-folders yet — tap + New to create one.",
                            style = MaterialTheme.typography.bodySmall, color = Color.Gray,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = vm::navUp, enabled = !atRoot,
                        modifier = Modifier.weight(1f).height(48.dp).trackBounds(KEY_UP, bounds),
                        border = if (hovered == KEY_UP) androidx.compose.foundation.BorderStroke(2.dp, Color.White) else null,
                    ) { Text("↑ Up") }
                    OutlinedButton(onClick = { showNew = true }, modifier = Modifier.weight(1f).height(48.dp)) { Text("＋ New") }
                    OutlinedButton(
                        onClick = { vm.filingGoTo((vm.filingIndex + 1) % vm.filingPhotos.size) },
                        modifier = Modifier.weight(1f).height(48.dp),
                    ) { Text("Skip") }
                }
            }
        }

        dragPos?.let { p ->
            Box(
                Modifier
                    .offset { IntOffset((p.x - rootOrigin.x - 40.dp.toPx()).roundToInt(), (p.y - rootOrigin.y - 90.dp.toPx()).roundToInt()) }
                    .size(80.dp)
                    .clip(MaterialTheme.shapes.small)
                    .border(2.dp, Color.White, MaterialTheme.shapes.small),
            ) {
                AsyncImage(thumbRequest(ctx, photo.path), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }

    if (showNew) {
        NewFolderDialog(
            parent = relativeLabel(root, navDir),
            onCreate = { name -> vm.createFolder(name).also { if (it) showNew = false } },
            onDismiss = { showNew = false },
        )
    }
}

/** Reports this composable's on-screen bounds under [key] so a dragged photo can find what it is over. */
private fun Modifier.trackBounds(key: String, bounds: MutableMap<String, Rect>): Modifier = composed {
    DisposableEffect(key) { onDispose { bounds.remove(key) } }
    onGloballyPositioned { bounds[key] = it.boundsInWindow() }
}

@Composable
private fun FolderTile(
    label: String, hasChildren: Boolean, hot: Boolean, color: Color?, key: String,
    bounds: MutableMap<String, Rect>, onClick: () -> Unit,
) {
    // Dark, muted tint per folder name (white text stays readable); lighter while a dragged photo hovers over it.
    val hue = remember(label) { folderHue(label).toFloat() }
    val base = color ?: Color.hsv(hue, 0.38f, if (hot) 0.60f else 0.40f)
    // Long names wrap to two lines; if they still do not fit the font shrinks (down to 10sp) until they do.
    var fontSize by remember(label) { mutableStateOf(14.sp) }
    Box(
        Modifier
            .height(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(base)
            .then(if (hot) Modifier.border(2.dp, Color.White, MaterialTheme.shapes.medium) else Modifier)
            .trackBounds(key, bounds)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, maxLines = 2, textAlign = TextAlign.Center, fontSize = fontSize, lineHeight = fontSize * 1.1f,
            color = Color.White, fontWeight = if (color != null) FontWeight.SemiBold else null,
            onTextLayout = { if (it.hasVisualOverflow && fontSize > 10.sp) fontSize *= 0.92f },
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        if (hasChildren) {
            Text("▸", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.align(Alignment.TopEnd).padding(end = 6.dp, top = 2.dp))
        }
    }
}

@Composable
private fun Breadcrumb(root: String, dir: String, onNavigate: (String) -> Unit) {
    val crumbs = buildList {
        var cur: File? = File(dir)
        while (cur != null) {
            add(cur.path)
            if (cur.path == root) break
            cur = cur.parentFile
        }
    }.reversed()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, path ->
            if (i > 0) Text(" › ", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            Text(
                File(path).name.ifEmpty { path },
                style = MaterialTheme.typography.bodySmall,
                color = if (i == crumbs.lastIndex) Color.White else Color(0xFF8AB4F8),
                modifier = Modifier.clickable(enabled = i < crumbs.lastIndex) { onNavigate(path) }.padding(vertical = 4.dp),
            )
        }
    }
}

/** Grab this and drag it onto a folder tile. Reports window coordinates of the finger. */
@Composable
private fun DragHandle(
    modifier: Modifier, onStart: (Offset) -> Unit, onMove: (Offset) -> Unit, onEnd: () -> Unit, onCancel: () -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Surface(
        color = Color.Black.copy(alpha = 0.65f),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { onStart(origin + it) },
                    onDrag = { change, amount -> change.consume(); onMove(amount) },
                    onDragEnd = onEnd,
                    onDragCancel = onCancel,
                )
            },
    ) {
        Text(
            "✋ Drag me onto a folder",
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp), color = Color.White,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun FilingStrip(vm: MainViewModel) {
    val ctx = LocalContext.current
    val state = rememberLazyListState()
    LaunchedEffect(vm.filingIndex) { state.animateScrollToItem((vm.filingIndex - 2).coerceAtLeast(0)) }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().height(70.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        itemsIndexed(vm.filingPhotos, key = { _, p -> p.id }) { i, p ->
            val filed = vm.filingDestinations.containsKey(p.id)
            Box(
                Modifier.size(62.dp).clip(MaterialTheme.shapes.small)
                    .border(if (i == vm.filingIndex) 3.dp else 0.dp, Color.White, MaterialTheme.shapes.small)
                    .clickable { vm.filingGoTo(i) },
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(File(p.path)).size(160).build(),
                    contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
                if (filed) {
                    Box(Modifier.fillMaxSize().background(Green.copy(alpha = 0.40f)))
                    Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.align(Alignment.Center).size(24.dp))
                }
            }
        }
    }
}

@Composable
fun NewFolderDialog(parent: String, onCreate: (String) -> Boolean, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New folder in $parent") },
        text = {
            OutlinedTextField(
                value = name, onValueChange = { name = it; failed = false }, singleLine = true,
                isError = failed, label = { Text(if (failed) "Could not create folder" else "Folder name") },
            )
        },
        confirmButton = { TextButton(onClick = { failed = !onCreate(name) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun FilingConfirmScreen(vm: MainViewModel) {
    val ctx = LocalContext.current
    val root = vm.photosRoot ?: return
    val moves = vm.filingMoves
    val groups = moves.groupBy { it.destDir }.toList().sortedBy { it.first }
    val left = vm.filingPhotos.size - moves.size
    BackHandler(enabled = !vm.busy) { vm.backToFiling() }

    Column(Modifier.fillMaxSize().systemBarsPaddingCompat()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Confirm filing", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = vm::cancelFiling, enabled = !vm.busy) { Text("Discard all") }
            }
            Text(
                if (moves.isEmpty()) "Nothing was sorted." else "${moves.size} photos will be moved. Name clashes get -1, -2, …",
                style = MaterialTheme.typography.bodyMedium,
            )
            if (left > 0) Text("$left skipped photos stay where they are.", style = MaterialTheme.typography.bodySmall)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(groups.size) { gi ->
                val (dir, list) = groups[gi]
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("${relativeLabel(root, dir)}  ·  ${list.size}", style = MaterialTheme.typography.titleSmall)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(list, key = { _, m -> m.photo.id }) { _, m ->
                            AsyncImage(
                                thumbRequest(ctx, m.photo.path), null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(MaterialTheme.shapes.small),
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::backToFiling, enabled = !vm.busy, modifier = Modifier.weight(1f).height(56.dp)) { Text("Back") }
            Button(
                onClick = vm::confirmFiling,
                enabled = !vm.busy && moves.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = Green, contentColor = Color.White),
                modifier = Modifier.weight(2f).height(56.dp),
            ) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Move ${moves.size} photos")
            }
        }
    }
}
