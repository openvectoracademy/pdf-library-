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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Recent and Saved tabs share this layout. */
@Composable
fun PdfListScreen(
    vm: LibraryVm, title: String, docs: List<PdfEntity>, emptyTitle: String, emptyBody: String,
    favFolders: List<FolderEntity> = emptyList()
) {
    val pal = LocalPal.current
    val folders by vm.folders.collectAsState()
    val allPdfs by vm.pdfs.collectAsState()
    val paths = remember(folders) { folderPaths(folders) }
    val stats = remember(allPdfs, folders) { FolderStats(allPdfs, folders) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        AppHeader(onSearch = { vm.searching = true })
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
                    Text(title, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = pal.ink)
                    val parts = ArrayList<String>()
                    if (favFolders.isNotEmpty()) parts.add("${favFolders.size} folder" + if (favFolders.size == 1) "" else "s")
                    if (docs.isNotEmpty()) parts.add("${docs.size} PDF" + if (docs.size == 1) "" else "s")
                    if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 13.sp, color = pal.soft)
                }
            }
            if (docs.isEmpty() && favFolders.isEmpty()) item { Hint(emptyTitle, emptyBody) }
            if (favFolders.isNotEmpty()) {
                item(key = "fav-folders") { Overline("Folders", Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp)) }
                items(favFolders, key = { "sf${it.id}" }) { f ->
                    FolderRow(
                        f, stats.subtitle(f.id), onClick = { vm.goToFolder(f.id) }, onEdit = {}, onMove = {}, onDelete = {},
                        onFav = { vm.setFoldersFavorite(listOf(f.id), !f.favorite) }, menu = false
                    )
                }
                if (docs.isNotEmpty()) item(key = "fav-pdfs") { Overline("PDFs", Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            }
            items(docs, key = { it.id }) { p ->
                PdfRow(p, p.folderId?.let { paths[it] } ?: "Unsorted", false, false,
                    onClick = { vm.openReader(p.id) }, onFav = { vm.setFavorite(listOf(p.id), !p.favorite) },
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
                        onClick = { vm.openReader(p.id) }, onFav = { vm.setFavorite(listOf(p.id), !p.favorite) },
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


// ───────────────────────── Settings building blocks ─────────────────────────

private val CardShape = RoundedCornerShape(20.dp)

/** A titled group: small caps label, one rounded card, and an optional footnote underneath. */
@Composable
private fun SettingsSection(label: String, footer: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().padding(top = 26.dp)) {
        Text(
            label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp,
            color = pal.soft, modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp)
        )
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(CardShape).background(pal.surface).border(1.dp, pal.line, CardShape),
            content = content
        )
        if (footer != null) {
            Text(footer, fontSize = 12.sp, lineHeight = 17.sp, color = pal.soft, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp))
        }
    }
}

@Composable
private fun RowDivider(inset: Dp = 68.dp) {
    Box(Modifier.padding(start = inset).fillMaxWidth().height(1.dp).background(LocalPal.current.line))
}

@Composable
private fun IconTile(icon: ImageVector, tint: Color) {
    Box(Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The shared look of every settings row. [modifier] carries the click or toggle behaviour. */
@Composable
private fun RowShell(
    icon: ImageVector, title: String, sub: String?, tint: Color, modifier: Modifier, trailing: (@Composable () -> Unit)?
) {
    val pal = LocalPal.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(icon, tint)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, color = pal.ink, fontWeight = FontWeight.Medium)
            if (sub != null) Text(sub, fontSize = 12.sp, lineHeight = 16.sp, color = pal.soft)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector, title: String, sub: String? = null, tint: Color = LocalPal.current.accent,
    trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit
) {
    RowShell(icon, title, sub, tint, Modifier.clickable(onClick = onClick), trailing)
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPal.current
    RowShell(
        icon, title, sub, pal.accent, Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        trailing = {
            Switch(
                checked = checked, onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White, checkedTrackColor = pal.accent, checkedBorderColor = pal.accent,
                    uncheckedThumbColor = pal.soft, uncheckedTrackColor = pal.chip, uncheckedBorderColor = pal.line
                )
            )
        }
    )
}

@Composable
private fun StatusChip(text: String, color: Color, icon: ImageVector) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(start = 6.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun StatTile(value: Int, label: String, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(pal.chip).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value.toString(), fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = pal.ink)
        Text(label, fontSize = 12.sp, color = pal.soft)
    }
}

