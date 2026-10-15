@file:OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)

package com.pdflib

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlin.math.abs
import kotlin.math.roundToInt

private val InvertFilter = ColorFilter.colorMatrix(
    ColorMatrix(floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f
    ))
)

@Composable
fun ReaderScreen(vm: LibraryVm, pdfId: Long) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val pdfs by vm.pdfs.collectAsState()
    val pdf = pdfs.firstOrNull { it.id == pdfId }
    val title = remember(pdfId) { pdf?.title() ?: "" }
    val uriStr = remember(pdfId) { pdf?.uri }
    val startPage = remember(pdfId) { pdf?.lastPage ?: 0 }

    var doc by remember(pdfId) { mutableStateOf<PdfDoc?>(null) }
    var firstRatio by remember(pdfId) { mutableStateOf(1.414f) }
    var failed by remember(pdfId) { mutableStateOf(false) }
    val holder = remember(pdfId) { arrayOfNulls<PdfDoc>(1) }

    BackHandler { vm.closeReader() }
    DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    LaunchedEffect(pdfId) {
        val u = uriStr
        if (u == null) { failed = true; return@LaunchedEffect }
        val d = PdfDoc.open(ctx, Uri.parse(u))
        if (d == null || d.pageCount == 0) { failed = true; return@LaunchedEffect }
        holder[0] = d
        d.pageSize(0)?.let { firstRatio = it.second.toFloat() / it.first }
        doc = d
    }
    DisposableEffect(pdfId) { onDispose { holder[0]?.close() } }

    val readerBg = if (pal.dark) Color(0xFF0C0B09) else Color(0xFFD6D1C4)
    Box(Modifier.fillMaxSize().background(readerBg).pointerInput(Unit) { detectTapGestures { } }) {
        val d = doc
        when {
            failed -> Column(Modifier.fillMaxSize().background(pal.bg).statusBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Hint("Couldn't open this PDF", "It may be password-protected, damaged, or moved. Rescan from Settings if the file was moved.")
                TextButton(onClick = { vm.closeReader() }) { Text("Go back", color = pal.accent) }
            }
            d == null -> Box(Modifier.fillMaxSize().background(pal.bg), contentAlignment = Alignment.Center) {
                Text("Opening…", color = pal.soft, fontSize = 14.sp)
            }
            else -> Reading(vm, pdfId, title, d, firstRatio, startPage, readerBg)
        }
    }
}

