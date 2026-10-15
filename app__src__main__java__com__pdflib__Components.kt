@file:OptIn(ExperimentalFoundationApi::class)

package com.pdflib

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

val Serif = FontFamily.Serif

fun PdfEntity.title(): String =
    label?.takeIf { it.isNotBlank() }
        ?: name.replace(Regex("\\.pdf$", RegexOption.IGNORE_CASE), "").replace('_', ' ').trim()

private val numChunk = Regex("\\d+|\\D+")

/** Sorts by title the way people expect: "Lecture 2" comes before "Lecture 10". */
fun sortNatural(list: List<PdfEntity>): List<PdfEntity> {
    val keyed = list.map { it to numChunk.findAll(it.title().lowercase()).map { m -> m.value }.toList() }
    return keyed.sortedWith(Comparator<Pair<PdfEntity, List<String>>> { x, y -> compareChunks(x.second, y.second) }).map { it.first }
}

private fun compareChunks(a: List<String>, b: List<String>): Int {
    val n = minOf(a.size, b.size)
    for (i in 0 until n) {
        val p = a[i]; val q = b[i]
        val c = if (p[0].isDigit() && q[0].isDigit()) {
            val pn = p.trimStart('0'); val qn = q.trimStart('0')
            if (pn.length != qn.length) pn.length - qn.length else pn.compareTo(qn)
        } else p.compareTo(q)
        if (c != 0) return c
    }
    return a.size - b.size
}

/** Folder picker that asks for edit access, so Shelf can rename and delete the real files. */
class PickTree : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent =
        super.createIntent(context, input).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
}

enum class PdfMenuAction { Rename, Move, Remove, Delete }

sealed interface PdfDlg {
    class Rename(val pdf: PdfEntity) : PdfDlg
    class Move(val ids: List<Long>) : PdfDlg
    /** [title] is only used when a single PDF is being deleted. */
    class Delete(val ids: List<Long>, val title: String) : PdfDlg
}

fun formatSize(b: Long): String = when {
    b >= 1_048_576 -> String.format(Locale.US, "%.1f MB", b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} KB"
    else -> "$b B"
}

fun folderPaths(folders: List<FolderEntity>): Map<Long, String> {
    val by = folders.associateBy { it.id }
    fun p(f: FolderEntity): String {
        val parent = f.parentId?.let { by[it] }
        return if (parent == null) f.name else p(parent) + " › " + f.name
    }
    return folders.associate { it.id to p(it) }
}

fun descendants(id: Long, folders: List<FolderEntity>): Set<Long> {
    val kids = folders.groupBy { it.parentId }
    val out = HashSet<Long>()
    fun walk(x: Long) { out.add(x); kids[x]?.forEach { walk(it.id) } }
    walk(id)
    return out
}

class FolderStats(pdfs: List<PdfEntity>, folders: List<FolderEntity>) {
    private val direct = pdfs.groupingBy { it.folderId }.eachCount()
    private val kids = folders.groupBy { it.parentId }
    private val cache = HashMap<Long, Int>()
    fun pdfCount(id: Long): Int = cache.getOrPut(id) { (direct[id] ?: 0) + (kids[id]?.sumOf { pdfCount(it.id) } ?: 0) }
    fun subCount(id: Long): Int = kids[id]?.size ?: 0
    fun subtitle(id: Long): String {
        val n = pdfCount(id); val s = subCount(id)
        return "$n PDF" + (if (n == 1) "" else "s") + (if (s > 0) " · $s folder" + (if (s == 1) "" else "s") else "")
    }
}

