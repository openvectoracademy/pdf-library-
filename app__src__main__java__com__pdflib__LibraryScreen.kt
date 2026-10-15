package com.pdflib

import android.content.Context
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
    class MoveFolder(val f: FolderEntity) : Dlg
}

@Composable
fun LibraryScreen(vm: LibraryVm) {
    val pal = LocalPal.current
    val folders by vm.folders.collectAsState()
    val pdfs by vm.pdfs.collectAsState()
    val currentId = vm.stack.lastOrNull()
    val byId = remember(folders) { folders.associateBy { it.id } }
    val current = currentId?.let { byId[it] }
    val subs = remember(folders, currentId) { folders.filter { it.parentId == currentId } }
    val docs = remember(pdfs, currentId) { sortNatural(pdfs.filter { it.folderId == currentId }) }
    val stats = remember(pdfs, folders) { FolderStats(pdfs, folders) }
    val resume = remember(pdfs) { pdfs.filter { it.lastOpened > 0 }.maxByOrNull { it.lastOpened } }
    val selecting = vm.selection.isNotEmpty()
    var dlg by remember { mutableStateOf<Dlg?>(null) }
    val picker = rememberLauncherForActivityResult(PickTree()) { u -> if (u != null) vm.addSource(u) }
    val pickFiles = rememberLauncherForActivityResult(PickPdfs()) { l -> if (l.isNotEmpty()) vm.addFiles(l, currentId) }
    val onboarding = vm.loaded && vm.sources.isEmpty() && pdfs.isEmpty() && currentId == null
    val openSearch: () -> Unit = { vm.searching = true }

    BackHandler(enabled = selecting || vm.stack.isNotEmpty()) { if (selecting) vm.selection.clear() else vm.up() }
    LaunchedEffect(currentId) { vm.libState.scrollToItem(0) }
    LaunchedEffect(folders) { if (currentId != null && folders.isNotEmpty() && byId[currentId] == null) vm.goToFolder(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            // ---- top bar: logo + name + search, selection tools, or back + search inside a folder ----
            if (selecting) {
                var more by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.selection.clear() }) { Icon(Icons.Rounded.Close, "Cancel", tint = pal.ink) }
                    Text("${vm.selection.size} selected", fontFamily = Serif, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.selection.clear(); vm.selection.addAll(docs.map { it.id }) }) { Icon(Icons.Rounded.SelectAll, "Select all", tint = pal.ink) }
                    IconButton(onClick = {
                        val allFav = vm.selection.all { id -> pdfs.firstOrNull { it.id == id }?.favorite == true }
                        vm.setFavorite(vm.selection.toList(), !allFav); vm.selection.clear()
                    }) { Icon(Icons.Rounded.Star, "Favorite", tint = pal.hi) }
                    IconButton(onClick = { vm.pdfDialog = PdfDlg.Move(vm.selection.toList()) }) { Icon(Icons.Rounded.FolderOpen, "Move", tint = pal.accent) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.ink) }
                        DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                            if (vm.selection.size == 1) DropdownMenuItem(text = { Text("Rename") }, onClick = {
                                more = false
                                val one = pdfs.firstOrNull { it.id == vm.selection.first() }
                                if (one != null) { vm.selection.clear(); vm.pdfDialog = PdfDlg.Rename(one) }
                            })
                            DropdownMenuItem(text = { Text("Remove from Shelf") }, onClick = { more = false; vm.removeFromShelf(vm.selection.toList()) })
                            DropdownMenuItem(text = { Text("Delete from phone", color = pal.accent) }, onClick = {
                                more = false
                                val ids = vm.selection.toList()
                                vm.pdfDialog = PdfDlg.Delete(ids, pdfs.firstOrNull { it.id == ids.firstOrNull() }?.title() ?: "")
                            })
                        }
                    }
                }
            } else if (current != null) {
                Row(Modifier.fillMaxWidth().height(60.dp).padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.up() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = pal.ink) }
                    SearchPill(Modifier.weight(1f), openSearch)
                }
            } else {
                AppHeader(if (onboarding) null else openSearch)
            }

            LazyColumn(state = vm.libState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(bottom = 110.dp)) {
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
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp))
                                .background(pal.surface).border(1.dp, pal.line, RoundedCornerShape(20.dp))
                                .clickable { vm.openReader(resume.id) }.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Thumb(resume, Modifier.size(54.dp, 74.dp))
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Overline("Continue reading", color = pal.accent)
                                Spacer(Modifier.height(4.dp))
                                Text(resume.title(), fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = pal.ink)
                                Spacer(Modifier.height(6.dp))
                                if (resume.pageCount > 0) {
                                    Text("Page ${resume.lastPage + 1} of ${resume.pageCount}", fontSize = 12.sp, color = pal.soft)
                                    Spacer(Modifier.height(6.dp))
                                    ReadBar((resume.lastPage + 1f) / resume.pageCount)
                                }
                            }
                        }
                    }

                    // ---- folders ----
                    if (subs.isNotEmpty()) {
                        item(key = "fl") { Overline("Folders", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
                        items(subs, key = { "f${it.id}" }) { f ->
                            FolderRow(f, stats.subtitle(f.id), onClick = { vm.enter(f.id) },
                                onEdit = { dlg = Dlg.Edit(f) }, onMove = { dlg = Dlg.MoveFolder(f) }, onDelete = { dlg = Dlg.Delete(f) })
                        }
                    }

                    // ---- pdfs ----
                    if (docs.isNotEmpty()) {
                        item(key = "pl") {
                            val label = if (current == null && subs.isNotEmpty()) "Unsorted" else "PDFs"
                            Overline("$label · ${docs.size}", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
                        }
                        items(docs, key = { it.id }) { p ->
                            PdfRow(p, null, selecting, p.id in vm.selection,
                                onClick = { if (selecting) vm.toggleSel(p.id) else vm.openReader(p.id) },
                                onLong = { vm.toggleSel(p.id) },
                                onFav = { vm.setFavorite(listOf(p.id), !p.favorite) },
                                onMore = { a -> vm.rowAction(p, a) })
                        }
                    }

                    if (current != null && subs.isEmpty() && docs.isEmpty()) item(key = "empty") {
                        Hint("Nothing in here yet", "Use the buttons at the bottom right to add PDFs from storage here, or create a subfolder.")
                    }
                }
            }
        }

        if (!selecting && !onboarding) {
            Column(Modifier.align(Alignment.BottomEnd).padding(16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = { pickFiles.launch(arrayOf("application/pdf")) }, containerColor = pal.surface, contentColor = pal.accent,
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.border(1.dp, pal.line, RoundedCornerShape(14.dp))
                ) { Icon(Icons.Rounded.NoteAdd, "Add PDFs from storage") }
                FloatingActionButton(
                    onClick = { dlg = Dlg.New }, containerColor = pal.accent, contentColor = Color.White, shape = RoundedCornerShape(18.dp)
                ) { Icon(Icons.Rounded.CreateNewFolder, "New folder") }
            }
        }
    }

    when (val d = dlg) {
        is Dlg.New -> FolderDialog("New folder", "", stats.subCount(currentId ?: -1L) % FolderTones.size, "Create", { dlg = null }) { n, c -> vm.createFolder(n, c, currentId); dlg = null }
        is Dlg.Edit -> FolderDialog("Edit folder", d.f.name, d.f.color, "Save", { dlg = null }) { n, c -> vm.updateFolder(d.f.id, n, c); dlg = null }
        is Dlg.Delete -> AlertDialog(
            onDismissRequest = { dlg = null }, containerColor = pal.surface,
            title = { Text("Delete “${d.f.name}”?", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
            text = { Text("Its PDFs and subfolders move up one level. Files on your phone are never touched.") },
            confirmButton = { TextButton(onClick = { vm.deleteFolder(d.f); dlg = null }) { Text("Delete", color = pal.accent) } },
            dismissButton = { TextButton(onClick = { dlg = null }) { Text("Cancel") } }
        )
        is Dlg.MoveFolder -> FolderPicker(folders, descendants(d.f.id, folders), "Move “${d.f.name}” to", { vm.moveFolder(d.f.id, it); dlg = null }, { dlg = null })
        null -> {}
    }
}
