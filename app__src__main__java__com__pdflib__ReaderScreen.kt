@file:OptIn(ExperimentalMaterial3Api::class, FlowPreview::class, ExperimentalFoundationApi::class)

package com.pdflib

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.shape.RoundedCornerShape
import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
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
import kotlinx.coroutines.delay
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

/** Pick one PDF and keep access to it (used by "Find this file"). */
private class PickOnePdf : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
}

@Composable
fun ReaderScreen(vm: LibraryVm, pdfId: Long) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val view = LocalView.current
    val pdfs by vm.pdfs.collectAsState()
    val pdf = pdfs.firstOrNull { it.id == pdfId }
    val title = remember(pdfId) { pdf?.title() ?: "" }
    val startPage = remember(pdfId) { pdf?.lastPage ?: 0 }
    val startZoom = remember(pdfId) { pdf?.zoom ?: 1f }
    val startTx = remember(pdfId) { pdf?.zoomTx ?: 0f }

    var doc by remember(pdfId) { mutableStateOf<PdfDoc?>(null) }
    var firstRatio by remember(pdfId) { mutableStateOf(1.414f) }
    var failed by remember(pdfId) { mutableStateOf(false) }
    var retry by remember(pdfId) { mutableIntStateOf(0) }
    var searching by remember(pdfId) { mutableStateOf(false) }
    val findFile = rememberLauncherForActivityResult(PickOnePdf()) { u -> if (u != null) vm.relinkManually(pdfId, u) { ok -> if (ok) retry++ } }
    val holder = remember(pdfId) { arrayOfNulls<PdfDoc>(1) }

    BackHandler { vm.closeReader() }
    // Reading comfort: screen on, brightness and orientation apply only while a PDF is open.
    val activity = remember(ctx) { ctx.findActivity() }
    DisposableEffect(vm.keepOn) { view.keepScreenOn = vm.keepOn; onDispose { view.keepScreenOn = false } }
    DisposableEffect(vm.brightness) {
        activity?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = if (vm.brightness < 0f) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else vm.brightness } }
        onDispose { activity?.window?.let { w -> w.attributes = w.attributes.also { it.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE } } }
    }
    DisposableEffect(vm.orient) {
        activity?.requestedOrientation = when (vm.orient) {
            1 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            2 -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }
    LaunchedEffect(pdfId, retry) {
        failed = false
        // Always use the file's current address: a rescan may have just followed a rename.
        val current = vm.pdfs.value.firstOrNull { it.id == pdfId }?.uri
        var d = if (current != null) PdfDoc.open(ctx, Uri.parse(current)) else null
        if (d == null && retry == 0) {
            // Not there any more? It was probably renamed or moved. Look for it before giving up.
            searching = true
            val fixed = vm.repairPdf(pdfId)
            searching = false
            if (fixed != null) d = PdfDoc.open(ctx, Uri.parse(fixed))
        }
        if (d == null || d.pageCount == 0) { d?.close(); failed = true; return@LaunchedEffect }
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
                Hint("Couldn't find this PDF", "It may have been moved out of your linked folders, deleted, or it is password-protected. Point Shelf to the file and everything you saved for it (folder, stars, bookmarks, progress) stays.")
                Button(
                    onClick = { findFile.launch(arrayOf("application/pdf")) }, shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = pal.accent, contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                ) { Text("Find this file", fontWeight = FontWeight.SemiBold) }
                TextButton(onClick = { vm.closeReader() }) { Text("Go back", color = pal.soft) }
            }
            d == null -> Box(Modifier.fillMaxSize().background(pal.bg), contentAlignment = Alignment.Center) {
                Text(if (searching) "Looking for this file…" else "Opening…", color = pal.soft, fontSize = 14.sp)
            }
            else -> Reading(vm, pdfId, title, d, firstRatio, startPage, readerBg, startZoom, startTx)
        }
    }
}