@Composable
fun Overline(text: String, modifier: Modifier = Modifier, color: Color = LocalPal.current.soft) {
    Text(text, modifier = modifier, color = color, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

/** The Shelf mark: three books on a shelf. The artwork has built-in padding, so it is drawn a little larger than its box. */
@Composable
fun LogoMark(size: androidx.compose.ui.unit.Dp = 36.dp) {
    Image(
        painterResource(R.drawable.ic_logo_fg), "Shelf",
        Modifier.size(size).graphicsLayer { scaleX = 1.5f; scaleY = 1.5f }
    )
}

@Composable
fun SearchPill(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(pal.chip).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Search, null, tint = pal.soft, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Search PDFs", color = pal.soft, fontSize = 15.sp, maxLines = 1)
    }
}

/** Logo, name and search in one row. Pass null for [onSearch] on screens with nothing to search. */
@Composable
fun AppHeader(onSearch: (() -> Unit)?) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LogoMark(36.dp)
        Spacer(Modifier.width(2.dp))
        Text("Shelf", fontFamily = Serif, fontStyle = FontStyle.Italic, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = pal.accent)
        Spacer(Modifier.width(14.dp))
        if (onSearch != null) SearchPill(Modifier.weight(1f), onSearch) else Spacer(Modifier.weight(1f))
    }
}

@Composable
fun ReadBar(fraction: Float, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(pal.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(pal.accent))
    }
}

@Composable
fun Thumb(pdf: PdfEntity, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    val bmp by produceState<Bitmap?>(null, pdf.id, pdf.size) { value = Thumbs.get(ctx, pdf) }
    val shape = RoundedCornerShape(6.dp)
    Box(modifier.clip(shape).background(Color.White).border(1.dp, pal.line, shape), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alignment = Alignment.TopCenter)
        else Text("PDF", fontSize = 9.sp, color = Color(0xFFB8B2A3), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun PdfRow(
    pdf: PdfEntity, place: String?, selecting: Boolean, selected: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, onFav: () -> Unit,
    onMore: ((PdfMenuAction) -> Unit)? = null
) {
    val pal = LocalPal.current
    val started = pdf.pageCount > 0 && pdf.lastPage > 0 && !pdf.missing
    var menu by remember { mutableStateOf(false) }
    val meta = listOfNotNull(
        place, if (pdf.missing) "File not found" else formatSize(pdf.size),
        if (started) "p. ${pdf.lastPage + 1} of ${pdf.pageCount}" else null
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) pal.accent.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(start = 16.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(pdf, Modifier.size(46.dp, 62.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(pdf.title(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = if (pdf.missing) pal.soft else pal.ink)
            Spacer(Modifier.height(3.dp))
            Text(meta, fontSize = 12.sp, color = if (pdf.missing) pal.accent else pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (started) { Spacer(Modifier.height(7.dp)); ReadBar((pdf.lastPage + 1f) / pdf.pageCount, Modifier.padding(end = 12.dp)) }
        }
        if (selecting) {
            Box(Modifier.padding(horizontal = 12.dp).size(24.dp).clip(CircleShape)
                .background(if (selected) pal.accent else Color.Transparent)
                .border(1.5.dp, if (selected) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        } else {
            IconButton(onClick = onFav, modifier = Modifier.size(40.dp)) {
                Icon(
                    if (pdf.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite",
                    tint = if (pdf.favorite) pal.hi else pal.soft.copy(alpha = 0.6f)
                )
            }
            if (onMore != null) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.soft) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onMore(PdfMenuAction.Rename) })
                        DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; onMore(PdfMenuAction.Move) })
                        DropdownMenuItem(text = { Text("Remove from Shelf") }, onClick = { menu = false; onMore(PdfMenuAction.Remove) })
                        DropdownMenuItem(text = { Text("Delete from phone", color = pal.accent) }, onClick = { menu = false; onMore(PdfMenuAction.Delete) })
                    }
                }
            } else Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
fun FolderRow(
    f: FolderEntity, subtitle: String, onClick: () -> Unit,
    onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit
) {
    val pal = LocalPal.current
    val tone = FolderTones[f.color.mod(FolderTones.size)]
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = tone, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontFamily = Serif, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = pal.soft)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.soft) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename / colour") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Move to…") }, onClick = { menu = false; onMove() })
                DropdownMenuItem(text = { Text("Delete folder") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
fun Hint(title: String, body: String, modifier: Modifier = Modifier) {
    val pal = LocalPal.current
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, fontFamily = Serif, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = pal.soft, textAlign = TextAlign.Center)
    }
}