@Composable
private fun Reading(vm: LibraryVm, pdfId: Long, title: String, doc: PdfDoc, firstRatio: Float, startPage: Int, readerBg: Color) {
    val pal = LocalPal.current
    val count = doc.pageCount
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, count - 1))
    val zoom = remember { ZoomState(listState) }
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    val invert = vm.invert
    val density = LocalDensity.current
    val viewConfig = LocalViewConfiguration.current

    // Real page proportions are read quietly in the background; every page starts with page 1's shape.
    val ratios = remember(doc) { mutableStateListOf<Float>().apply { repeat(count) { add(firstRatio) } } }
    LaunchedEffect(doc) {
        for (i in 1 until count) {
            val s = doc.pageSize(i) ?: break
            val r = s.second.toFloat() / s.first
            if (abs(r - ratios[i]) > 0.002f) ratios[i] = r
            if (i % 15 == 0) yield()
        }
    }

    val current by remember { derivedStateOf { listState.firstVisibleItemIndex } }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.debounce(700).collect { vm.saveProgress(pdfId, it, count) }
    }
    DisposableEffect(Unit) { onDispose { vm.saveProgress(pdfId, listState.firstVisibleItemIndex, count) } }
    LaunchedEffect(listState) { listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) chrome = false } }

    val allBm by vm.bookmarks.collectAsState()
    val bms = remember(allBm, pdfId) { allBm.filter { it.pdfId == pdfId } }
    val here = bms.firstOrNull { it.page == current }

    // ---- sharpness: pages are drawn at the layout size and only ever TRANSFORMED while you zoom (no re-layout, no jump).
    // Once you stop, they are re-rendered at the resolution the screen needs, and at high zoom just the visible part is
    // rendered at full density (a "tile"), so text stays crisp without huge bitmaps.
    var settled by remember { mutableFloatStateOf(1f) }
    val tiles = remember(doc) { mutableStateMapOf<Int, PageTile>() }
    LaunchedEffect(zoom) {
        snapshotFlow { zoom.scale }.debounce(140).collect { settled = it }
    }
    LaunchedEffect(zoom, doc) {
        snapshotFlow { Quad(zoom.scale, zoom.tx, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset) }
            .debounce(170)
            .collectLatest { q ->
                if (q.scale <= TILE_FROM || zoom.viewW <= 0f) { tiles.clear(); return@collectLatest }
                val vTop = 0f
                val vBottom = zoom.areaH / q.scale
                val vLeft = -q.tx / q.scale
                val vRight = vLeft + zoom.viewW / q.scale
                val pw = zoom.viewW
                val seen = HashSet<Int>()
                for (it in listState.layoutInfo.visibleItemsInfo) {
                    val page = it.index
                    if (page !in 0 until count) continue
                    val ph = it.size.toFloat()
                    if (ph <= 0f) continue
                    val top = it.offset.toFloat()
                    val y0 = maxOf(vTop - top, 0f); val y1 = minOf(vBottom - top, ph)
                    val x0 = maxOf(vLeft, 0f); val x1 = minOf(vRight, pw)
                    if (y1 - y0 < 8f || x1 - x0 < 8f) continue
                    seen.add(page)
                    val mx = (x1 - x0) * 0.15f; val my = (y1 - y0) * 0.15f
                    val l = ((x0 - mx) / pw).coerceIn(0f, 1f); val r = ((x1 + mx) / pw).coerceIn(0f, 1f)
                    val t = ((y0 - my) / ph).coerceIn(0f, 1f); val b = ((y1 + my) / ph).coerceIn(0f, 1f)
                    val have = tiles[page]
                    if (have != null && have.covers(l, t, r, b) && have.scale >= q.scale * 0.9f) continue
                    val bmp = doc.renderRegion(page, pw * q.scale, l, t, r, b, 5_000_000) ?: continue
                    tiles[page] = PageTile(bmp, l, t, r, b, q.scale)
                }
                tiles.keys.toList().forEach { if (it !in seen) tiles.remove(it) }
            }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val topInset = WindowInsets.systemBars.getTop(density)
        val bottomInset = WindowInsets.systemBars.getBottom(density)
        val viewWpx = constraints.maxWidth.toFloat()
        val areaHpx = (constraints.maxHeight - topInset - bottomInset).toFloat().coerceAtLeast(1f)
        zoom.viewW = viewWpx
        zoom.areaH = areaHpx
        val topDp = with(density) { topInset.toDp() }
        val bottomDp = with(density) { bottomInset.toDp() }
        val baseW = viewWpx.toInt().coerceAtLeast(1)

        Box(
            Modifier.fillMaxSize().padding(top = topDp, bottom = bottomDp).clipToBounds()
                .pointerInput(Unit) {
                    val slop = viewConfig.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        zoom.stopMotion()
                        var pinching = false
                        var panning = false
                        var travelled = 0f
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                if (!pinching) { pinching = true; chrome = false }
                                val ratio = event.calculateZoom()
                                val pan = event.calculatePan()
                                val prev = event.calculateCentroid(useCurrent = false)
                                if (ratio != 1f || pan != Offset.Zero) zoom.transform(prev, prev + pan, ratio)
                                event.changes.forEach { it.consume() }
                            } else if (pinching) {
                                // One finger left after a pinch: it must not turn into a scroll or a tap.
                                event.changes.forEach { it.consume() }
                            } else if (zoom.scale > 1.001f) {
                                val c = event.changes.firstOrNull { it.id == down.id }
                                if (c != null && c.pressed) {
                                    tracker.addPosition(c.uptimeMillis, c.position)
                                    val dx = c.position.x - c.previousPosition.x
                                    travelled += abs(dx)
                                    if (!panning && travelled > slop) panning = true
                                    if (panning) zoom.panX(dx)   // vertical movement is handled by the list itself
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!pinching && panning && zoom.scale > 1.001f) {
                            val v = tracker.calculateVelocity()
                            if (abs(v.x) > 400f && abs(v.x) > abs(v.y)) zoom.flingX(scope, v.x)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { chrome = !chrome },
                        onDoubleTap = { p ->
                            val target = if (zoom.scale > 1.25f) 1f else DOUBLE_TAP_ZOOM
                            zoom.animateTo(scope, target, p)
                        }
                    )
                }
        ) {
            // The list is laid out at 1× width but only 1/zoom of the height, then scaled up from the top-left corner:
            // exactly the visible region fills the screen, and zooming never re-lays-out or re-flows a single page.
            Box(
                Modifier
                    .layout { measurable, c ->
                        val h = kotlin.math.ceil(c.maxHeight / zoom.scale).toInt().coerceAtLeast(1)
                        val p = measurable.measure(Constraints.fixed(c.maxWidth, h))
                        layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
                    }
                    .graphicsLayer {
                        scaleX = zoom.scale; scaleY = zoom.scale
                        translationX = zoom.tx
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(count, key = { it }) { i ->
                        PageView(doc, i, ratios[i], baseW, if (settled > 1.15f) 2f else 1f, invert, tiles[i])
                    }
                }
            }
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(pal.surface.copy(alpha = 0.97f)).statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.closeReader() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = pal.ink) }
                Text(title, Modifier.weight(1f), fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = pal.ink)
                IconButton(onClick = { vm.toggleBookmark(pdfId, current, here) }) {
                    Icon(if (here != null) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, "Bookmark page", tint = if (here != null) pal.accent else pal.ink)
                }
                IconButton(onClick = { sheet = true }) { Icon(Icons.Rounded.FormatListBulleted, "Bookmarks", tint = pal.ink) }
                IconButton(onClick = { vm.updateInvert(!invert) }) { Icon(Icons.Rounded.InvertColors, "Night reading", tint = if (invert) pal.accent else pal.ink) }
            }
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.fillMaxWidth().background(pal.surface.copy(alpha = 0.97f)).navigationBarsPadding().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${current + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink,
                    modifier = Modifier.clickable { jump = true }.padding(horizontal = 8.dp, vertical = 8.dp))
                Slider(
                    value = current.toFloat(), onValueChange = { v -> scope.launch { listState.scrollToItem(v.roundToInt().coerceIn(0, count - 1)) } },
                    valueRange = 0f..(count - 1).coerceAtLeast(1).toFloat(), modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent, inactiveTrackColor = pal.line)
                )
                Text("$count", fontSize = 13.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = pal.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Bookmarks", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = pal.ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (bms.isEmpty()) Text("Tap the bookmark icon at the top to mark a page.", fontSize = 14.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            bms.forEach { b ->
                Row(Modifier.fillMaxWidth().clickable { scope.launch { listState.scrollToItem(b.page.coerceIn(0, count - 1)) }; sheet = false }.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Bookmark, null, tint = pal.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Text("Page ${b.page + 1}", Modifier.weight(1f).padding(vertical = 14.dp), fontSize = 15.sp, color = pal.ink)
                    IconButton(onClick = { vm.deleteBookmark(b.id) }) { Icon(Icons.Rounded.Close, "Remove", tint = pal.soft) }
                }
            }
        }
    }

    if (jump) {
        var txt by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { jump = false }, containerColor = pal.surface,
            title = { Text("Go to page", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
            text = {
                OutlinedTextField(txt, { txt = it.filter { c -> c.isDigit() }.take(5) }, singleLine = true,
                    placeholder = { Text("1 – $count") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            },
            confirmButton = {
                TextButton(onClick = {
                    txt.toIntOrNull()?.let { n -> scope.launch { listState.scrollToItem((n - 1).coerceIn(0, count - 1)) } }
                    jump = false
                }) { Text("Go") }
            },
            dismissButton = { TextButton(onClick = { jump = false }) { Text("Cancel") } }
        )
    }
}

private const val TILE_FROM = 2.05f
private const val DOUBLE_TAP_ZOOM = 2.5f

private class Quad(val scale: Float, val tx: Float, val i: Int, val o: Int) {
    override fun equals(other: Any?) = other is Quad && other.scale == scale && other.tx == tx && other.i == i && other.o == o
    override fun hashCode() = ((scale.hashCode() * 31 + tx.hashCode()) * 31 + i) * 31 + o
}

/** A sharp, high-density render of part of one page (rectangle in page fractions). */
@Stable
class PageTile(val bmp: Bitmap, val l: Float, val t: Float, val r: Float, val b: Float, val scale: Float) {
    fun covers(l2: Float, t2: Float, r2: Float, b2: Float) = l <= l2 + 0.02f && t <= t2 + 0.02f && r >= r2 - 0.02f && b >= b2 - 0.02f
}

/**
 * Zoom + horizontal pan state. The page list is scaled from its top-left corner, so the whole transform is just
 * (scale, tx) plus the list's own vertical scroll. Every change keeps the point under your fingers fixed.
 */
@Stable
class ZoomState(private val list: LazyListState) {
    var scale by mutableFloatStateOf(1f); private set
    var tx by mutableFloatStateOf(0f); private set
    var viewW = 0f
    var areaH = 0f
    private var motion: Job? = null

    private fun minTx(s: Float) = viewW * (1f - s)

    /** The content under [from] moves to [to] while everything is scaled by [ratio]. Points are in pixels inside the page area. */
    fun transform(from: Offset, to: Offset, ratio: Float) {
        val s0 = scale
        val s1 = (s0 * ratio).coerceIn(1f, MAX_SCALE)
        val xl = (from.x - tx) / s0
        val nx = (to.x - xl * s1).coerceIn(minTx(s1), 0f)
        val dy = from.y / s0 - to.y / s1
        scale = s1; tx = nx
        if (abs(dy) > 0.01f) list.dispatchRawDelta(dy)
    }

    fun panX(dx: Float) { tx = (tx + dx).coerceIn(minTx(scale), 0f) }

    fun stopMotion() { motion?.cancel(); motion = null }

    fun animateTo(scope: kotlinx.coroutines.CoroutineScope, target: Float, focal: Offset) {
        stopMotion()
        motion = scope.launch {
            animate(scale, target, animationSpec = tween(300, easing = FastOutSlowInEasing)) { v, _ ->
                if (scale > 0f) transform(focal, focal, v / scale)
            }
        }
    }

    fun flingX(scope: kotlinx.coroutines.CoroutineScope, velocity: Float) {
        stopMotion()
        motion = scope.launch {
            val a = Animatable(tx)
            a.updateBounds(minTx(scale), 0f)
            a.animateDecay(velocity, exponentialDecay(frictionMultiplier = 1.4f)) { tx = value }
        }
    }

    companion object { const val MAX_SCALE = 5f }
}

@Composable
private fun PageView(doc: PdfDoc, index: Int, ratio: Float, baseWidthPx: Int, oversample: Float, invert: Boolean, tile: PageTile?) {
    // A quick base render first (so a page is never blank), then a sharper one when you have zoomed in.
    // The previous render stays on screen until the better one is ready, so nothing flashes.
    var bmp by remember(doc, index) { mutableStateOf<Bitmap?>(null) }
    val wantW = run {
        val capped = kotlin.math.sqrt(6_000_000f / ratio).toInt()   // keep a single page bitmap under ~24 MB
        (baseWidthPx * oversample).toInt().coerceAtMost(capped).coerceAtLeast(baseWidthPx.coerceAtMost(capped))
    }
    LaunchedEffect(doc, index) { if (bmp == null) doc.render(index, baseWidthPx)?.let { if (bmp == null) bmp = it } }
    LaunchedEffect(doc, index, wantW) { doc.render(index, wantW)?.let { bmp = it } }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(if (invert) Color(0xFF101010) else Color.White)) {
        val b = bmp
        if (b != null) Image(
            b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
            colorFilter = if (invert) InvertFilter else null
        )
        if (tile != null) {
            val img = remember(tile) { tile.bmp.asImageBitmap() }
            Canvas(Modifier.fillMaxSize()) {
                val x = (tile.l * size.width).roundToInt()
                val y = (tile.t * size.height).roundToInt()
                val w = ((tile.r - tile.l) * size.width).roundToInt().coerceAtLeast(1)
                val h = ((tile.b - tile.t) * size.height).roundToInt().coerceAtLeast(1)
                drawImage(img, dstOffset = IntOffset(x, y), dstSize = IntSize(w, h), colorFilter = if (invert) InvertFilter else null)
            }
        }
    }
}
