@file:OptIn(ExperimentalMaterial3Api::class)

package com.pdflib

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How a linked phone folder fills a Shelf folder. */
const val LINK_MIRROR = 0   // its subfolders become Shelf folders
const val LINK_FLAT = 1     // every PDF goes straight in, no folders

/** Stable id for a phone folder, used to find the Shelf folders created for it. */
fun linkKey(uri: String): String = Integer.toHexString(uri.hashCode())

/** "primary:Download/Physics" -> "Physics". */
fun treeFolderName(uri: Uri): String {
    if (uri.scheme == "file") {
        val path = uri.path ?: return "Phone storage"
        return if (path == "/storage/emulated/0") "Internal storage" else java.io.File(path).name.ifEmpty { "SD card" }
    }
    val id = try { DocumentsContract.getTreeDocumentId(uri) } catch (e: Exception) { return "Phone folder" }
    val path = id.substringAfter(':', "")
    return path.substringAfterLast('/').ifEmpty { if (id.startsWith("primary")) "Internal storage" else "SD card" }
}

@Composable
private fun ModeCard(icon: ImageVector, title: String, body: String, selected: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    val shape = RoundedCornerShape(18.dp)
    val edge by animateColorAsState(if (selected) pal.accent else pal.line, label = "modeEdge")
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (selected) pal.accent.copy(alpha = 0.07f) else pal.surface)
            .border(if (selected) 2.dp else 1.dp, edge, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pal.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = pal.accent, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
            Spacer(Modifier.height(4.dp))
            Text(body, fontSize = 13.sp, lineHeight = 19.sp, color = pal.soft)
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier.size(22.dp).clip(CircleShape).background(if (selected) pal.accent else Color.Transparent)
                .border(1.5.dp, if (selected) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) { if (selected) Icon(Icons.Rounded.Check, null, tint = pal.onAccent, modifier = Modifier.size(15.dp)) }
    }
}

/** Shown right after a phone folder is picked: keep its folders, or just bring in the files. */
@Composable
fun LinkModeSheet(vm: LibraryVm, tree: Uri, target: Long?, targetName: String, onClose: () -> Unit) {
    val pal = LocalPal.current
    var mode by remember { mutableIntStateOf(LINK_MIRROR) }
    val name = remember(tree) { treeFolderName(tree) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Overline("Link a phone folder", color = pal.accent)
            Spacer(Modifier.height(6.dp))
            Text(name, fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 26.sp, color = pal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("goes into $targetName", fontSize = 14.sp, color = pal.soft)
            Spacer(Modifier.height(20.dp))
            ModeCard(
                Icons.Rounded.AccountTree, "Keep its folders",
                "“$name” and every subfolder inside it appear here as folders, organised like on your phone.",
                mode == LINK_MIRROR
            ) { mode = LINK_MIRROR }
            Spacer(Modifier.height(10.dp))
            ModeCard(
                Icons.Rounded.Description, "Just the files",
                "Every PDF inside, from all of its subfolders, comes straight into $targetName. No folders are created.",
                mode == LINK_FLAT
            ) { mode = LINK_FLAT }
            Spacer(Modifier.height(14.dp))
            Text(
                "Shelf reads the files right where they are. Nothing is copied or uploaded, and new PDFs you add to that phone folder show up here by themselves.",
                fontSize = 12.sp, lineHeight = 17.sp, color = pal.soft
            )
            Spacer(Modifier.height(18.dp))
            Button(
                onClick = { vm.linkFolder(tree, target, mode); onClose() },
                modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = pal.accent, contentColor = pal.onAccent)
            ) { Text("Add to $targetName", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = pal.soft) }
        }
    }
}

@Composable
private fun LinkChip(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = color)
    }
}

@Composable
private fun LinkCard(link: LinkEntity, count: Int, hasAccess: Boolean, canEdit: Boolean, onFix: () -> Unit, onUnlink: () -> Unit) {
    val pal = LocalPal.current
    val ok = if (pal.dark) Color(0xFF8FBF6A) else Color(0xFF4C7A2E)
    val shape = RoundedCornerShape(18.dp)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clip(shape).background(pal.bg).border(1.dp, pal.line, shape).padding(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(pal.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Link, null, tint = pal.accent, modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(link.name, fontFamily = Serif, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$count PDF" + (if (count == 1) "" else "s"), fontSize = 12.sp, color = pal.soft)
            }
            IconButton(onClick = onUnlink) { Icon(Icons.Rounded.LinkOff, "Unlink", tint = pal.soft) }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LinkChip(if (link.mode == LINK_MIRROR) "Keeps folders" else "Just the files", pal.soft, if (link.mode == LINK_MIRROR) Icons.Rounded.AccountTree else Icons.Rounded.Description)
            when {
                !hasAccess -> LinkChip("Access lost", pal.accent, Icons.Rounded.Lock)
                canEdit -> LinkChip("Can rename and delete", ok, Icons.Rounded.CheckCircle)
                else -> LinkChip("Read-only", pal.accent, Icons.Rounded.Lock)
            }
        }
        if (!hasAccess || !canEdit) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (!hasAccess) "Shelf can't read this folder right now. Choose it again to bring it back."
                else "Choose this folder again to allow renaming and deleting its files.",
                fontSize = 12.sp, lineHeight = 17.sp, color = pal.soft
            )
            TextButton(onClick = onFix, contentPadding = PaddingValues(0.dp)) {
                Text(if (!hasAccess) "Reconnect folder" else "Allow editing", color = pal.accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Manage the phone folders linked into one Shelf folder. */
@Composable
fun LinksSheet(vm: LibraryVm, target: Long?, targetName: String, onFix: () -> Unit, onAdd: () -> Unit, onClose: () -> Unit) {
    val pal = LocalPal.current
    val links by vm.links.collectAsState()
    val pdfs by vm.pdfs.collectAsState()
    val mine = links.filter { it.target == target }
    var confirm by remember { mutableStateOf<LinkEntity?>(null) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = pal.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 28.dp)) {
            Text("Linked phone folders", fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = pal.ink, modifier = Modifier.padding(horizontal = 20.dp))
            Text(
                "New PDFs in these phone folders appear in $targetName by themselves.",
                fontSize = 13.sp, lineHeight = 18.sp, color = pal.soft, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 10.dp)
            )
            if (mine.isEmpty()) Text("Nothing is linked here yet.", fontSize = 14.sp, color = pal.soft, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            mine.forEach { l ->
                key(l.id) {
                    val prefix = PhoneOps.prefixOf(Uri.parse(l.uri))
                    LinkCard(
                        l, pdfs.count { it.uri.startsWith(prefix) },
                        // Folders read through "All files access" (file:// links) have no saved folder permission, yet are fully readable.
                        hasAccess = vm.sources.any { it.toString() == l.uri } || (l.uri.startsWith("file:") && vm.allFiles), canEdit = l.uri in vm.writable,
                        onFix = onFix, onUnlink = { confirm = l }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onAdd, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.Add, null, tint = pal.accent)
                Spacer(Modifier.width(8.dp))
                Text("Link another phone folder", color = pal.accent, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    confirm?.let { l ->
        AlertDialog(
            onDismissRequest = { confirm = null }, containerColor = pal.surface,
            title = { Text("Unlink “${l.name}”?", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
            text = {
                Text("Shelf stops reading this phone folder. Its PDFs stay in Shelf with their folders, stars and bookmarks, and show “File not found” until you link the folder again. Files on your phone are never touched.")
            },
            confirmButton = { TextButton(onClick = { vm.unlink(l); confirm = null }) { Text("Unlink", color = pal.accent) } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } }
        )
    }
}