@Composable
private fun Reading(vm: LibraryVm, pdfId: Long, title: String, doc: PdfDoc, firstRatio: Float, startPage: Int, readerBg: Color, startZoom: Float, startTx: Float) {
    val pal = LocalPal.current
    val count = doc.pageCount
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = startPage.coerceIn(0, count - 1))
    val zoom = remember { ZoomState(listState) }
    val paged = vm.pageMode
    val pagerState = rememberPagerState(initialPage = startPage.coerceIn(0, count - 1)) { count }
    var pos by remember { mutableIntStateOf(startPage.coerceIn(0, count - 1)) }
    var chrome by remember { mutableStateOf(true) }
    var settings by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    val goTo: suspend (Int) -> Unit = { p ->
        val t = p.coerceIn(0, count - 1)
        if (paged) pagerState.scrollToPage(t) else listState.scrollToItem(t)
    }
    // Page-number badge: shows while the list is moving, and for a moment after a tap; fades away 1 s after things go still.
    var badge by remember { mutableStateOf(false) }
    var badgeTap by remember { mutableIntStateOf(0) }
    var dragP by remember { mutableStateOf<Float?>(null) }   // set while the badge itself is being dragged
    val moving = listState.isScrollInProgress || pagerState.isScrollInProgress || dragP != null
    LaunchedEffect(moving, badgeTap) {
        if (moving) badge = true
        else if (badge || badgeTap > 0) { badge = true; delay(1000); badge = false }
    }
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

    val current by remember(paged) { derivedStateOf { if (paged) pagerState.currentPage else listState.firstVisibleItemIndex } }
    val latestCurrent = rememberUpdatedState(current)
    val latestPaged = rememberUpdatedState(paged)
    // Switching between Scroll and Pages keeps you on the same page.
    LaunchedEffect(paged) {
        goTo(pos)
        snapshotFlow { if (paged) pagerState.currentPage else listState.firstVisibleItemIndex }.collect { pos = it }
    }
    LaunchedEffect(paged) {
        snapshotFlow { if (paged) pagerState.currentPage else listState.firstVisibleItemIndex }.debounce(700).collect { vm.saveProgress(pdfId, it, count) }
    }
    DisposableEffect(Unit) { onDispose { vm.saveProgress(pdfId, latestCurrent.value, count) } }
    LaunchedEffect(listState) { listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) chrome = false } }
    LaunchedEffect(pagerState) { snapshotFlow { pagerState.isScrollInProgress }.collect { if (it) chrome = false } }

    // The zoom you left a PDF at is remembered for that PDF (scroll layout).
    LaunchedEffect(zoom) {
        if (startZoom > 1.02f) {
            snapshotFlow { zoom.viewW }.first { it > 0f }
            zoom.restore(startZoom, startTx)
        }
    }
    LaunchedEffect(zoom, paged) {
        if (!paged) snapshotFlow { zoom.scale to zoom.tx }.debounce(900).collect { (sc, t) -> if (zoom.viewW > 0f) vm.saveZoom(pdfId, sc, t / zoom.viewW) }
    }
    DisposableEffect(Unit) { onDispose { if (!latestPaged.value && zoom.viewW > 0f) vm.saveZoom(pdfId, zoom.scale, zoom.tx / zoom.viewW) } }

    val allBm by vm.bookmarks.collectAsState()
    val bms = remember(allBm, pdfId) { allBm.filter { it.pdfId == pdfId } }
    val here = bms.firstOrNull { it.page == current }

    // ---- the smart bottom bar: bookmarks on the track, a live page preview, time left at YOUR pace,
    // a way back after a long jump, an all-pages overview and a focus timer.
    val marks = remember(bms) { bms.map { it.page }.distinct().sorted() }
    var backTo by remember { mutableStateOf<Int?>(null) }
    var lastBack by remember { mutableIntStateOf(0) }
    LaunchedEffect(backTo) { val b = backTo; if (b != null) { lastBack = b; delay(7000); backTo = null } }
    val jumpTo: (Int) -> Unit = { p ->
        val t = p.coerceIn(0, count - 1)
        if (abs(t - latestCurrent.value) >= 3) backTo = latestCurrent.value
        scope.launch { goTo(t) }
    }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubFrac by remember { mutableFloatStateOf(0f) }
    var scrubPage by remember { mutableIntStateOf(0) }
    var scrubFrom by remember { mutableIntStateOf(0) }
    var scrubThumb by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(scrubPage, scrubbing) {
        if (scrubbing) { delay(50); doc.render(scrubPage, 260)?.let { scrubThumb = it } } else scrubThumb = null
    }
    var overview by remember { mutableStateOf(false) }

    // Your pace: how long you really stay on a page, from the pages you turned one by one this session.
    val samples = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        var prev = latestCurrent.value
        var t0 = SystemClock.elapsedRealtime()
        snapshotFlow { latestCurrent.value }.collect { p ->
            val now = SystemClock.elapsedRealtime()
            if (p == prev + 1) {
                val dwell = (now - t0) / 1000f
                if (dwell in 4f..240f) { samples.add(dwell); if (samples.size > 15) samples.removeAt(0) }
            }
            prev = p
            t0 = now
        }
    }
    val pace: Float = if (samples.size >= 3) samples.sorted()[samples.size / 2] else vm.paceSec
    DisposableEffect(Unit) {
        onDispose {
            if (samples.size >= 5) {
                val m = samples.sorted()[samples.size / 2]
                vm.updatePace(if (vm.paceSec > 0f) 0.6f * vm.paceSec + 0.4f * m else m)
            }
        }
    }
    val pagesLeft = (count - 1 - current).coerceAtLeast(0)
    val pct = if (count > 1) (current + 1) * 100 / count else 100
    val eta: String = when {
        pagesLeft == 0 -> "Last page"
        pace > 0f -> {
            val mins = pagesLeft * pace / 60f
            if (mins < 1f) "under a minute left"
            else if (mins < 60f) "~${mins.roundToInt()} min left"
            else "~${(mins / 60f).toInt()} h ${(mins % 60f).roundToInt()} min left"
        }
        pagesLeft == 1 -> "1 page left"
        else -> "$pagesLeft pages left"
    }
    val prevMark = marks.lastOrNull { it < current }
    val nextMark = marks.firstOrNull { it > current }

    // Focus timer: a quiet countdown that taps you on the shoulder when the session is over.
    val view = LocalView.current
    var timerEnd by remember { mutableLongStateOf(0L) }
    var timerLeft by remember { mutableIntStateOf(0) }
    var timerStartPage by remember { mutableIntStateOf(0) }
    var timerMenu by remember { mutableStateOf(false) }
    var timerDone by remember { mutableStateOf<Int?>(null) }
    val startTimer: (Int) -> Unit = { mins ->
        timerStartPage = latestCurrent.value
        timerLeft = mins * 60
        timerEnd = SystemClock.elapsedRealtime() + mins * 60_000L
    }
    LaunchedEffect(timerEnd) {
        if (timerEnd > 0L) {
            while (true) {
                val l = ((timerEnd - SystemClock.elapsedRealtime() + 999L) / 1000L).toInt()
                if (l <= 0) break
                timerLeft = l
                delay(500)
            }
            timerLeft = 0
            timerDone = (latestCurrent.value - timerStartPage).coerceAtLeast(0)
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            timerEnd = 0L
        }
    }

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
        // While the top bar is showing, the pages start exactly below it; when it hides they use the full height again.
        val barPx by animateFloatAsState(if (chrome) with(density) { 56.dp.toPx() } else 0f, tween(180), label = "topBar")
        val topPadPx = topInset + barPx
        val areaHpx = (constraints.maxHeight - topPadPx - bottomInset).coerceAtLeast(1f)
        zoom.viewW = viewWpx
        zoom.areaH = areaHpx
        val topDp = with(density) { topPadPx.toDp() }
        val bottomDp = with(density) { bottomInset.toDp() }
        val baseW = viewWpx.toInt().coerceAtLeast(1)

        if (paged) PagedPages(
            doc, count, ratios, pagerState, invert,
            topInsetDp = with(density) { topInset.toDp() }, bottomInsetDp = bottomDp, chrome = chrome,
            onTap = { chrome = !chrome; badgeTap++ },
            onZoomed = { z -> if (z) chrome = false }
        ) else Box(
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
                        onTap = { chrome = !chrome; badgeTap++ },
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

        // The badge rides the right edge and follows your position in the document, like a scroll thumb.
        val badgeH = with(density) { 40.dp.toPx() }
        val badgeMargin = with(density) { 8.dp.toPx() }
        val progress by remember(paged) {
            derivedStateOf {
                if (paged) {
                    if (count > 1) ((pagerState.currentPage + pagerState.currentPageOffsetFraction) / (count - 1)).coerceIn(0f, 1f) else 0f
                } else {
                    val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                    val frac = if (first != null && first.size > 0) (-first.offset.toFloat() / first.size).coerceIn(0f, 1f) else 0f
                    if (count > 1) ((listState.firstVisibleItemIndex + frac) / (count - 1)).coerceIn(0f, 1f) else 0f
                }
            }
        }
        val trackLo = topPadPx + badgeMargin
        val trackHi = (constraints.maxHeight - bottomInset - barPx - badgeH - badgeMargin).coerceAtLeast(trackLo)
        val track by rememberUpdatedState((trackHi - trackLo).coerceAtLeast(1f))
        AnimatedVisibility(
            badge, Modifier.align(Alignment.TopEnd),
            enter = fadeIn(tween(120)) + slideInHorizontally(tween(180)) { it },
            exit = fadeOut(tween(260)) + slideOutHorizontally(tween(260)) { it }
        ) {
            val shownProgress = dragP ?: progress
            val shownPage = dragP?.let { (it * (count - 1)).roundToInt() } ?: current
            Row(
                Modifier
                    .offset { IntOffset(0, (trackLo + (trackHi - trackLo) * shownProgress).toInt()) }
                    .height(40.dp)
                    .background(Color(0xEB202124), RoundedCornerShape(topStart = 20.dp, bottomStart = 20.dp))
                    // Grab the badge and drag it for fast scrolling through the whole document.
                    .pointerInput(count) {
                        detectVerticalDragGestures(
                            onDragStart = { dragP = progress },
                            onDragEnd = { dragP = null },
                            onDragCancel = { dragP = null },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                val p = ((dragP ?: progress) + dy / track).coerceIn(0f, 1f)
                                dragP = p
                                val page = (p * (count - 1)).roundToInt().coerceIn(0, count - 1)
                                if (page != current) scope.launch { goTo(page) }
                            }
                        )
                    }
                    .padding(start = 16.dp, end = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${shownPage + 1}", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                Text(" / $count", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
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
                IconButton(onClick = { settings = true }) { Icon(Icons.Rounded.Tune, "Reading settings", tint = if (invert || paged) pal.accent else pal.ink) }
                Box {
                    IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.ink) }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        val me = { vm.pdfs.value.firstOrNull { it.id == pdfId } }
                        MenuItem("Bookmarks", Icons.Rounded.FormatListBulleted) { more = false; sheet = true }
                        MenuItem("Go to page…", Icons.Rounded.Tag) { more = false; jump = true }
                        HorizontalDivider(color = pal.line)
                        MenuItem("Share…", Icons.Rounded.Share) { more = false; me()?.let { vm.shareReq = ShareReq(it, ShareKind.Share) } }
                        MenuItem("Open with…", Icons.Rounded.OpenInNew) { more = false; me()?.let { vm.shareReq = ShareReq(it, ShareKind.OpenIn) } }
                        MenuItem("Print", Icons.Rounded.Print) { more = false; me()?.let { vm.shareReq = ShareReq(it, ShareKind.Print) } }
                    }
                }
            }
        }

        AnimatedVisibility(chrome, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Column(Modifier.fillMaxWidth().background(pal.surface.copy(alpha = 0.97f)).navigationBarsPadding()) {
                ReaderScrubber(
                    fraction = if (scrubbing) scrubFrac else (if (count > 1) current / (count - 1).toFloat() else 0f),
                    count = count, marks = marks,
                    onStart = { scrubbing = true; scrubFrom = latestCurrent.value; scrubPage = latestCurrent.value },
                    onScrub = { f, p -> scrubFrac = f; scrubPage = p; scope.launch { goTo(p) } },
                    onEnd = { scrubbing = false; if (abs(latestCurrent.value - scrubFrom) >= 3) backTo = scrubFrom },
                    modifier = Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp)
                )
                Row(Modifier.fillMaxWidth().height(46.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { jump = true }) {
                        Text("${current + 1} / $count", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
                        Text("$pct% · $eta", fontSize = 11.sp, color = pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (marks.isNotEmpty()) {
                        Row(Modifier.clip(RoundedCornerShape(50)).background(pal.chip), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { prevMark?.let { jumpTo(it) } }, enabled = prevMark != null, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowLeft, "Previous bookmark", tint = if (prevMark != null) pal.ink else pal.soft.copy(alpha = 0.4f))
                            }
                            Icon(Icons.Rounded.Bookmark, null, tint = pal.accent, modifier = Modifier.size(14.dp))
                            IconButton(onClick = { nextMark?.let { jumpTo(it) } }, enabled = nextMark != null, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowRight, "Next bookmark", tint = if (nextMark != null) pal.ink else pal.soft.copy(alpha = 0.4f))
                            }
                        }
                    }
                    IconButton(onClick = { overview = true }, modifier = Modifier.size(42.dp)) { Icon(Icons.Rounded.GridView, "All pages", tint = pal.ink) }
                    Box {
                        Row(
                            Modifier.clip(RoundedCornerShape(50)).clickable { timerMenu = true }.padding(horizontal = 10.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Timer, "Focus timer", tint = if (timerEnd > 0L) pal.accent else pal.ink, modifier = Modifier.size(22.dp))
                            if (timerEnd > 0L) {
                                Spacer(Modifier.width(4.dp))
                                Text(formatClock(timerLeft), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.accent)
                            }
                        }
                        DropdownMenu(expanded = timerMenu, onDismissRequest = { timerMenu = false }) {
                            if (timerEnd > 0L) {
                                MenuItem("Stop timer", Icons.Rounded.Close) { timerMenu = false; timerEnd = 0L; timerLeft = 0 }
                            } else {
                                MenuItem("Focus for 15 min", Icons.Rounded.Timer) { timerMenu = false; startTimer(15) }
                                MenuItem("Focus for 25 min", Icons.Rounded.Timer) { timerMenu = false; startTimer(25) }
                                MenuItem("Focus for 45 min", Icons.Rounded.Timer) { timerMenu = false; startTimer(45) }
                            }
                        }
                    }
                }
            }
        }

        // "Back to where you were": after a long jump, one tap returns.
        AnimatedVisibility(backTo != null && !scrubbing, Modifier.align(Alignment.BottomCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.navigationBarsPadding().padding(bottom = if (chrome) 96.dp else 24.dp)
                    .clip(RoundedCornerShape(50)).background(pal.ink)
                    .clickable { val t = backTo; backTo = null; if (t != null) scope.launch { goTo(t) } }
                    .padding(start = 14.dp, end = 18.dp, top = 11.dp, bottom = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.History, null, tint = pal.bg, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Back to page ${(backTo ?: lastBack) + 1}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = pal.bg)
            }
        }

        // The page you are about to land on, right above your finger while you scrub.
        if (scrubbing) {
            val bw = 120.dp
            val bx = (12.dp + (maxWidth - 24.dp) * scrubFrac - bw / 2).coerceIn(8.dp, maxWidth - bw - 8.dp)
            Column(
                Modifier.align(Alignment.BottomStart).navigationBarsPadding().padding(bottom = 96.dp).offset(x = bx).width(bw)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xF0202124)).padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val tb = scrubThumb
                Box(
                    Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(6.dp)).background(if (invert) Color(0xFF101010) else Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    if (tb != null) Image(tb.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = if (invert) InvertFilter else null)
                }
                Spacer(Modifier.height(6.dp))
                Text("Page ${scrubPage + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            }
        }
    }

    timerDone?.let { n ->
        AlertDialog(
            onDismissRequest = { timerDone = null }, containerColor = pal.surface,
            title = { Text("Focus session complete", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
            text = {
                Text((if (n > 0) "You moved through $n page" + (if (n == 1) "" else "s") + ". " else "") + "Rest your eyes and stretch for a few minutes. You earned it.")
            },
            confirmButton = { TextButton(onClick = { timerDone = null; startTimer(25) }) { Text("Another 25 min") } },
            dismissButton = { TextButton(onClick = { timerDone = null }) { Text("Done") } }
        )
    }

    if (overview) ModalBottomSheet(onDismissRequest = { overview = false }, containerColor = pal.surface) {
        val cols = 3
        val gridState = rememberLazyGridState(initialFirstVisibleItemIndex = ((current / cols) * cols).coerceAtLeast(0))
        Column(Modifier.navigationBarsPadding().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("All pages", Modifier.weight(1f), fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = pal.ink)
                Text("$count", fontSize = 13.sp, color = pal.soft)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols), state = gridState, modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(count) { i ->
                    val on = i == current
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(8.dp))
                                .background(if (invert) Color(0xFF101010) else Color.White)
                                .border(if (on) 3.dp else 1.dp, if (on) pal.accent else pal.line, RoundedCornerShape(8.dp))
                                .clickable { overview = false; jumpTo(i) }
                        ) {
                            GridThumb(doc, i, invert)
                            if (i in marks) Icon(Icons.Rounded.Bookmark, null, tint = pal.accent, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp))
                        }
                        Text(
                            "${i + 1}", fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (on) pal.accent else pal.soft, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }

    if (settings) ModalBottomSheet(onDismissRequest = { settings = false }, containerColor = pal.surface) {
        val auto = vm.brightness < 0f
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState())) {
            Text("Reading settings", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = pal.ink, modifier = Modifier.padding(bottom = 14.dp))

            SettingLabel("Page layout")
            Segmented(listOf("Scroll", "Pages"), if (paged) 1 else 0) { vm.updatePageMode(it == 1) }
            Text(
                if (paged) "Swipe sideways, one page at a time. Double-tap to zoom." else "Keep scrolling down through the whole document.",
                fontSize = 12.sp, color = pal.soft, modifier = Modifier.padding(top = 6.dp)
            )

            SettingLabel("Appearance")
            SettingSwitch("Night reading", "Dark pages for reading in the dark", invert) { vm.updateInvert(it) }

            SettingLabel("Brightness")
            SettingSwitch("Automatic", "Follow your phone's brightness", auto) { vm.updateBrightness(if (it) -1f else 0.6f) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.BrightnessLow, null, tint = pal.soft, modifier = Modifier.size(20.dp))
                Slider(
                    value = if (auto) 0.6f else vm.brightness.coerceIn(0.05f, 1f), onValueChange = { vm.updateBrightness(it.coerceIn(0.05f, 1f)) },
                    valueRange = 0.05f..1f, enabled = !auto, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(thumbColor = pal.accent, activeTrackColor = pal.accent, inactiveTrackColor = pal.line)
                )
                Icon(Icons.Rounded.BrightnessHigh, null, tint = pal.soft, modifier = Modifier.size(20.dp))
            }

            SettingLabel("Screen")
            SettingSwitch("Keep screen on", "Stops the screen from turning off while you read", vm.keepOn) { vm.updateKeepOn(it) }
            Text("Orientation", fontSize = 14.sp, color = pal.ink, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))
            Segmented(listOf("Auto", "Portrait", "Landscape"), vm.orient) { vm.updateOrient(it) }

            if (!paged) {
                SettingLabel("Zoom")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { zoom.animateTo(scope, 1f, Offset(zoom.viewW / 2f, zoom.areaH / 2f)); settings = false },
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.FitScreen, null, tint = pal.ink, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Fit to width", color = pal.ink)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text("Your zoom is remembered for each PDF.", fontSize = 12.sp, color = pal.soft, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }, containerColor = pal.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Bookmarks", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = pal.ink, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (bms.isEmpty()) Text("Tap the bookmark icon at the top to mark a page.", fontSize = 14.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            bms.forEach { b ->
                Row(Modifier.fillMaxWidth().clickable { jumpTo(b.page); sheet = false }.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    txt.toIntOrNull()?.let { n -> jumpTo(n - 1) }
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

    /** Puts back a remembered zoom: [s] is the scale, [txFrac] the horizontal shift as a fraction of the screen width. */
    fun restore(s: Float, txFrac: Float) {
        scale = s.coerceIn(1f, MAX_SCALE)
        tx = (txFrac * viewW).coerceIn(minTx(scale), 0f)
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


@Composable
private fun SettingLabel(text: String) {
    val pal = LocalPal.current
    Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = pal.soft, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
}

@Composable
private fun SettingSwitch(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = pal.ink)
            Text(sub, fontSize = 12.sp, color = pal.soft)
        }
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = pal.accent, checkedThumbColor = Color.White, uncheckedTrackColor = pal.line, uncheckedBorderColor = pal.line)
        )
    }
}