@Composable
private fun SourceRow(label: String, canEdit: Boolean, onFix: () -> Unit, onRemove: () -> Unit) {
    val pal = LocalPal.current
    val ok = if (pal.dark) Color(0xFF8FBF6A) else Color(0xFF4C7A2E)
    Row(
        Modifier.fillMaxWidth().then(if (canEdit) Modifier else Modifier.clickable(onClick = onFix))
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconTile(Icons.Rounded.Folder, if (canEdit) ok else pal.accent)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(5.dp))
            if (canEdit) {
                StatusChip("Can rename and delete", ok, Icons.Rounded.CheckCircle)
            } else {
                StatusChip("Read-only", pal.accent, Icons.Rounded.Lock)
                Text(
                    "Tap, then choose this folder again to allow renaming and deleting.",
                    fontSize = 12.sp, lineHeight = 16.sp, color = pal.soft, modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Stop using this folder", tint = pal.soft) }
    }
}

// ───────────────────────── Theme picker ─────────────────────────

/** A tiny drawing of the app in one palette, so the choice is visible before it is made. */
@Composable
private fun MiniApp(p: Pal, modifier: Modifier, pad: Dp) {
    Column(modifier.background(p.bg).padding(pad), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(width = 14.dp, height = 6.dp).clip(RoundedCornerShape(3.dp)).background(p.accent))
        Box(Modifier.fillMaxWidth(0.7f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(p.ink))
        repeat(2) {
            val r = RoundedCornerShape(7.dp)
            Column(
                Modifier.fillMaxWidth().clip(r).background(p.surface).border(1.dp, p.line, r).padding(5.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Box(Modifier.fillMaxWidth(0.85f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(p.ink))
                Box(Modifier.fillMaxWidth(0.5f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(p.soft))
            }
        }
    }
}

@Composable
private fun ThemeCard(mode: ThemeMode, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    val edge by animateColorAsState(if (selected) pal.accent else pal.line, label = "themeEdge")
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.clip(shape).border(2.dp, edge, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(10.dp))) {
            when (mode) {
                ThemeMode.Light -> MiniApp(LightPal, Modifier.weight(1f).fillMaxHeight(), 8.dp)
                ThemeMode.Dark -> MiniApp(DarkPal, Modifier.weight(1f).fillMaxHeight(), 8.dp)
                ThemeMode.System -> {
                    MiniApp(LightPal, Modifier.weight(1f).fillMaxHeight(), 5.dp)
                    MiniApp(DarkPal, Modifier.weight(1f).fillMaxHeight(), 5.dp)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(bottom = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(18.dp).clip(CircleShape)
                    .background(if (selected) pal.accent else Color.Transparent)
                    .border(1.5.dp, if (selected) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) { if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(12.dp)) }
            Spacer(Modifier.width(6.dp))
            Text(mode.label, fontSize = 13.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, color = pal.ink)
        }
    }
}

@Composable
private fun ThemePicker(mode: ThemeMode, onPick: (ThemeMode) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(14.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeMode.values().forEach { m -> ThemeCard(m, m == mode, Modifier.weight(1f)) { onPick(m) } }
    }
}

// ───────────────────────── Settings screen ─────────────────────────

@Suppress("DEPRECATION")
@Composable
fun SettingsScreen(vm: LibraryVm) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val pdfs by vm.pdfs.collectAsState()
    val folders by vm.folders.collectAsState()
    val bms by vm.bookmarks.collectAsState()
    val removed by vm.hidden.collectAsState()
    val removedFolders by vm.removedFolders.collectAsState()
    var showRemoved by remember { mutableStateOf(false) }
    var showRemovedFolders by remember { mutableStateOf(false) }
    var confirmSource by remember { mutableStateOf<Uri?>(null) }
    val add = rememberLauncherForActivityResult(PickTree()) { u -> if (u != null) vm.addSource(u) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { u -> if (u != null) vm.exportBackup(u) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.importBackup(u) }
    val version = remember { try { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "" } catch (e: Exception) { "" } }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
    // The logo bar stays pinned, exactly like the Library screen.
    AppHeader(onSearch = null)
    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Text("Settings", fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 36.sp, color = pal.ink, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 0.dp)) }

        item {
            SettingsSection(
                "Appearance",
                footer = when (vm.themeMode) {
                    ThemeMode.System -> "Shelf switches between light and dark together with your phone."
                    ThemeMode.Light -> "Shelf stays light, whatever your phone is set to."
                    ThemeMode.Dark -> "Shelf stays dark, easier on the eyes at night."
                }
            ) {
                ThemePicker(vm.themeMode) { vm.updateTheme(it) }
                RowDivider()
                SwitchRow(
                    Icons.Rounded.InvertColors, "Night reading", "Invert PDF pages in the reader for dark rooms",
                    vm.invert
                ) { vm.updateInvert(it) }
            }
        }

        item {
            SettingsSection("PDF folders", footer = "Subfolders are included automatically.") {
                if (vm.sources.isEmpty()) {
                    Text(
                        "No folders yet. Add one so Shelf can find your PDFs.",
                        fontSize = 13.sp, lineHeight = 19.sp, color = pal.soft, modifier = Modifier.padding(16.dp)
                    )
                    RowDivider()
                }
                vm.sources.forEach { u ->
                    key(u.toString()) {
                        SourceRow(treeLabel(u), u.toString() in vm.writable, onFix = { add.launch(null) }, onRemove = { confirmSource = u })
                        RowDivider()
                    }
                }
                SettingRow(Icons.Rounded.Add, "Add a folder", "Pick any folder on your phone") { add.launch(null) }
                RowDivider()
                SettingRow(
                    Icons.Rounded.Refresh, if (vm.scanning) "Scanning… ${vm.scanFound} found" else "Rescan now", "Also runs every time you open Shelf",
                    tint = pal.ink,
                    trailing = { if (vm.scanning) CircularProgressIndicator(Modifier.size(20.dp), color = pal.accent, strokeWidth = 2.dp) }
                ) { vm.rescan() }
            }
        }

        item {
            SettingsSection("Your library") {
                SettingRow(
                    Icons.Rounded.Folder, "Removed folders",
                    if (removedFolders.isEmpty()) "Nothing removed" else "Come back with everything inside them",
                    tint = pal.ink,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (removedFolders.isNotEmpty()) {
                                Text(
                                    removedFolders.size.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(pal.accent.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = pal.soft)
                        }
                    }
                ) { showRemovedFolders = true }
                RowDivider()
                SettingRow(
                    Icons.Rounded.VisibilityOff, "Removed PDFs",
                    if (removed.isEmpty()) "Nothing removed" else "Hidden from Shelf, the files are untouched",
                    tint = pal.ink,
                    trailing = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (removed.isNotEmpty()) {
                                Text(
                                    removed.size.toString(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.accent,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(pal.accent.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Icon(Icons.Rounded.ChevronRight, null, tint = pal.soft)
                        }
                    }
                ) { showRemoved = true }
            }
        }

        item {
            SettingsSection("Backup", footer = "A backup is one small file. It never contains your PDFs.") {
                SettingRow(Icons.Rounded.Backup, "Export backup", "Folders, stars, bookmarks and progress") {
                    export.launch("shelf-backup-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".json")
                }
                RowDivider()
                SettingRow(Icons.Rounded.Restore, "Restore backup", "Matches PDFs by location, then by name and size") {
                    import.launch(arrayOf("application/json", "*/*"))
                }
            }
        }

        item {
            SettingsSection("About") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LogoMark(34.dp)
                    Spacer(Modifier.width(22.dp))
                    Column {
                        Text("Shelf", fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = pal.ink)
                        if (version.isNotEmpty()) Text("Version $version", fontSize = 12.sp, color = pal.soft)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(pdfs.size, "PDFs", Modifier.weight(1f))
                    StatTile(folders.size, "Folders", Modifier.weight(1f))
                    StatTile(bms.size, "Bookmarks", Modifier.weight(1f))
                }
                Text(
                    "Shelf works fully offline. Your PDFs stay where they are, and only the organisation is stored on this phone.",
                    fontSize = 13.sp, lineHeight = 19.sp, color = pal.soft, modifier = Modifier.padding(16.dp)
                )
                RowDivider(0.dp)
                Column(Modifier.padding(16.dp)) {
                    Text("Developed by", fontSize = 12.sp, color = pal.soft)
                    Spacer(Modifier.height(2.dp))
                    Text("Md. Al Amin", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = pal.ink)
                    Text("Barguna, Bangladesh", fontSize = 13.sp, color = pal.soft)
                }
            }
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
        val picked = remember { mutableStateListOf<Long>() }
        // Rows that disappear (restored, or changed elsewhere) are dropped from the selection.
        LaunchedEffect(removed) { picked.retainAll { id -> removed.any { it.id == id } } }
        val allPicked = removed.isNotEmpty() && picked.size == removed.size
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Removed from Shelf", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = pal.ink,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
            )
            if (removed.isEmpty()) {
                Text("Nothing here. PDFs you remove from Shelf wait here until you restore them. The files themselves are never touched.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (allPicked) picked.clear() else { picked.clear(); picked.addAll(removed.map { it.id }) }
                    }) { Text(if (allPicked) "Clear selection" else "Select all", color = pal.accent) }
                    Spacer(Modifier.weight(1f))
                    if (picked.isNotEmpty()) {
                        Button(
                            onClick = { vm.restoreToShelf(picked.toList()); picked.clear() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = pal.accent, contentColor = Color.White)
                        ) { Text("Restore (${picked.size})", fontWeight = FontWeight.SemiBold) }
                    } else {
                        TextButton(onClick = { vm.restoreToShelf(removed.map { it.id }) }) { Text("Restore all", color = pal.accent) }
                    }
                }
                if (picked.isEmpty()) Text("Tap PDFs to pick several and restore them together.", fontSize = 12.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(removed, key = { it.id }) { p ->
                    val on = p.id in picked
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (on) pal.accent.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { if (on) picked.remove(p.id) else picked.add(p.id) }
                            .padding(start = 20.dp, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .background(if (on) pal.accent else Color.Transparent)
                                .border(1.5.dp, if (on) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Text(p.title(), Modifier.weight(1f).padding(vertical = 12.dp), fontSize = 15.sp, color = pal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (picked.isEmpty()) TextButton(onClick = { vm.restoreToShelf(listOf(p.id)) }) { Text("Restore", color = pal.accent) }
                        else Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }

    if (showRemovedFolders) ModalBottomSheet(onDismissRequest = { showRemovedFolders = false }, containerColor = pal.surface) {
        val picked = remember { mutableStateListOf<Long>() }
        LaunchedEffect(removedFolders) { picked.retainAll { id -> removedFolders.any { it.folder.id == id } } }
        val allPicked = removedFolders.isNotEmpty() && picked.size == removedFolders.size
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                "Removed folders", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = pal.ink,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 4.dp)
            )
            if (removedFolders.isEmpty()) {
                Text(
                    "Nothing here. A folder you remove waits here, with every PDF and subfolder inside it, until you restore it. Files on your phone are never touched.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        if (allPicked) picked.clear() else { picked.clear(); picked.addAll(removedFolders.map { it.folder.id }) }
                    }) { Text(if (allPicked) "Clear selection" else "Select all", color = pal.accent) }
                    Spacer(Modifier.weight(1f))
                    if (picked.isNotEmpty()) {
                        Button(
                            onClick = { vm.restoreFolders(picked.toList()); picked.clear() },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = pal.accent, contentColor = Color.White)
                        ) { Text("Restore (${picked.size})", fontWeight = FontWeight.SemiBold) }
                    } else {
                        TextButton(onClick = { vm.restoreFolders(removedFolders.map { it.folder.id }) }) { Text("Restore all", color = pal.accent) }
                    }
                }
                if (picked.isEmpty()) Text(
                    "Restoring a folder brings back all of its PDFs and subfolders.",
                    fontSize = 12.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp)
                )
            }
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(removedFolders, key = { it.folder.id }) { r ->
                    val on = r.folder.id in picked
                    val tone = FolderTones[r.folder.color.mod(FolderTones.size)]
                    val np = r.pdfs
                    val nf = r.subfolders
                    val sub = "$np PDF" + (if (np == 1) "" else "s") + (if (nf > 0) " · $nf folder" + (if (nf == 1) "" else "s") else "")
                    Row(
                        Modifier.fillMaxWidth()
                            .background(if (on) pal.accent.copy(alpha = 0.10f) else Color.Transparent)
                            .clickable { if (on) picked.remove(r.folder.id) else picked.add(r.folder.id) }
                            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(24.dp).clip(CircleShape)
                                .background(if (on) pal.accent else Color.Transparent)
                                .border(1.5.dp, if (on) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) { if (on) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.Folder, null, tint = tone, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.folder.name, fontFamily = Serif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sub, fontSize = 12.sp, color = pal.soft)
                        }
                        if (picked.isEmpty()) TextButton(onClick = { vm.restoreFolders(listOf(r.folder.id)) }) { Text("Restore", color = pal.accent) }
                        else Spacer(Modifier.width(8.dp))
                    }
                }
            }
        }
    }
}