@Composable
fun FolderDialog(title: String, initialName: String, initialColor: Int, confirm: String, onDismiss: () -> Unit, onOk: (String, Int) -> Unit) {
    val pal = LocalPal.current
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableIntStateOf(initialColor) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.surface,
        title = { Text(title, fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(fr),
                    placeholder = { Text("e.g. Physics") }
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FolderTones.forEachIndexed { i, c ->
                        Box(Modifier.size(28.dp).clip(CircleShape).background(c).clickable { color = i }, contentAlignment = Alignment.Center) {
                            if (color == i) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onOk(name.trim(), color) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FolderPicker(folders: List<FolderEntity>, exclude: Set<Long>, title: String, onPick: (Long?) -> Unit, onDismiss: () -> Unit) {
    val pal = LocalPal.current
    val flat = remember(folders, exclude) {
        val kids = folders.groupBy { it.parentId }
        val out = ArrayList<Pair<FolderEntity, Int>>()
        fun walk(parent: Long?, depth: Int) {
            kids[parent]?.sortedBy { it.name.lowercase() }?.forEach {
                if (it.id !in exclude) { out.add(it to depth); walk(it.id, depth + 1) }
            }
        }
        walk(null, 0)
        out
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.surface,
        title = { Text(title, fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
        text = {
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item { PickRow("Library (top level)", 0, pal.ink) { onPick(null) } }
                items(flat, key = { it.first.id }) { (f, d) ->
                    PickRow(f.name, d, FolderTones[f.color.mod(FolderTones.size)]) { onPick(f.id) }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PickRow(label: String, depth: Int, tone: Color, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(start = (8 + depth * 18).dp, top = 11.dp, bottom = 11.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.Folder, null, tint = tone, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = pal.ink, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, onOk: (String) -> Unit) {
    val pal = LocalPal.current
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.surface,
        title = { Text("Rename PDF", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(fr)) },
        confirmButton = { TextButton(enabled = text.text.isNotBlank(), onClick = { onOk(text.text.trim()) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One place for every PDF dialog, so Library, Recent, Saved and Search all behave the same. */
@Composable
fun PdfDialogHost(vm: LibraryVm) {
    val pal = LocalPal.current
    val folders by vm.folders.collectAsState()
    when (val d = vm.pdfDialog) {
        is PdfDlg.Rename -> RenameDialog(d.pdf.title(), { vm.pdfDialog = null }) { n -> vm.renamePdf(d.pdf, n); vm.pdfDialog = null }
        is PdfDlg.Move -> FolderPicker(
            folders, emptySet(), if (d.ids.size == 1) "Move to" else "Move ${d.ids.size} PDFs to",
            { vm.movePdfs(d.ids, it); vm.selection.clear(); vm.pdfDialog = null }, { vm.pdfDialog = null }
        )
        is PdfDlg.Delete -> AlertDialog(
            onDismissRequest = { vm.pdfDialog = null }, containerColor = pal.surface,
            title = {
                Text(if (d.ids.size == 1) "Delete “${d.title}”?" else "Delete ${d.ids.size} PDFs?", fontFamily = Serif, fontWeight = FontWeight.SemiBold)
            },
            text = { Text("This permanently deletes the file from your phone and can't be undone. To keep the file, choose Remove from Shelf instead.") },
            confirmButton = { TextButton(onClick = { vm.deleteFromPhone(d.ids); vm.pdfDialog = null }) { Text("Delete", color = pal.accent) } },
            dismissButton = { TextButton(onClick = { vm.pdfDialog = null }) { Text("Cancel") } }
        )
        null -> {}
    }
}
