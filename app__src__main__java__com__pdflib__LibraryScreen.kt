package com.pdflib

import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.ui.draw.shadow
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import kotlin.math.abs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/** Multi-select PDF picker whose grants survive a restart. */
private class PickPdfs : ActivityResultContracts.OpenMultipleDocuments() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
}

private sealed interface Dlg {
    object New : Dlg
    class Edit(val f: FolderEntity) : Dlg
    class Delete(val f: FolderEntity) : Dlg
}

@Composable
fun LibraryScreen(vm: LibraryVm) {
    val pal = LocalPal.current
    val widthDp = LocalConfiguration.current.screenWidthDp
    val cols = if (widthDp >= 840) 4 else if (widthDp >= 600) 3 else 2
    val folders by vm.folders.collectAsState()
    val pdfs by vm.pdfs.collectAsState()
    val currentId = vm.stack.lastOrNull()
    val byId = remember(folders) { folders.associateBy { it.id } }
    val current = currentId?.let { byId[it] }
    val sortMode = vm.sortMode
    val subs = remember(folders, currentId, sortMode) { sortFolders(folders.filter { it.parentId == currentId }, sortMode) }
    val docs = remember(pdfs, currentId, sortMode) { sortPdfs(pdfs.filter { it.folderId == currentId }, sortMode) }

    // Hold-and-drag reordering (pinned rows stay on top; rows only move inside their own block).
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val reorder = remember {
        DragReorder(
            vm.libState, scope,
            edgePx = with(density) { 96.dp.toPx() }, maxSpeedPx = with(density) { 560.dp.toPx() }, slopPx = with(density) { 8.dp.toPx() }, sidePx = with(density) { 10.dp.toPx() },
            onPick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            onTick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )
    }
    reorder.resolve = { key ->
        if (vm.sortMode != SORT_CUSTOM || vm.statusFilter != 0) null
        else if (key is Long) {
            val i = docs.indexOfFirst { it.id == key }
            if (i < 0) null else {
                val pin = docs[i].pinned
                DragReorder.Group("p", docs.map { it.id }, docs.indexOfFirst { it.pinned == pin }, docs.indexOfLast { it.pinned == pin }) { slice ->
                    vm.reorderPdfs(slice.map { it as Long })
                }
            }
        } else if (key is String && key.startsWith("f") && key.drop(1).toLongOrNull() != null) {
            val i = subs.indexOfFirst { "f${it.id}" == key }
            if (i < 0) null else {
                val pin = subs[i].pinned
                DragReorder.Group("f", subs.map { "f${it.id}" }, subs.indexOfFirst { it.pinned == pin }, subs.indexOfLast { it.pinned == pin }) { slice ->
                    vm.reorderFolders(slice.map { (it as String).drop(1).toLong() })
                }
            }
        } else null
    }
    reorder.onLongPress = { k ->
        if (k is Long) {
            vm.toggleSel(k)
        } else if (k is String && k.startsWith("f")) {
            val fid = k.drop(1).toLongOrNull()
            if (fid != null) vm.toggleFolderSel(fid)
        }
    }
    val shownSubs = reorder.arrange("f", subs) { "f${it.id}" }
    val shownDocs = reorder.arrange("p", docs) { it.id }
    // Reading-status filter (All / Unread / In progress / Finished). Reordering is paused while it is on.
    val counts = remember(docs) {
        val c = IntArray(3)
        docs.forEach { c[it.status().ordinal]++ }
        c
    }
    val visibleDocs = if (vm.statusFilter == 0) shownDocs else shownDocs.filter { it.status().ordinal == vm.statusFilter - 1 }
    reorder.cols = { s -> if (s == "p") (if (vm.gridView) cols else 1) else (if (vm.foldersGrid) cols else 1) }
    reorder.cellKey = { s, i -> if (s == "p") visibleDocs.getOrNull(i)?.id else shownSubs.getOrNull(i)?.let { "f${it.id}" } }
    LaunchedEffect(subs, docs) {
        reorder.onSource("f", subs.map { "f${it.id}" })
        reorder.onSource("p", docs.map { it.id })
    }
    val stats = remember(pdfs, folders) { FolderStats(pdfs, folders) }
    // The three PDFs you read most recently: the first one is "Continue reading", swipe for the others.
    val recent3 = remember(pdfs) { pdfs.filter { it.lastOpened > 0 && !it.missing }.sortedByDescending { it.lastOpened }.take(3) }
    val paths = remember(folders) { folderPaths(folders) }
    val selecting = vm.selectedCount > 0
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val links by vm.links.collectAsState()
    val linkCount = remember(links, currentId) { links.count { it.target == currentId } }
    var pendingTree by remember { mutableStateOf<Uri?>(null) }
    var showLinks by remember { mutableStateOf(false) }
    val linkPicker = rememberLauncherForActivityResult(PickTree()) { u -> if (u != null) pendingTree = u }
    val fixPicker = rememberLauncherForActivityResult(PickTree()) { u -> if (u != null) vm.regrant(u) }
    val placeName = current?.name ?: "Library"
    val pickFiles = rememberLauncherForActivityResult(PickPdfs()) { l -> if (l.isNotEmpty()) vm.addFiles(l, currentId) }
    val onboarding = vm.loaded && vm.sources.isEmpty() && links.isEmpty() && pdfs.isEmpty() && currentId == null
    val openSearch: () -> Unit = { vm.searching = true }

    BackHandler(enabled = selecting || vm.stack.isNotEmpty()) { if (selecting) vm.clearSel() else vm.up() }
    LaunchedEffect(currentId) { vm.libState.scrollToItem(0) }
    LaunchedEffect(folders) { if (currentId != null && folders.isNotEmpty() && byId[currentId] == null) vm.goToFolder(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- top bar: logo + name + search, selection tools, or back + search inside a folder ----
            if (selecting) {
                var more by remember { mutableStateOf(false) }
                val selPdfs = vm.selection.toList()
                val selFolders = vm.folderSelection.toList()
                val total = selPdfs.size + selFolders.size
                val allSaved = selPdfs.all { id -> pdfs.firstOrNull { it.id == id }?.favorite == true } &&
                    selFolders.all { id -> byId[id]?.favorite == true }
                val allPinned = selPdfs.all { id -> pdfs.firstOrNull { it.id == id }?.pinned == true } &&
                    selFolders.all { id -> byId[id]?.pinned == true }
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.clearSel() }) { Icon(Icons.Rounded.Close, "Cancel", tint = pal.ink) }
                    Text("$total selected", fontFamily = Serif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        vm.clearSel(); vm.folderSelection.addAll(subs.map { it.id }); vm.selection.addAll(docs.map { it.id })
                    }) { Icon(Icons.Rounded.SelectAll, "Select all", tint = pal.ink) }
                    IconButton(onClick = {
                        vm.setFavorite(selPdfs, !allSaved); vm.setFoldersFavorite(selFolders, !allSaved); vm.clearSel()
                    }) { Icon(if (allSaved) Icons.Rounded.StarBorder else Icons.Rounded.Star, if (allSaved) "Remove from Saved" else "Save", tint = pal.hi) }
                    IconButton(onClick = { vm.pdfDialog = PdfDlg.Move(selPdfs, selFolders) }) { Icon(Icons.Rounded.FolderOpen, "Move", tint = pal.accent) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.ink) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            // Things you can do with the PDFs themselves come first.
                            if (selFolders.isEmpty()) {
                                MenuItem(if (selPdfs.size == 1) "Share…" else "Share ${selPdfs.size} PDFs…", Icons.Rounded.Share) {
                                    more = false; vm.shareSelected(selPdfs); vm.clearSel()
                                }
                                val allRead = selPdfs.all { id -> pdfs.firstOrNull { it.id == id }?.status() == ReadStatus.Finished }
                                MenuItem(if (allRead) "Mark as unread" else "Mark as read", if (allRead) Icons.Rounded.Replay else Icons.Rounded.TaskAlt) {
                                    more = false; vm.markRead(selPdfs, !allRead); vm.clearSel()
                                }
                                HorizontalDivider(color = pal.line)
                            }
                            if (total == 1) MenuItem(if (selFolders.isNotEmpty()) "Rename / colour" else "Rename", Icons.Rounded.Edit) {
                                more = false
                                if (selFolders.isNotEmpty()) {
                                    val fo = byId[selFolders.first()]
                                    vm.clearSel()
                                    if (fo != null) dlg = Dlg.Edit(fo)
                                } else {
                                    val one = pdfs.firstOrNull { it.id == selPdfs.first() }
                                    vm.clearSel()
                                    if (one != null) vm.pdfDialog = PdfDlg.Rename(one)
                                }
                            }
                            MenuItem(if (allPinned) "Unpin" else "Pin to top", Icons.Rounded.PushPin) {
                                more = false
                                vm.setPdfPinned(selPdfs, !allPinned); vm.setFoldersPinned(selFolders, !allPinned); vm.clearSel()
                            }
                            if (selFolders.isEmpty()) MenuItem("Copy to…", Icons.Rounded.ContentCopy) { more = false; vm.pdfDialog = PdfDlg.Copy(selPdfs) }
                            MenuItem("Remove from Shelf", Icons.Rounded.VisibilityOff) { more = false; vm.pdfDialog = PdfDlg.RemoveItems(selPdfs, selFolders) }
                            if (selFolders.isEmpty()) MenuItem("Move to Trash", Icons.Rounded.Delete, danger = true) {
                                more = false
                                vm.pdfDialog = PdfDlg.Delete(selPdfs, pdfs.firstOrNull { it.id == selPdfs.firstOrNull() }?.title() ?: "")
                            }
                        }
                    }
                }
            } else if (current != null) {
                AppHeader(onSearch = openSearch, onBack = { vm.up() }, onRefresh = { vm.rescan() }, refreshing = vm.scanning)
            } else {
                AppHeader(if (onboarding) null else openSearch, onRefresh = if (onboarding) null else ({ vm.rescan() }), refreshing = vm.scanning)
            }

            LazyColumn(state = vm.libState, modifier = Modifier.weight(1f).reorderGestures(reorder, canDrag = !selecting), contentPadding = PaddingValues(bottom = 110.dp)) {
                // ---- header ----
                item(key = "head") {
                    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                        if (current == null) {
                            Text("Library", fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = pal.ink)
                            if (!onboarding) {
                                val n = pdfs.size; val f = folders.size
                                Text("$n PDF" + (if (n == 1) "" else "s") + " · $f folder" + (if (f == 1) "" else "s"), fontSize = 13.sp, color = pal.soft)
                            }
                        } else {
                            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                                Text("Library", fontSize = 12.sp, color = pal.soft, modifier = Modifier.clickable { vm.goToFolder(null) }.padding(vertical = 4.dp))
                                vm.stack.forEachIndexed { i, id ->
                                    Icon(Icons.Rounded.ChevronRight, null, tint = pal.soft, modifier = Modifier.size(16.dp))
                                    val last = i == vm.stack.lastIndex
                                    Text(byId[id]?.name ?: "", fontSize = 12.sp, color = if (last) pal.ink else pal.soft, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.clickable(enabled = !last) { vm.toBreadcrumb(i) }.padding(vertical = 4.dp))
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(current.name, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 34.sp, color = pal.ink)
                            Text(stats.subtitle(current.id), fontSize = 13.sp, color = pal.soft)
                        }
                        if (!onboarding && linkCount > 0) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(pal.chip).clickable { showLinks = true }.padding(start = 12.dp, end = 8.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.Link, null, tint = pal.accent, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("$linkCount linked phone folder" + (if (linkCount == 1) "" else "s"), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
                                Icon(Icons.Rounded.ChevronRight, null, tint = pal.soft, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                if (vm.scanning) item(key = "scan") {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = pal.accent)
                        Spacer(Modifier.width(10.dp))
                        Text("Looking through your folders · ${vm.scanFound} PDFs found", fontSize = 12.sp, color = pal.soft)
                    }
                }

                if (onboarding) {
                    item(key = "onboard") {
                        Column(Modifier.padding(horizontal = 24.dp, vertical = 40.dp)) {
                            Text("Your PDFs,\nin order.", fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 40.sp, lineHeight = 44.sp, color = pal.ink)
                            Spacer(Modifier.height(16.dp))
                            Text("Link a folder from your phone. Keep its folders as they are, or bring in just the files. Shelf reads them right where they are. Nothing is copied or uploaded.", fontSize = 15.sp, lineHeight = 22.sp, color = pal.soft)
                            Spacer(Modifier.height(28.dp))
                            Button(onClick = { linkPicker.launch(null) }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = pal.accent, contentColor = pal.onAccent),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                                Text("Link a phone folder", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                            // A softer way in, for people who just have a few files.
                            TextButton(onClick = { pickFiles.launch(arrayOf("application/pdf")) }) {
                                Icon(Icons.Rounded.NoteAdd, null, tint = pal.soft, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("or add a few PDF files", color = pal.soft, fontSize = 14.sp)
                            }
                        }
                    }
                } else {
                    // ---- continue reading ----
                    if (current == null && !selecting && recent3.isNotEmpty()) item(key = "resume") {
                        ResumeCarousel(recent3, paths) { vm.openReader(it) }
                    }

                    // ---- folders ----
                    if (subs.isNotEmpty()) {
                        item(key = "fl") {
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Overline("Folders", Modifier.weight(1f))
                                if (docs.size < 2 && subs.size > 1) SortMenu(sortMode) { vm.updateSort(it) }
                                Spacer(Modifier.width(6.dp))
                                ViewToggle(vm.foldersGrid) { vm.updateFoldersGrid(it) }
                            }
                        }
                        if (vm.foldersGrid) {
                            val frows = shownSubs.chunked(cols)
                            items(frows.size, key = { gridRowKey("f", it) }) { ri ->
                                val rowItems = frows.getOrNull(ri) ?: return@items
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                                        .then(if (rowItems.any { "f${it.id}" == reorder.draggedKey }) Modifier.zIndex(1f) else Modifier),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    rowItems.forEachIndexed { ci, f ->
                                        key(f.id) {
                                            FolderGridCard(f, stats.subtitle(f.id),
                                                onClick = { if (selecting) vm.toggleFolderSel(f.id) else vm.enter(f.id) },
                                                onEdit = { dlg = Dlg.Edit(f) }, onMove = { vm.pdfDialog = PdfDlg.Move(emptyList(), listOf(f.id)) }, onDelete = { dlg = Dlg.Delete(f) },
                                                onPin = { vm.setFolderPinned(f, !f.pinned) },
                                                modifier = Modifier.weight(1f).reorderCell(reorder, "f${f.id}", ri * cols + ci, cols, pal.surface),
                                                selecting = selecting, selected = f.id in vm.folderSelection,
                                                onFav = { vm.setFoldersFavorite(listOf(f.id), !f.favorite) })
                                        }
                                    }
                                    repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        } else
                        items(shownSubs, key = { "f${it.id}" }) { f ->
                            FolderRow(f, stats.subtitle(f.id),
                                onClick = { if (selecting) vm.toggleFolderSel(f.id) else vm.enter(f.id) },
                                onEdit = { dlg = Dlg.Edit(f) }, onMove = { vm.pdfDialog = PdfDlg.Move(emptyList(), listOf(f.id)) }, onDelete = { dlg = Dlg.Delete(f) },
                                onPin = { vm.setFolderPinned(f, !f.pinned) },
                                modifier = reorderItem(reorder, "f${f.id}", pal.surface),
                                selecting = selecting, selected = f.id in vm.folderSelection,
                                onFav = { vm.setFoldersFavorite(listOf(f.id), !f.favorite) })
                        }
                    }

                    // ---- pdfs ----
                    if (docs.isNotEmpty()) {
                        item(key = "pl") {
                            val label = if (current == null && subs.isNotEmpty()) "Unsorted" else "PDFs"
                            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Overline(if (vm.statusFilter == 0) "$label · ${docs.size}" else "$label · ${visibleDocs.size} of ${docs.size}", Modifier.weight(1f))
                                if (docs.size > 1) SortMenu(sortMode) { vm.updateSort(it) }
                                Spacer(Modifier.width(6.dp))
                                ViewToggle(vm.gridView) { vm.updateGrid(it) }
                            }
                        }
                        if (docs.size >= 2) item(key = "chips") {
                            StatusChips(vm.statusFilter, docs.size, counts) { vm.statusFilter = it }
                        }
                        if (visibleDocs.isEmpty()) item(key = "nomatch") {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    when (vm.statusFilter) { 1 -> "Nothing unread here"; 2 -> "Nothing in progress here"; else -> "Nothing finished here yet" },
                                    fontFamily = Serif, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.ink
                                )
                                Spacer(Modifier.height(4.dp))
                                Text("Try another filter to see the rest of this folder.", fontSize = 13.sp, color = pal.soft)
                                TextButton(onClick = { vm.statusFilter = 0 }) { Text("Show all", color = pal.accent, fontWeight = FontWeight.SemiBold) }
                            }
                        }
                        if (vm.gridView) {
                            val prows = visibleDocs.chunked(cols)
                            items(prows.size, key = { gridRowKey("p", it) }) { ri ->
                                val rowItems = prows.getOrNull(ri) ?: return@items
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 10.dp)
                                        .then(if (rowItems.any { it.id == reorder.draggedKey }) Modifier.zIndex(1f) else Modifier),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    rowItems.forEachIndexed { ci, p ->
                                        key(p.id) {
                                            PdfGridCard(
                                                p, selecting, p.id in vm.selection,
                                                onClick = { if (selecting) vm.toggleSel(p.id) else vm.openReader(p.id) },
                                                onLong = {},
                                                onFav = { vm.setFavorite(listOf(p.id), !p.favorite) },
                                                onMore = { a -> vm.rowAction(p, a) },
                                                modifier = Modifier.weight(1f).reorderCell(reorder, p.id, ri * cols + ci, cols, pal.surface)
                                            )
                                        }
                                    }
                                    repeat(cols - rowItems.size) { Spacer(Modifier.weight(1f)) }
                                }
                            }
                        } else {
                            items(visibleDocs, key = { it.id }) { p ->
                                PdfRow(p, null, selecting, p.id in vm.selection,
                                    onClick = { if (selecting) vm.toggleSel(p.id) else vm.openReader(p.id) },
                                    onFav = { vm.setFavorite(listOf(p.id), !p.favorite) },
                                    onMore = { a -> vm.rowAction(p, a) },
                                    modifier = reorderItem(reorder, p.id, pal.surface))
                            }
                        }
                    }

                    if (current != null && subs.isEmpty() && docs.isEmpty()) item(key = "empty") {
                        Hint("Nothing in here yet", "Tap + at the bottom right to add PDFs, link a phone folder, or create a subfolder.")
                    }
                }
            }
        }

        // Long library: a quick way back to the top (also: tap the Library tab again).
        val showTop by remember { derivedStateOf { vm.libState.firstVisibleItemIndex > 8 } }
        AnimatedVisibility(
            visible = showTop && !selecting && !onboarding,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 92.dp),
            enter = fadeIn(tween(160)) + scaleIn(tween(160), initialScale = 0.8f),
            exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.8f)
        ) {
            Box(
                Modifier.size(44.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(pal.surface)
                    .clickable { scope.launch { vm.libState.animateScrollToItem(0) } },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.KeyboardArrowUp, "Back to top", tint = pal.ink) }
        }

        if (!selecting && !onboarding) {
            var add by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                FloatingActionButton(
                    onClick = { add = true }, containerColor = pal.accent, contentColor = pal.onAccent, shape = RoundedCornerShape(18.dp)
                ) { Icon(Icons.Rounded.Add, "Add") }
                DropdownMenu(expanded = add, onDismissRequest = { add = false }) {
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null, tint = pal.accent) },
                        text = { Text("New folder") }, onClick = { add = false; dlg = Dlg.New }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.NoteAdd, null, tint = pal.accent) },
                        text = { Text("Add PDFs from phone") }, onClick = { add = false; pickFiles.launch(arrayOf("application/pdf")) }
                    )
                    DropdownMenuItem(
                        leadingIcon = { Icon(Icons.Rounded.Link, null, tint = pal.accent) },
                        text = { Text("Link a phone folder") }, onClick = { add = false; linkPicker.launch(null) }
                    )
                }
            }
        }
    }

    pendingTree?.let { t -> LinkModeSheet(vm, t, currentId, placeName) { pendingTree = null } }
    if (showLinks) LinksSheet(
        vm, currentId, placeName,
        onFix = { fixPicker.launch(null) },
        onAdd = { showLinks = false; linkPicker.launch(null) },
        onClose = { showLinks = false }
    )

    when (val d = dlg) {
        is Dlg.New -> FolderDialog(
            "New folder", "", stats.subCount(currentId ?: -1L) % FolderTones.size, "Create", { dlg = null },
            check = { t -> vm.folderNameProblem(currentId, t, null) }
        ) { n, c -> vm.createFolder(n, c, currentId); dlg = null }
        is Dlg.Edit -> FolderDialog(
            "Edit folder", d.f.name, d.f.color, "Save", { dlg = null },
            check = { t -> vm.folderNameProblem(d.f.parentId, t, d.f.id) }
        ) { n, c -> vm.updateFolder(d.f.id, n, c); dlg = null }
        is Dlg.Delete -> {
            val n = stats.pdfCount(d.f.id)
            val pl = if (n == 1) "" else "s"
            AlertDialog(
                onDismissRequest = { dlg = null }, containerColor = pal.surface,
                title = { Text("Remove “${d.f.name}”?", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        (if (n > 0) "The folder and its $n PDF$pl leave your Library. Restore them any time in Settings › Removed folders."
                        else "The folder leaves your Library. Restore it any time in Settings › Removed folders.") +
                            " Files on your phone are never touched."
                    )
                },
                confirmButton = { TextButton(onClick = { vm.removeItems(emptyList(), listOf(d.f.id)); dlg = null }) { Text("Remove", color = pal.accent) } },
                dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancel") } }
            )
        }
        null -> {}
    }
}

