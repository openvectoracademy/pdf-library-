@file:OptIn(ExperimentalMaterial3Api::class)

package com.pdflib

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recent and Saved tabs share this layout. */
@Composable
fun PdfListScreen(vm: LibraryVm, title: String, docs: List<PdfEntity>, emptyTitle: String, emptyBody: String) {
    val pal = LocalPal.current
    val folders by vm.folders.collectAsState()
    val paths = remember(folders) { folderPaths(folders) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppHeader(onSearch = { vm.searching = true })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                    Text(title, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = pal.ink)
                    if (docs.isNotEmpty()) Text("${docs.size} PDF" + if (docs.size == 1) "" else "s", fontSize = 13.sp, color = pal.soft)
                }
            }
            if (docs.isEmpty()) item { Hint(emptyTitle, emptyBody) }
            items(docs, key = { it.id }) { p ->
                PdfRow(p, p.folderId?.let { paths[it] } ?: "Unsorted", false, false,
                    onClick = { vm.openReader(p.id) }, onLong = {}, onFav = { vm.setFavorite(listOf(p.id), !p.favorite) },
                    onMore = { a -> vm.rowAction(p, a) })
            }
        }
    }
}

@Composable
fun SearchScreen(vm: LibraryVm) {
    val pal = LocalPal.current
    val pdfs by vm.pdfs.collectAsState()
    val folders by vm.folders.collectAsState()
    val paths = remember(folders) { folderPaths(folders) }
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    BackHandler { vm.searching = false }

    val tokens = remember(q) { q.lowercase().split(' ').filter { it.isNotBlank() } }
    val foundFolders = remember(tokens, folders) {
        if (tokens.isEmpty()) emptyList() else folders.filter { f -> val n = f.name.lowercase(); tokens.all { it in n } }.take(20)
    }
    val found = remember(tokens, pdfs) {
        if (tokens.isEmpty()) emptyList() else pdfs.filter { p -> val n = (p.title() + " " + p.name).lowercase(); tokens.all { it in n } }.take(200)
    }

    Column(Modifier.fillMaxSize().background(pal.bg).pointerInput(Unit) { detectTapGestures { } }.statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.searching = false }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = pal.ink) }
            Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(21.dp)).background(pal.chip).padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                BasicTextField(
                    q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(fr),
                    textStyle = TextStyle(color = pal.ink, fontSize = 16.sp), cursorBrush = SolidColor(pal.accent),
                    decorationBox = { inner ->
                        Box { if (q.isEmpty()) Text("Search PDFs and folders", color = pal.soft, fontSize = 16.sp); inner() }
                    }
                )
            }
            if (q.isNotEmpty()) IconButton(onClick = { q = "" }) { Icon(Icons.Rounded.Close, "Clear", tint = pal.soft) }
            else Spacer(Modifier.width(12.dp))
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (tokens.isEmpty()) item { Hint("Find anything", "Type part of a file or folder name. ${pdfs.size} PDFs are searched instantly, right on your phone.") }
            else if (found.isEmpty() && foundFolders.isEmpty()) item { Hint("No matches", "Try fewer or shorter words.") }
            if (foundFolders.isNotEmpty()) {
                item { Overline("Folders", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) }
                items(foundFolders, key = { "f${it.id}" }) { f ->
                    Row(Modifier.fillMaxWidth().clickable { vm.goToFolder(f.id) }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Folder, null, tint = FolderTones[f.color.mod(FolderTones.size)])
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(f.name, fontFamily = Serif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
                            Text(paths[f.id] ?: "", fontSize = 12.sp, color = pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (found.isNotEmpty()) {
                item { Overline("PDFs · ${found.size}", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)) }
                items(found, key = { it.id }) { p ->
                    PdfRow(p, p.folderId?.let { paths[it] } ?: "Unsorted", false, false,
                        onClick = { vm.openReader(p.id) }, onLong = {}, onFav = { vm.setFavorite(listOf(p.id), !p.favorite) },
                        onMore = { a -> vm.rowAction(p, a) })
                }
            }
        }
    }
}

private fun treeLabel(uri: Uri): String {
    val id = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { uri.toString() }
    return id.substringAfter(':', id).ifEmpty { "Internal storage" }
}

@Composable
private fun SettingRow(icon: ImageVector, title: String, sub: String? = null, tint: Color = LocalPal.current.ink, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = pal.ink, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 12.sp, color = pal.soft)
        }
    }
}