/** A small segmented control: one option is always selected. */
@Composable
private fun Segmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(pal.line.copy(alpha = 0.5f)).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (on) pal.accent else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) Color.White else pal.ink) }
        }
    }
}

/** "Pages" layout: one page per screen, swipe sideways. Pinch or double-tap to zoom a page; swiping resumes once you zoom back out. */
@Composable
private fun PagedPages(
    doc: PdfDoc, count: Int, ratios: List<Float>, pagerState: PagerState, invert: Boolean,
    topInsetDp: Dp, bottomInsetDp: Dp, chrome: Boolean, onTap: () -> Unit, onZoomed: (Boolean) -> Unit
) {
    var zoomed by remember { mutableStateOf(false) }
    HorizontalPager(
        state = pagerState,
        modifier = Modifier.fillMaxSize().padding(top = topInsetDp, bottom = bottomInsetDp),
        userScrollEnabled = !zoomed,
        pageSpacing = 12.dp,
        key = { it }
    ) { page ->
        PagerPage(
            doc, page, ratios.getOrElse(page) { 1.414f }, invert, chrome, pagerState.currentPage == page, onTap,
            onZoomed = { z -> zoomed = z; onZoomed(z) }
        )
    }
}

@Composable
private fun PagerPage(doc: PdfDoc, page: Int, ratio: Float, invert: Boolean, chrome: Boolean, active: Boolean, onTap: () -> Unit, onZoomed: (Boolean) -> Unit) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds()) {
        val areaW = constraints.maxWidth.toFloat()
        val areaH = constraints.maxHeight.toFloat()
        val barPx = with(density) { 56.dp.toPx() }
        // The whole page is always visible: it fits the screen (width or height, whichever is tighter).
        val fw = minOf(areaW, areaH / ratio).coerceAtLeast(1f)
        val fh = fw * ratio
        var scale by remember { mutableFloatStateOf(1f) }
        var tx by remember { mutableFloatStateOf(0f) }
        var ty by remember { mutableFloatStateOf(0f) }
        var tile by remember { mutableStateOf<PageTile?>(null) }
        // With the bars showing, the page shrinks a little so it sits between them (a smooth transform: nothing is re-rendered).
        val k by animateFloatAsState(
            if (chrome && scale <= 1.001f) minOf(1f, ((areaH - 2f * barPx) / fh).coerceAtLeast(0.4f)) else 1f,
            tween(180), label = "fitK"
        )

        fun clampT() {
            val mx = ((fw * scale - areaW) / 2f).coerceAtLeast(0f)
            val my = ((fh * scale - areaH) / 2f).coerceAtLeast(0f)
            tx = tx.coerceIn(-mx, mx); ty = ty.coerceIn(-my, my)
        }

        fun zoomTo(target: Float, focal: Offset) {
            val cx = areaW / 2f; val cy = areaH / 2f
            val s0 = scale
            val qx = (focal.x - cx - tx) / s0; val qy = (focal.y - cy - ty) / s0
            scope.launch {
                animate(s0, target, animationSpec = tween(280, easing = FastOutSlowInEasing)) { v, _ ->
                    scale = v
                    tx = focal.x - cx - qx * v
                    ty = focal.y - cy - qy * v
                    clampT()
                }
            }
        }

        LaunchedEffect(active) { if (!active && scale != 1f) { scale = 1f; tx = 0f; ty = 0f; tile = null } }
        LaunchedEffect(scale > 1.001f, active) { if (active) onZoomed(scale > 1.001f) }
        // Zoomed in: the visible part is re-rendered at full density so text stays sharp.
        LaunchedEffect(doc, page, active, fw, fh, areaW, areaH) {
            snapshotFlow { Triple(scale, tx, ty) }.debounce(170).collectLatest { (s, x, y) ->
                if (s <= TILE_FROM || !active) { tile = null; return@collectLatest }
                val lx0 = ((-areaW / 2f - x) / s + fw / 2f).coerceIn(0f, fw)
                val lx1 = ((areaW / 2f - x) / s + fw / 2f).coerceIn(0f, fw)
                val ly0 = ((-areaH / 2f - y) / s + fh / 2f).coerceIn(0f, fh)
                val ly1 = ((areaH / 2f - y) / s + fh / 2f).coerceIn(0f, fh)
                if (lx1 - lx0 < 8f || ly1 - ly0 < 8f) return@collectLatest
                val mx = (lx1 - lx0) * 0.15f; val my = (ly1 - ly0) * 0.15f
                val l = ((lx0 - mx) / fw).coerceIn(0f, 1f); val r = ((lx1 + mx) / fw).coerceIn(0f, 1f)
                val t = ((ly0 - my) / fh).coerceIn(0f, 1f); val b = ((ly1 + my) / fh).coerceIn(0f, 1f)
                val have = tile
                if (have != null && have.covers(l, t, r, b) && have.scale >= s * 0.9f) return@collectLatest
                val bmp = doc.renderRegion(page, fw * s, l, t, r, b, 5_000_000) ?: return@collectLatest
                tile = PageTile(bmp, l, t, r, b, s)
            }
        }

        Box(
            Modifier.fillMaxSize()
                .pointerInput(fw, fh, areaW, areaH) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var pinching = false
                        var panning = false
                        var travelled = 0f
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                pinching = true
                                val zr = event.calculateZoom()
                                val pan = event.calculatePan()
                                val c = event.calculateCentroid(useCurrent = false)
                                if ((zr != 1f || pan != Offset.Zero) && c != Offset.Unspecified) {
                                    val s0 = scale
                                    val s1 = (s0 * zr).coerceIn(1f, ZoomState.MAX_SCALE)
                                    val cx = size.width / 2f; val cy = size.height / 2f
                                    val qx = (c.x - cx - tx) / s0; val qy = (c.y - cy - ty) / s0
                                    scale = s1
                                    tx = c.x + pan.x - cx - qx * s1
                                    ty = c.y + pan.y - cy - qy * s1
                                    clampT()
                                }
                                event.changes.forEach { it.consume() }
                            } else if (pinching) {
                                event.changes.forEach { it.consume() }
                            } else if (scale > 1.001f) {
                                val pan = event.calculatePan()
                                travelled += abs(pan.x) + abs(pan.y)
                                if (!panning && travelled > slop) panning = true
                                if (panning) {
                                    tx += pan.x; ty += pan.y; clampT()
                                    event.changes.forEach { it.consume() }
                                }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(fw, fh, areaW, areaH) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onDoubleTap = { p -> zoomTo(if (scale > 1.25f) 1f else DOUBLE_TAP_ZOOM, p) }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier.size(with(density) { fw.toDp() }, with(density) { fh.toDp() })
                    .graphicsLayer {
                        scaleX = scale * k; scaleY = scale * k
                        translationX = tx; translationY = ty
                    }
            ) {
                PageView(doc, page, ratio, fw.toInt().coerceAtLeast(1), if (active) 2f else 1f, invert, tile)
            }
        }
    }
}