private fun timeAgo(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60000
    return when {
        m < 1 -> "Just now"
        m < 60 -> "$m min ago"
        m < 24 * 60 -> "${m / 60} h ago"
        m < 48 * 60 -> "Yesterday"
        m < 30 * 24 * 60 -> "${m / (24 * 60)} days ago"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(t))
    }
}

/** The one thing you most likely want when you open Shelf: pick up exactly where you stopped. */
private val ResumeCardHeight = 164.dp

/** Up to three recently read PDFs side by side: swipe between them, dots show where you are (tap a dot to jump). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResumeCarousel(items: List<PdfEntity>, paths: Map<Long, String>, onOpen: (Long) -> Unit) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(pageCount = { items.size })
    // Reading something new puts it first: slide back to it.
    LaunchedEffect(items.firstOrNull()?.id) { if (pager.currentPage != 0) pager.animateScrollToPage(0) }
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp)) {
        HorizontalPager(
            state = pager, modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp), pageSpacing = 10.dp
        ) { i ->
            val p = items.getOrNull(i) ?: return@HorizontalPager
            ResumeCard(
                p, p.folderId?.let { paths[it] }, if (i == 0) "Continue reading" else "Recently read",
                // The neighbouring cards sit a little back, so the one in front feels like it is leading.
                Modifier.graphicsLayer {
                    val o = abs((pager.currentPage - i) + pager.currentPageOffsetFraction).coerceIn(0f, 1f)
                    val sc = 1f - 0.05f * o
                    scaleX = sc; scaleY = sc; alpha = 1f - 0.35f * o
                }
            ) { onOpen(p.id) }
        }
        if (items.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                repeat(items.size) { i ->
                    val on = pager.currentPage == i
                    val w by animateDpAsState(if (on) 20.dp else 6.dp, tween(220), label = "dotWidth")
                    val c by animateColorAsState(if (on) pal.accent else pal.soft.copy(alpha = 0.35f), tween(220), label = "dotColor")
                    Box(Modifier.clickable { scope.launch { pager.animateScrollToPage(i) } }.padding(horizontal = 3.dp, vertical = 8.dp)) {
                        Box(Modifier.height(6.dp).width(w).clip(CircleShape).background(c))
                    }
                }
            }
        }
    }
}

@Composable
private fun ResumeCard(p: PdfEntity, place: String?, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(22.dp)
    val total = p.pageCount
    val page = (p.lastPage + 1).coerceAtLeast(1)
    val started = total > 0 && p.lastPage > 0
    val done = total > 0 && page >= total
    val frac = if (total > 0) (page.toFloat() / total).coerceIn(0f, 1f) else 0f
    Row(
        modifier.fillMaxWidth().height(ResumeCardHeight).clip(shape)
            .background(pal.surface).border(1.dp, pal.line, shape).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(p, Modifier.size(ThumbSlotLarge))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline(if (done && label == "Continue reading") "Last read" else label, color = pal.accent)
                Spacer(Modifier.weight(1f))
                Text(timeAgo(p.lastOpened), fontSize = 12.sp, color = pal.soft)
            }
            Spacer(Modifier.height(6.dp))
            Text(p.title(), fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 23.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = pal.ink)
            if (place != null) Text(place, fontSize = 12.sp, color = pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.height(12.dp))
            if (started) {
                ReadBar(frac)
                Spacer(Modifier.height(6.dp))
                Row {
                    Text("${(frac * 100).toInt()}%", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
                    Spacer(Modifier.weight(1f))
                    val left = total - page
                    Text(if (done) "Finished" else if (left == 1) "1 page left" else "$left pages left", fontSize = 12.sp, color = pal.soft)
                }
            } else Text("Just opened", fontSize = 12.sp, color = pal.soft)
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.size(44.dp).clip(CircleShape).background(pal.accent), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.PlayArrow, "Resume", tint = pal.onAccent)
        }
    }
}


/** All / Unread / In progress / Finished, with how many PDFs of this folder are in each. */
@Composable
private fun StatusChips(selected: Int, total: Int, counts: IntArray, onSelect: (Int) -> Unit) {
    val pal = LocalPal.current
    val labels = listOf("All" to total, "Unread" to counts[0], "In progress" to counts[1], "Finished" to counts[2])
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        labels.forEachIndexed { i, (label, n) ->
            val on = i == selected
            val dim = i != 0 && n == 0 && !on
            Row(
                Modifier.clip(RoundedCornerShape(50))
                    .background(if (on) pal.ink else Color.Transparent)
                    .border(1.dp, if (on) pal.ink else pal.line, RoundedCornerShape(50))
                    .clickable { onSelect(if (on) 0 else i) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, fontSize = 13.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) pal.bg else if (dim) pal.soft.copy(alpha = 0.55f) else pal.ink)
                Spacer(Modifier.width(6.dp))
                Text("$n", fontSize = 12.sp, color = if (on) pal.bg.copy(alpha = 0.7f) else pal.soft)
            }
        }
    }
}