@Composable
fun SettingsScreen(vm: LibraryVm) {
    val pal = LocalPal.current
    val pdfs by vm.pdfs.collectAsState()
    val folders by vm.folders.collectAsState()
    val bms by vm.bookmarks.collectAsState()
    val removed by vm.hidden.collectAsState()
    var showRemoved by remember { mutableStateOf(false) }
    var confirmSource by remember { mutableStateOf<Uri?>(null) }
    val add = rememberLauncherForActivityResult(PickTree()) { u -> if (u != null) vm.addSource(u) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> if (u != null) vm.exportBackup(u) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importBackup(u) }

    LazyColumn(Modifier.fillMaxSize().statusBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { AppHeader(onSearch = null) }
        item { Text("Settings", fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = pal.ink, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 12.dp)) }

        item { Overline("PDF folders", Modifier.padding(start = 16.dp, top = 12.dp, bottom = 2.dp)) }
        items(vm.sources, key = { it.toString() }) { u ->
            val canEdit = u.toString() in vm.writable
            Row(
                Modifier.fillMaxWidth().clickable(enabled = !canEdit) { add.launch(null) }.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Folder, null, tint = pal.soft, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(treeLabel(u), fontSize = 15.sp, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (canEdit) "Can rename and delete files" else "Read-only. Tap, then choose this folder again to allow renaming and deleting.",
                        fontSize = 12.sp, lineHeight = 16.sp, color = if (canEdit) pal.soft else pal.accent
                    )
                }
                IconButton(onClick = { confirmSource = u }) { Icon(Icons.Rounded.Close, "Remove", tint = pal.soft) }
            }
        }
        item { SettingRow(Icons.Rounded.Add, "Add a folder", "Subfolders are included automatically", pal.accent) { add.launch(null) } }
        item { SettingRow(Icons.Rounded.Refresh, if (vm.scanning) "Scanning… ${vm.scanFound} found" else "Rescan now", "Also runs every time you open Shelf") { vm.rescan() } }

        item { Overline("Your library", Modifier.padding(start = 16.dp, top = 24.dp, bottom = 2.dp)) }
        item {
            SettingRow(Icons.Rounded.VisibilityOff, "Removed PDFs", if (removed.isEmpty()) "Nothing removed" else "${removed.size} hidden from Shelf, files untouched") { showRemoved = true }
        }

        item { Overline("Backup", Modifier.padding(start = 16.dp, top = 24.dp, bottom = 2.dp)) }
        item {
            SettingRow(Icons.Rounded.Backup, "Export backup", "Folders, stars, bookmarks and progress as a small file") {
                export.launch("shelf-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json")
            }
        }
        item { SettingRow(Icons.Rounded.Restore, "Restore backup", "Matches PDFs by location, then by name and size") { import.launch(arrayOf("application/json", "*/*")) } }

        item { Overline("About", Modifier.padding(start = 16.dp, top = 24.dp, bottom = 6.dp)) }
        item {
            Text("${pdfs.size} PDFs · ${folders.size} folders · ${bms.size} bookmarks\n\nShelf works fully offline. Your PDFs stay where they are, and only the organisation is stored on this phone.",
                fontSize = 13.sp, lineHeight = 19.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 16.dp))
        }
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp)) {
                Text("Developed by", fontSize = 12.sp, color = pal.soft)
                Spacer(Modifier.height(2.dp))
                Text("Md. Al Amin", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = pal.ink)
                Text("Barguna, Bangladesh", fontSize = 13.sp, color = pal.soft)
            }
        }
    }

    confirmSource?.let { u ->
        AlertDialog(
            onDismissRequest = { confirmSource = null }, containerColor = pal.surface,
            title = { Text("Stop using “${treeLabel(u)}”?", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
            text = {
                Text("Shelf will stop looking in this folder. Your PDFs stay on your phone, and their folders, stars and bookmarks stay in Shelf. They show “File not found” until you add the folder again.")
            },
            confirmButton = { TextButton(onClick = { vm.removeSource(u); confirmSource = null }) { Text("Stop using", color = pal.accent) } },
            dismissButton = { TextButton(onClick = { confirmSource = null }) { Text("Cancel") } }
        )
    }

    if (showRemoved) ModalBottomSheet(onDismissRequest = { showRemoved = false }, containerColor = pal.surface) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Row(Modifier.padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Removed from Shelf", Modifier.weight(1f), fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = pal.ink)
                if (removed.isNotEmpty()) TextButton(onClick = { vm.restoreToShelf(removed.map { it.id }) }) { Text("Restore all", color = pal.accent) }
            }
            if (removed.isEmpty()) {
                Text("Nothing here. PDFs you remove from Shelf wait here until you restore them. The files themselves are never touched.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(removed, key = { it.id }) { p ->
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title(), Modifier.weight(1f).padding(vertical = 12.dp), fontSize = 15.sp, color = pal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        TextButton(onClick = { vm.restoreToShelf(listOf(p.id)) }) { Text("Restore", color = pal.accent) }
                    }
                }
            }
        }
    }
}