private fun formatClock(sec: Int): String = "%d:%02d".format(sec / 60, sec % 60)

/**
 * The page scrubber: tap or drag anywhere on the track. Yellow ticks mark your bookmarks.
 * [onScrub] gets the position (0..1) and the page it points to.
 */
@Composable
private fun ReaderScrubber(
    fraction: Float, count: Int, marks: List<Int>,
    onStart: () -> Unit, onScrub: (Float, Int) -> Unit, onEnd: () -> Unit, modifier: Modifier = Modifier
) {
    val pal = LocalPal.current
    val startNow by rememberUpdatedState(onStart)
    val scrubNow by rememberUpdatedState(onScrub)
    val endNow by rememberUpdatedState(onEnd)
    Box(
        modifier.pointerInput(count) {
            val pad = 12.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                fun at(x: Float) {
                    val span = (size.width - 2 * pad).coerceAtLeast(1f)
                    val f = ((x - pad) / span).coerceIn(0f, 1f)
                    scrubNow(f, (f * (count - 1)).roundToInt().coerceIn(0, (count - 1).coerceAtLeast(0)))
                }
                startNow()
                at(down.position.x)
                do {
                    val event = awaitPointerEvent()
                    val c = event.changes.firstOrNull { it.id == down.id }
                    if (c != null && c.pressed) { at(c.position.x); c.consume() }
                } while (event.changes.any { it.pressed })
                endNow()
            }
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 12.dp.toPx()
            val span = (size.width - 2 * pad).coerceAtLeast(1f)
            val cy = size.height / 2f
            val th = 4.dp.toPx()
            drawRoundRect(pal.line, Offset(pad, cy - th / 2f), Size(span, th), CornerRadius(th / 2f))
            val x = pad + span * fraction.coerceIn(0f, 1f)
            drawRoundRect(pal.accent, Offset(pad, cy - th / 2f), Size((x - pad).coerceAtLeast(0f), th), CornerRadius(th / 2f))
            val tickW = 3.dp.toPx()
            marks.forEach { p ->
                val mx = pad + span * (if (count > 1) p / (count - 1).toFloat() else 0f)
                drawRoundRect(pal.hi, Offset(mx - tickW / 2f, cy - 9.dp.toPx()), Size(tickW, 18.dp.toPx()), CornerRadius(tickW / 2f))
            }
            drawCircle(Color.Black.copy(alpha = 0.15f), 12.dp.toPx(), Offset(x, cy + 1.dp.toPx()))
            drawCircle(pal.accent, 10.dp.toPx(), Offset(x, cy))
            drawCircle(Color.White, 4.dp.toPx(), Offset(x, cy))
        }
    }
}

/** One page in the all-pages overview, drawn small and only when it scrolls into view. */
@Composable
private fun GridThumb(doc: PdfDoc, index: Int, invert: Boolean) {
    var bmp by remember(doc, index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(doc, index) { bmp = doc.render(index, 240) }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, colorFilter = if (invert) InvertFilter else null)
}
