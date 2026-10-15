@file:OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)

package com.pdflib

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    var zoom by remember { mutableFloatStateOf(1f) }
    var chrome by remember { mutableStateOf(true) }
    var sheet by remember { mutableStateOf(false) }
    var jump by remember { mutableStateOf(false) }
    val invert = vm.invert

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

    BoxWithConstraints(
        Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures(
                onTap = { chrome = !chrome },
                onDoubleTap = { zoom = if (zoom < 1.2f) 1.7f else if (zoom < 1.9f) 2.2f else 1f }
            )
        }
    ) {
        val pageW = (constraints.maxWidth * zoom).toInt()
        val listWidth = this.maxWidth * zoom
        Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(listWidth).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = WindowInsets.systemBars.asPaddingValues()
            ) {
                items(count, key = { it }) { i -> PageView(doc, i, ratios[i], pageW, invert) }
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

@Composable
private fun PageView(doc: PdfDoc, index: Int, ratio: Float, widthPx: Int, invert: Boolean) {
    val bmp by produceState<Bitmap?>(null, doc, index, widthPx) { value = doc.render(index, widthPx) }
    Box(Modifier.fillMaxWidth().aspectRatio(1f / ratio).background(if (invert) Color(0xFF101010) else Color.White)) {
        val b = bmp
        if (b != null) Image(
            b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds,
            colorFilter = if (invert) InvertFilter else null
        )
    }
}
