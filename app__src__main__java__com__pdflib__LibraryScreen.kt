package com.pdflib

import android.content.Context
import androidx.compose.foundation.shape.CircleShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
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
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    val folders by vm.folders.collectAsState()
    val pdfs by vm.pdfs.collectAsState()
    val currentId = vm.stack.lastOrNull()
    val byId = remember(folders) { folders.associateBy { it.id } }
    val current = currentId?.let { byId[it] }
    val subs = remember(folders, currentId) { arrangeFolders(folders.filter { it.parentId == currentId }) }
    val docs = remember(pdfs, currentId) { arrangePdfs(pdfs.filter { it.folderId == currentId }) }

    // Hold-and-drag reordering (pinned rows stay on top; rows only move inside their own block).
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val reorder = remember {
        DragReorder(
            vm.libState, scope,
            edgePx = with(density) { 96.dp.toPx() }, maxSpeedPx = with(density) { 560.dp.toPx() }, slopPx = with(density) { 8.dp.toPx() },
            onPick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
            onTick = { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
        )
    }
    reorder.resolve = { key ->
        if (key is Long) {
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
    LaunchedEffect(subs, docs) {
        reorder.onSource("f", subs.map { "f${it.id}" })
        reorder.onSource("p", docs.map { it.id })
    }
    val stats = remember(pdfs, folders) { FolderStats(pdfs, folders) }
    val resume = remember(pdfs) { pdfs.filter { it.lastOpened > 0 && !it.missing }.maxByOrNull { it.lastOpened } }
    val paths = remember(folders) { folderPaths(folders) }
    val selecting = vm.selectedCount > 0
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val picker = rememberLauncherForActivityResult(PickTree()) { u -> if (u != null) vm.addSource(u) }
    val pickFiles = rememberLauncherForActivityResult(PickPdfs()) { l -> if (l.isNotEmpty()) vm.addFiles(l, currentId) }
    val onboarding = vm.loaded && vm.sources.isEmpty() && pdfs.isEmpty() && currentId == null
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
                            if (total == 1) DropdownMenuItem(text = { Text(if (selFolders.isNotEmpty()) "Rename / colour" else "Rename") }, onClick = {
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
                            })
                            DropdownMenuItem(text = { Text(if (allPinned) "Unpin" else "Pin to top") }, onClick = {
                                more = false
                                vm.setPdfPinned(selPdfs, !allPinned); vm.setFoldersPinned(selFolders, !allPinned); vm.clearSel()
                            })
                            DropdownMenuItem(text = { Text("Remove from Shelf") }, onClick = { more = false; vm.pdfDialog = PdfDlg.RemoveItems(selPdfs, selFolders) })
                            if (selFolders.isEmpty()) DropdownMenuItem(text = { Text("Delete from phone", color = pal.accent) }, onClick = {
                                more = false
                                vm.pdfDialog = PdfDlg.Delete(selPdfs, pdfs.firstOrNull { it.id == selPdfs.firstOrNull() }?.title() ?: "")
                            })
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
                            Text("Choose the folders where your study PDFs live. Shelf reads them right where they are. Nothing is copied, moved or uploaded.", fontSize = 15.sp, lineHeight = 22.sp, color = pal.soft)
                            Spacer(Modifier.height(28.dp))
                            Button(onClick = { picker.launch(null) }, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = pal.accent, contentColor = Color.White),
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)) {
                                Text("Choose folders", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            }
                        }
                    }
                } else {
                    // ---- continue reading ----
                    if (current == null && !selecting && resume != null) item(key = "resume") {
                        ResumeCard(resume, resume.folderId?.let { paths[it] }) { vm.openReader(resume.id) }
                    }

                    // ---- folders ----
                    if (subs.isNotEmpty()) {
                        item(key = "fl") { Overline("Folders", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
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
                            Overline("$label · ${docs.size}", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                        }
                        items(shownDocs, key = { it.id }) { p ->
                            PdfRow(p, null, selecting, p.id in vm.selection,
                                onClick = { if (selecting) vm.toggleSel(p.id) else vm.openReader(p.id) },
                                onFav = { vm.setFavorite(listOf(p.id), !p.favorite) },
                                onMore = { a -> vm.rowAction(p, a) },
                                modifier = reorderItem(reorder, p.id, pal.surface))
                        }
                    }

                    if (current != null && subs.isEmpty() && docs.isEmpty()) item(key = "empty") {
                        Hint("Nothing in here yet", "Tap + at the bottom right to add PDFs from your phone here, or to create a subfolder.")
                    }
                }
            }
        }

        if (!selecting && !onboarding) {
            var add by remember { mutableStateOf(false) }
            Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
                FloatingActionButton(
                    onClick = { add = true }, containerColor = pal.accent, contentColor = Color.White, shape = RoundedCornerShape(18.dp)
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
                }
            }
        }
    }

    when (val d = dlg) {
        is Dlg.New -> FolderDialog("New folder", "", stats.subCount(currentId ?: -1L) % FolderTones.size, "Create", { dlg = null }) { n, c -> vm.createFolder(n, c, currentId); dlg = null }
        is Dlg.Edit -> FolderDialog("Edit folder", d.f.name, d.f.color, "Save", { dlg = null }) { n, c -> vm.updateFolder(d.f.id, n, c); dlg = null }
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
@Composable
private fun ResumeCard(p: PdfEntity, place: String?, onClick: () -> Unit) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(22.dp)
    val total = p.pageCount
    val page = (p.lastPage + 1).coerceAtLeast(1)
    val started = total > 0 && p.lastPage > 0
    val done = total > 0 && page >= total
    val frac = if (total > 0) (page.toFloat() / total).coerceIn(0f, 1f) else 0f
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(shape)
            .background(pal.surface).border(1.dp, pal.line, shape).clickable(onClick = onClick).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(p, Modifier.size(ThumbSlotLarge))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Overline(if (done) "Last read" else "Continue reading", color = pal.accent)
                Spacer(Modifier.weight(1f))
                Text(timeAgo(p.lastOpened), fontSize = 11.sp, color = pal.soft)
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
            Icon(Icons.Rounded.PlayArrow, "Resume", tint = Color.White)
        }
    }
}
