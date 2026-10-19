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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

/** Library order: pinned first, then the order set by hand (if any), then natural A–Z for everything not yet placed by hand. */
fun arrangePdfs(list: List<PdfEntity>): List<PdfEntity> {
    val keyed = list.map { it to numChunk.findAll(it.title().lowercase()).map { m -> m.value }.toList() }
    return keyed.sortedWith(Comparator<Pair<PdfEntity, List<String>>> { x, y ->
        orderCompare(x.first.pinned, x.first.sortIndex, y.first.pinned, y.first.sortIndex).let { if (it != 0) it else compareChunks(x.second, y.second) }
    }).map { it.first }
}

fun arrangeFolders(list: List<FolderEntity>): List<FolderEntity> {
    val keyed = list.map { it to numChunk.findAll(it.name.lowercase()).map { m -> m.value }.toList() }
    return keyed.sortedWith(Comparator<Pair<FolderEntity, List<String>>> { x, y ->
        orderCompare(x.first.pinned, x.first.sortIndex, y.first.pinned, y.first.sortIndex).let { if (it != 0) it else compareChunks(x.second, y.second) }
    }).map { it.first }
}

/** Library "Sort by". 0 keeps your own drag-and-drop order; the rest sort by a rule. Pinned items always stay on top. */
const val SORT_CUSTOM = 0
val SortLabels = listOf("Custom order", "Newest date first", "Oldest date first", "Largest first", "Smallest first", "Name A–Z", "Name Z–A")

fun sortPdfs(list: List<PdfEntity>, mode: Int): List<PdfEntity> {
    if (mode == SORT_CUSTOM) return arrangePdfs(list)
    val byName = sortNatural(list)   // also the tie-breaker for equal dates and sizes
    val body = when (mode) {
        1 -> byName.sortedByDescending { it.modified }
        2 -> byName.sortedBy { it.modified }
        3 -> byName.sortedByDescending { it.size }
        4 -> byName.sortedBy { it.size }
        5 -> byName
        else -> byName.asReversed()
    }
    return body.sortedByDescending { it.pinned }
}

/** Folders only follow the name rules; for date and size they keep their usual order. */
fun sortFolders(list: List<FolderEntity>, mode: Int): List<FolderEntity> {
    if (mode != 5 && mode != 6) return arrangeFolders(list)
    val byName = list.map { it to numChunk.findAll(it.name.lowercase()).map { m -> m.value }.toList() }
        .sortedWith(Comparator<Pair<FolderEntity, List<String>>> { x, y -> compareChunks(x.second, y.second) })
        .map { it.first }
    return (if (mode == 5) byName else byName.asReversed()).sortedByDescending { it.pinned }
}

private fun orderCompare(pa: Boolean, ia: Int, pb: Boolean, ib: Int): Int {
    if (pa != pb) return if (pa) -1 else 1
    val ra = if (ia > 0) ia else Int.MAX_VALUE
    val rb = if (ib > 0) ib else Int.MAX_VALUE
    return ra.compareTo(rb)
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

enum class PdfMenuAction { Pin, Rename, Move, Copy, Remove, Delete, Share, OpenIn, Print, ToggleRead }

sealed interface PdfDlg {
    class Rename(val pdf: PdfEntity) : PdfDlg
    class Move(val ids: List<Long>, val folderIds: List<Long> = emptyList()) : PdfDlg
    class RemoveItems(val pdfIds: List<Long>, val folderIds: List<Long>) : PdfDlg
    class Copy(val ids: List<Long>) : PdfDlg
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

/** A quiet brand bar: just the logo mark, so the page title below stays the hero. [onSearch] null = no search icon; [onBack] adds a back arrow (inside folders); [onRefresh] adds a rescan button that spins while [refreshing]. */
@Composable
fun AppHeader(onSearch: (() -> Unit)?, onBack: (() -> Unit)? = null, onRefresh: (() -> Unit)? = null, refreshing: Boolean = false) {
    val pal = LocalPal.current
    Row(Modifier.fillMaxWidth().height(56.dp).padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = pal.ink) }
        LogoMark(30.dp)
        Spacer(Modifier.weight(1f))
        if (onRefresh != null) RefreshButton(refreshing, onRefresh)
        if (onSearch != null) IconButton(onClick = onSearch) { Icon(Icons.Rounded.Search, "Search", tint = pal.ink) }
    }
}

/** Spins while a scan runs, and for at least a moment after a tap so a very fast scan still feels acknowledged. */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    val rot = remember { androidx.compose.animation.core.Animatable(0f) }
    var kick by remember { mutableStateOf(false) }
    LaunchedEffect(kick) { if (kick) { kotlinx.coroutines.delay(800); kick = false } }
    val spinning = refreshing || kick
    LaunchedEffect(spinning) {
        if (spinning) {
            while (true) rot.animateTo(rot.value + 360f, androidx.compose.animation.core.tween(800, easing = androidx.compose.animation.core.LinearEasing))
        } else {
            val rest = kotlin.math.ceil(rot.value / 360f) * 360f
            rot.animateTo(rest, androidx.compose.animation.core.tween(250))
            rot.snapTo(0f)
        }
    }
    IconButton(onClick = { kick = true; onClick() }) {
        Icon(
            Icons.Rounded.Refresh, "Refresh library", tint = if (spinning) pal.accent else pal.ink,
            modifier = Modifier.graphicsLayer { rotationZ = rot.value }
        )
    }
}

@Composable
fun ViewToggle(grid: Boolean, onChange: (Boolean) -> Unit) {
    val pal = LocalPal.current
    Row(Modifier.clip(RoundedCornerShape(50)).background(pal.chip).padding(3.dp)) {
        listOf(false to Icons.Rounded.FormatListBulleted, true to Icons.Rounded.GridView).forEach { (g, icon) ->
            val on = grid == g
            Box(
                Modifier.size(34.dp, 28.dp).clip(RoundedCornerShape(50)).background(if (on) pal.surface else Color.Transparent).clickable { onChange(g) },
                contentAlignment = Alignment.Center
            ) { Icon(icon, if (g) "Grid view" else "List view", tint = if (on) pal.accent else pal.soft, modifier = Modifier.size(18.dp)) }
        }
    }
}

/**
 * Grid preview: the WHOLE first page is shown, never cropped. A wide smartboard slide lies down, a tall book stands up,
 * both centred inside the same square frame with a thin page edge.
 */
@Composable
fun GridThumb(pdf: PdfEntity, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    var loaded by remember(pdf.id, pdf.size) { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, pdf.id, pdf.size) { value = Thumbs.get(ctx, pdf); loaded = true }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(14.dp)).background(pal.chip).then(if (!loaded) Modifier.shimmer(pal.chip, pal.surface) else Modifier), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null && b.height > 0) {
            val fade = remember(b) { Animatable(0f) }
            LaunchedEffect(b) { fade.animateTo(1f, tween(220)) }
            val r = b.width.toFloat() / b.height
            val maxW = maxWidth * 0.84f
            val maxH = maxHeight * 0.84f
            val w = if (maxW / r <= maxH) maxW else maxH * r
            val h = w / r
            Image(
                b.asImageBitmap(), null,
                Modifier.size(w, h).graphicsLayer { alpha = fade.value }.clip(RoundedCornerShape(3.dp)).border(1.dp, pal.line, RoundedCornerShape(3.dp)),
                contentScale = ContentScale.FillBounds
            )
        } else if (loaded) Text("PDF", fontSize = 12.sp, color = pal.soft, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun PdfGridCard(
    pdf: PdfEntity, selecting: Boolean, selected: Boolean,
    onClick: () -> Unit, onLong: () -> Unit, onFav: () -> Unit,
    onMore: ((PdfMenuAction) -> Unit)?, modifier: Modifier = Modifier
) {
    val pal = LocalPal.current
    val finished = pdf.done && !pdf.missing
    val started = (finished || (pdf.pageCount > 0 && pdf.lastPage > 0)) && !pdf.missing
    var menu by remember { mutableStateOf(false) }
    val meta = if (pdf.missing) "File not found" else if (finished) "Finished" else if (started) "p. ${pdf.lastPage + 1} of ${pdf.pageCount}" else formatSize(pdf.size)
    Column(
        modifier.padding(horizontal = 4.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) pal.accent.copy(alpha = 0.10f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(6.dp)
    ) {
        Box {
            GridThumb(pdf, Modifier.fillMaxWidth().aspectRatio(1f))
            if (pdf.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = pal.accent, modifier = Modifier.align(Alignment.TopStart).padding(9.dp).size(16.dp))
            if (selecting) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape)
                        .background(if (selected) pal.accent else pal.surface.copy(alpha = 0.85f))
                        .border(1.5.dp, if (selected) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { if (selected) Icon(Icons.Rounded.Check, null, tint = pal.onAccent, modifier = Modifier.size(16.dp)) }
            } else {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(32.dp).clip(CircleShape)
                        .background(pal.surface.copy(alpha = 0.85f)).clickable(onClick = onFav),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (pdf.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Favorite",
                        tint = if (pdf.favorite) pal.hi else pal.soft, modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (started) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                ReadBar(pdf.readFraction(), done = finished)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(
                    pdf.title(), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, lineHeight = 17.sp,
                    fontWeight = FontWeight.Medium, color = if (pdf.missing) pal.soft else pal.ink
                )
                Spacer(Modifier.height(2.dp))
                Text(meta, fontSize = 12.sp, color = if (pdf.missing) pal.accent else pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (onMore != null && !selecting) {
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.soft, modifier = Modifier.size(18.dp)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        PdfMenuItems(pdf, { menu = false }, onMore)
                    }
                }
            }
        }
    }
}

@Composable
fun SortMenu(mode: Int, onPick: (Int) -> Unit) {
    val pal = LocalPal.current
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clip(RoundedCornerShape(50)).clickable { open = true }.padding(start = 10.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.SwapVert, null, tint = pal.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(SortLabels[mode.coerceIn(0, SortLabels.lastIndex)], fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.ink)
            Icon(Icons.Rounded.ArrowDropDown, null, tint = pal.soft, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text("Sort by", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.soft, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            SortLabels.forEachIndexed { i, label ->
                val on = i == mode
                DropdownMenuItem(
                    leadingIcon = { if (on) Icon(Icons.Rounded.Check, null, tint = pal.accent) else Spacer(Modifier.size(24.dp)) },
                    text = {
                        Column {
                            Text(label, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, color = if (on) pal.accent else pal.ink)
                            if (i == SORT_CUSTOM) Text("Hold and drag to rearrange", fontSize = 12.sp, color = pal.soft)
                        }
                    },
                    onClick = { open = false; onPick(i) }
                )
            }
        }
    }
}

@Composable
fun ReadBar(fraction: Float, modifier: Modifier = Modifier, done: Boolean = false) {
    val pal = LocalPal.current
    Box(modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(pal.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(if (done) FinishedGreen else pal.accent))
    }
}

val FinishedGreen = Color(0xFF2E9E6B)

/** The ⋮ menu of a PDF, grouped: send it elsewhere, reading status, then organising. */
@Composable
private fun PdfMenuItems(pdf: PdfEntity, close: () -> Unit, onMore: (PdfMenuAction) -> Unit) {
    val pal = LocalPal.current
    fun go(a: PdfMenuAction) { close(); onMore(a) }
    val read = pdf.status() == ReadStatus.Finished
    MenuItem("Share…", Icons.Rounded.Share) { go(PdfMenuAction.Share) }
    MenuItem("Open with…", Icons.Rounded.OpenInNew) { go(PdfMenuAction.OpenIn) }
    MenuItem("Print", Icons.Rounded.Print) { go(PdfMenuAction.Print) }
    HorizontalDivider(color = pal.line)
    MenuItem(if (read) "Mark as unread" else "Mark as read", if (read) Icons.Rounded.Replay else Icons.Rounded.TaskAlt) { go(PdfMenuAction.ToggleRead) }
    MenuItem(if (pdf.pinned) "Unpin" else "Pin to top", Icons.Rounded.PushPin) { go(PdfMenuAction.Pin) }
    HorizontalDivider(color = pal.line)
    MenuItem("Rename", Icons.Rounded.Edit) { go(PdfMenuAction.Rename) }
    MenuItem("Move to…", Icons.Rounded.DriveFileMove) { go(PdfMenuAction.Move) }
    MenuItem("Copy to…", Icons.Rounded.ContentCopy) { go(PdfMenuAction.Copy) }
    MenuItem("Remove from Shelf", Icons.Rounded.VisibilityOff) { go(PdfMenuAction.Remove) }
    MenuItem("Move to Trash", Icons.Rounded.Delete, danger = true) { go(PdfMenuAction.Delete) }
}

@Composable
fun MenuItem(label: String, icon: ImageVector, danger: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPal.current
    DropdownMenuItem(
        text = { Text(label, color = if (danger) pal.accent else pal.ink) },
        leadingIcon = { Icon(icon, null, tint = if (danger) pal.accent else pal.soft, modifier = Modifier.size(20.dp)) },
        onClick = onClick
    )
}

/** Fixed slot sizes, so titles line up whatever the page shape is. */
val ThumbSlot = DpSize(80.dp, 68.dp)
val ThumbSlotLarge = DpSize(88.dp, 80.dp)

/**
 * First-page preview. The slot never changes size; the page is drawn inside it at its real proportions
 * (tall for books, wide for slides) on a soft mat, so landscape and portrait PDFs both look deliberate, never cropped.
 */
@Composable
fun Thumb(pdf: PdfEntity, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val pal = LocalPal.current
    var loaded by remember(pdf.id, pdf.size) { mutableStateOf(false) }
    val bmp by produceState<Bitmap?>(null, pdf.id, pdf.size) { value = Thumbs.get(ctx, pdf); loaded = true }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(10.dp)).background(pal.chip).then(if (!loaded) Modifier.shimmer(pal.chip, pal.surface) else Modifier), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null && b.width > 0 && b.height > 0) {
            val fade = remember(b) { Animatable(0f) }
            LaunchedEffect(b) { fade.animateTo(1f, tween(220)) }
            val ratio = b.width.toFloat() / b.height
            val inset = 3.dp
            val boxW = maxWidth - inset * 2
            val boxH = maxHeight - inset * 2
            val w = minOf(boxW, boxH * ratio)
            val h = w / ratio
            val page = RoundedCornerShape(3.dp)
            Image(
                b.asImageBitmap(), null,
                Modifier.size(w, h).graphicsLayer { alpha = fade.value }.shadow(1.5.dp, page).clip(page).border(0.5.dp, pal.line, page),
                contentScale = ContentScale.FillBounds
            )
        } else if (loaded) Text("PDF", fontSize = 11.sp, color = pal.soft.copy(alpha = 0.7f), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun PdfRow(
    pdf: PdfEntity, place: String?, selecting: Boolean, selected: Boolean,
    onClick: () -> Unit, onLong: (() -> Unit)? = null, onFav: () -> Unit,
    onMore: ((PdfMenuAction) -> Unit)? = null,
    modifier: Modifier = Modifier,
    highlight: List<String> = emptyList()
) {
    val pal = LocalPal.current
    val finished = pdf.done && !pdf.missing
    val started = (finished || (pdf.pageCount > 0 && pdf.lastPage > 0)) && !pdf.missing
    var menu by remember { mutableStateOf(false) }
    val meta = listOfNotNull(
        place, if (pdf.missing) "File not found" else formatSize(pdf.size),
        if (finished) "Finished" else if (started) "p. ${pdf.lastPage + 1} of ${pdf.pageCount}" else null
    ).joinToString(" · ")
    Row(
        modifier.fillMaxWidth()
            .background(if (selected) pal.accent.copy(alpha = 0.10f) else Color.Transparent)
            .then(if (onLong != null) Modifier.combinedClickable(onClick = onClick, onLongClick = onLong) else Modifier.clickable(onClick = onClick))
            .padding(start = 16.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Thumb(pdf, Modifier.size(ThumbSlot))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Top) {
                if (pdf.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = pal.accent, modifier = Modifier.padding(top = 3.dp, end = 5.dp).size(14.dp))
                Text(highlightText(pdf.title(), highlight, pal.accent), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, color = if (pdf.missing) pal.soft else pal.ink)
            }
            Spacer(Modifier.height(3.dp))
            Text(meta, fontSize = 12.sp, color = if (pdf.missing) pal.accent else pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (started) { Spacer(Modifier.height(7.dp)); ReadBar(pdf.readFraction(), Modifier.padding(end = 12.dp), done = finished) }
        }
        if (selecting) {
            Box(Modifier.padding(horizontal = 12.dp).size(24.dp).clip(CircleShape)
                .background(if (selected) pal.accent else Color.Transparent)
                .border(1.5.dp, if (selected) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = pal.onAccent, modifier = Modifier.size(16.dp))
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
                        PdfMenuItems(pdf, { menu = false }, onMore)
                    }
                }
            } else Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
fun FolderRow(
    f: FolderEntity, subtitle: String, onClick: () -> Unit,
    onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit,
    onPin: () -> Unit = {}, modifier: Modifier = Modifier,
    selecting: Boolean = false, selected: Boolean = false,
    onFav: (() -> Unit)? = null, menu: Boolean = true
) {
    val pal = LocalPal.current
    val tone = FolderTones[f.color.mod(FolderTones.size)]
    var open by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth()
            .background(if (selected) pal.accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = tone, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (f.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = pal.accent, modifier = Modifier.padding(end = 5.dp).size(14.dp))
                Text(f.name, fontFamily = Serif, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (f.link != null) Icon(Icons.Rounded.PhoneAndroid, "A folder on your phone", tint = pal.soft, modifier = Modifier.padding(start = 6.dp).size(14.dp))
            }
            Text(subtitle, fontSize = 12.sp, color = pal.soft)
        }
        if (selecting) {
            Box(
                Modifier.padding(horizontal = 12.dp).size(24.dp).clip(CircleShape)
                    .background(if (selected) pal.accent else Color.Transparent)
                    .border(1.5.dp, if (selected) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = pal.onAccent, modifier = Modifier.size(16.dp))
            }
        } else {
            if (onFav != null) {
                IconButton(onClick = onFav, modifier = Modifier.size(40.dp)) {
                    Icon(
                        if (f.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (f.favorite) "Remove from Saved" else "Save folder",
                        tint = if (f.favorite) pal.hi else pal.soft.copy(alpha = 0.6f)
                    )
                }
            }
            if (menu) {
                Box {
                    IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.soft) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text(if (f.pinned) "Unpin" else "Pin to top") }, onClick = { open = false; onPin() })
                        DropdownMenuItem(text = { Text("Rename / colour") }, onClick = { open = false; onEdit() })
                        DropdownMenuItem(text = { Text("Move to…") }, onClick = { open = false; onMove() })
                        DropdownMenuItem(text = { Text("Remove folder", color = pal.accent) }, onClick = { open = false; onDelete() })
                    }
                }
            } else Spacer(Modifier.width(8.dp))
        }
    }
}

@Composable
fun FolderGridCard(
    f: FolderEntity, subtitle: String, onClick: () -> Unit,
    onEdit: () -> Unit, onMove: () -> Unit, onDelete: () -> Unit,
    onPin: () -> Unit = {}, modifier: Modifier = Modifier,
    selecting: Boolean = false, selected: Boolean = false,
    onFav: (() -> Unit)? = null
) {
    val pal = LocalPal.current
    val tone = FolderTones[f.color.mod(FolderTones.size)]
    var open by remember { mutableStateOf(false) }
    Column(
        modifier.padding(horizontal = 4.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp))
            .background(if (selected) pal.accent.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(6.dp)
    ) {
        Box {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1.4f).clip(RoundedCornerShape(14.dp)).background(tone.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.Folder, null, tint = tone, modifier = Modifier.size(56.dp)) }
            if (f.pinned) Icon(Icons.Rounded.PushPin, "Pinned", tint = pal.accent, modifier = Modifier.align(Alignment.TopStart).padding(9.dp).size(16.dp))
            if (selecting) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp).clip(CircleShape)
                        .background(if (selected) pal.accent else pal.surface.copy(alpha = 0.85f))
                        .border(1.5.dp, if (selected) pal.accent else pal.soft.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) { if (selected) Icon(Icons.Rounded.Check, null, tint = pal.onAccent, modifier = Modifier.size(16.dp)) }
            } else if (onFav != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(5.dp).size(32.dp).clip(CircleShape)
                        .background(pal.surface.copy(alpha = 0.85f)).clickable(onClick = onFav),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (f.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, if (f.favorite) "Remove from Saved" else "Save folder",
                        tint = if (f.favorite) pal.hi else pal.soft, modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(f.name, fontFamily = Serif, fontSize = 15.sp, lineHeight = 19.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (f.link != null) Icon(Icons.Rounded.PhoneAndroid, "A folder on your phone", tint = pal.soft, modifier = Modifier.padding(start = 5.dp).size(13.dp))
                }
                Spacer(Modifier.height(2.dp))
                Text(subtitle, fontSize = 12.sp, color = pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (!selecting) {
                Box {
                    IconButton(onClick = { open = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.MoreVert, "More", tint = pal.soft, modifier = Modifier.size(18.dp)) }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        DropdownMenuItem(text = { Text(if (f.pinned) "Unpin" else "Pin to top") }, onClick = { open = false; onPin() })
                        DropdownMenuItem(text = { Text("Rename / colour") }, onClick = { open = false; onEdit() })
                        DropdownMenuItem(text = { Text("Move to…") }, onClick = { open = false; onMove() })
                        DropdownMenuItem(text = { Text("Remove folder", color = pal.accent) }, onClick = { open = false; onDelete() })
                    }
                }
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
fun FolderDialog(
    title: String, initialName: String, initialColor: Int, confirm: String, onDismiss: () -> Unit,
    check: (suspend (String) -> String?)? = null, onOk: (String, Int) -> Unit
) {
    val pal = LocalPal.current
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableIntStateOf(initialColor) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(name) {
        problem = null
        if (check != null && name.isNotBlank() && name != initialName) { delay(250); problem = check(name) }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.surface,
        title = { Text(title, fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                OutlinedTextField(
                    name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(fr),
                    placeholder = { Text("e.g. Physics") }, isError = problem != null,
                    supportingText = { val pr = problem; if (pr != null) Text(pr) },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (name.isNotBlank() && problem == null) onOk(name.trim(), color) })
                )
                Spacer(Modifier.height(16.dp))
                val toneNames = listOf("Red", "Amber", "Green", "Teal", "Blue", "Plum")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FolderTones.forEachIndexed { i, c ->
                        // 34dp circles with a clear tick; each is announced by name and as selected or not.
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(c)
                                .selectable(selected = color == i, role = Role.RadioButton) { color = i }
                                .semantics { contentDescription = toneNames.getOrElse(i) { "Colour ${i + 1}" } },
                            contentAlignment = Alignment.Center
                        ) {
                            if (color == i) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank() && problem == null, onClick = { onOk(name.trim(), color) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/**
 * The "Move to" sheet. Browse like a file manager: tap a folder to open it, the breadcrumb shows where you are,
 * search jumps straight to any folder, and one big button moves everything into the folder you are looking at.
 * [current] marks where the items live now, so "Already in this folder" is never a surprise.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderPicker(
    folders: List<FolderEntity>, exclude: Set<Long>, title: String, onPick: (Long?) -> Unit, onDismiss: () -> Unit,
    subtitle: String? = null, current: Set<Long?> = emptySet(), stats: FolderStats? = null,
    onNewFolder: ((String, Int, Long?) -> Unit)? = null,
    phoneOf: (Long) -> Boolean = { false }, needPhone: Boolean = false,
    nameCheck: (suspend (Long?, String) -> String?)? = null
) {
    val pal = LocalPal.current
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var at by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var forward by remember { mutableStateOf(true) }
    var creating by remember { mutableStateOf(false) }

    val byId = remember(folders) { folders.associateBy { it.id } }
    val paths = remember(folders) { folderPaths(folders) }
    val here = at?.takeIf { it in byId && it !in exclude }
    val trail = remember(here, byId) {
        val out = ArrayList<FolderEntity>()
        var cur: Long? = here
        while (cur != null && out.size < 50) {
            val f = byId[cur] ?: break
            out.add(0, f)
            cur = f.parentId
        }
        out
    }
    val tokens = remember(query) { query.lowercase().split(' ').filter { it.isNotBlank() } }
    val searching = tokens.isNotEmpty()
    val hereName = trail.lastOrNull()?.name ?: "Library"
    val same = !needPhone && current.isNotEmpty() && current.all { it == here }
    val onPhone = here != null && phoneOf(here)
    val blocked = needPhone && !onPhone

    fun depth(id: Long?): Int {
        var d = 0
        var cur: Long? = id
        while (cur != null && d < 50) {
            val f = byId[cur] ?: break
            d++
            cur = f.parentId
        }
        return d
    }
    fun go(id: Long?) { forward = depth(id) >= depth(here); at = id; query = "" }
    fun finish(after: () -> Unit) { scope.launch { sheet.hide() }.invokeOnCompletion { after() } }

    BackHandler(enabled = searching || here != null) {
        if (searching) query = "" else go(trail.lastOrNull()?.parentId)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = pal.surface) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).navigationBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, color = pal.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (onNewFolder != null) {
                    IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.CreateNewFolder, "New folder", tint = pal.accent) }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(44.dp).clip(RoundedCornerShape(22.dp)).background(pal.chip).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = pal.soft, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    BasicTextField(
                        query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(color = pal.ink, fontSize = 15.sp), cursorBrush = SolidColor(pal.accent),
                        decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search folders", color = pal.soft, fontSize = 15.sp); inner() } }
                    )
                }
                if (query.isNotEmpty()) {
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { query = "" }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Close, "Clear search", tint = pal.soft, modifier = Modifier.size(20.dp))
                    }
                }
            }
            if (searching) {
                Spacer(Modifier.height(14.dp))
            } else {
                val scroll = rememberScrollState()
                LaunchedEffect(trail.size) { scroll.animateScrollTo(scroll.maxValue) }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Crumb("Library", trail.isEmpty(), home = true) { go(null) }
                    trail.forEachIndexed { i, f ->
                        Icon(Icons.Rounded.ChevronRight, null, tint = pal.soft, modifier = Modifier.size(18.dp))
                        Crumb(f.name, i == trail.lastIndex) { go(f.id) }
                    }
                }
            }

            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = if (searching) -1L else (here ?: 0L),
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = {
                        val dir = if (forward) 1 else -1
                        (fadeIn(tween(180)) + slideInHorizontally(tween(220)) { dir * it / 5 }) togetherWith
                            (fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { -dir * it / 5 })
                    },
                    label = "pickerLevel"
                ) { lv ->
                    if (lv == -1L) {
                        val results = folders.filter { f -> f.id !in exclude && tokens.all { it in f.name.lowercase() } }
                            .sortedBy { (paths[it.id] ?: it.name).lowercase() }.take(60)
                        if (results.isEmpty()) {
                            Hint("No folders match", "Try fewer or shorter words.")
                        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                            items(results, key = { it.id }) { f ->
                                PickFolderRow(f, paths[f.id], f.id in current, phoneOf(f.id)) { go(f.id) }
                            }
                        }
                    } else {
                        val parent: Long? = if (lv == 0L) null else lv
                        val kids = folders.filter { it.parentId == parent && it.id !in exclude }.sortedBy { it.name.lowercase() }
                        if (kids.isEmpty()) {
                            Hint(
                                "No subfolders here",
                                if (onNewFolder != null) "Move into this folder, or create a new one with the folder button above."
                                else "You can move into this folder."
                            )
                        } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 8.dp)) {
                            items(kids, key = { it.id }) { f ->
                                PickFolderRow(f, stats?.subtitle(f.id), f.id in current, phoneOf(f.id)) { go(f.id) }
                            }
                        }
                    }
                }
            }

            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
            if (onPhone) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.PhoneAndroid, null, tint = pal.soft, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (needPhone) "A real copy is made on your phone." else "Files and folders really move on your phone.",
                        fontSize = 12.sp, color = pal.soft
                    )
                }
            } else if (needPhone) {
                Text(
                    "Copy works on phone folders. Open a folder marked with the phone icon.",
                    fontSize = 12.sp, color = pal.soft, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp)
                )
            }
            Button(
                onClick = { finish { onPick(here) } }, enabled = !same && !blocked,
                modifier = Modifier.padding(16.dp).fillMaxWidth().height(52.dp), shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = pal.accent, contentColor = pal.onAccent,
                    disabledContainerColor = pal.chip, disabledContentColor = pal.soft
                )
            ) {
                Icon(Icons.Rounded.FolderOpen, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (blocked) "Choose a phone folder" else if (same) "Already in “$hereName”" else if (needPhone) "Copy to “$hereName”" else "Move to “$hereName”",
                    fontWeight = FontWeight.SemiBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    if (creating && onNewFolder != null) {
        FolderDialog(
            "New folder", "", folders.count { it.parentId == here } % FolderTones.size, "Create", { creating = false },
            check = { typed -> val nc = nameCheck; if (nc != null) nc(here, typed) else null }
        ) { n, c ->
            onNewFolder(n, c, here); creating = false
        }
    }
}

@Composable
private fun Crumb(text: String, last: Boolean, home: Boolean = false, onClick: () -> Unit) {
    val pal = LocalPal.current
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (last) pal.accent.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(enabled = !last, onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (home) {
            Icon(Icons.Rounded.Home, null, tint = if (last) pal.accent else pal.soft, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(text, fontSize = 13.sp, fontWeight = if (last) FontWeight.SemiBold else FontWeight.Medium, color = if (last) pal.accent else pal.ink, maxLines = 1)
    }
}

@Composable
private fun PickFolderRow(f: FolderEntity, sub: String?, isCurrent: Boolean, phone: Boolean, onClick: () -> Unit) {
    val pal = LocalPal.current
    val tone = FolderTones[f.color.mod(FolderTones.size)]
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(tone.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Folder, null, tint = tone, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(f.name, fontFamily = Serif, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, fontSize = 12.sp, color = pal.soft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (isCurrent) {
            Spacer(Modifier.width(8.dp))
            Text(
                "Current", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(pal.accent.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
        if (phone) Icon(Icons.Rounded.PhoneAndroid, "A folder on your phone", tint = pal.soft, modifier = Modifier.padding(start = 8.dp).size(16.dp))
        Spacer(Modifier.width(4.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = pal.soft)
    }
}

@Composable
fun RenameDialog(initial: String, onDismiss: () -> Unit, check: (suspend (String) -> String?)? = null, onOk: (String) -> Unit) {
    val pal = LocalPal.current
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length))) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }
    var problem by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(text.text) {
        problem = null
        if (check != null && text.text.isNotBlank()) { delay(250); problem = check(text.text) }
    }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = pal.surface,
        title = { Text("Rename PDF", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                text, { text = it }, singleLine = true, isError = problem != null,
                supportingText = { val pr = problem; if (pr != null) Text(pr) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (text.text.isNotBlank() && problem == null) onOk(text.text.trim()) }),
                modifier = Modifier.fillMaxWidth().focusRequester(fr)
            )
        },
        confirmButton = { TextButton(enabled = text.text.isNotBlank() && problem == null, onClick = { onOk(text.text.trim()) }) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** One place for every PDF dialog, so Library, Recent, Saved and Search all behave the same. */
@Composable
fun PdfDialogHost(vm: LibraryVm) {
    val pal = LocalPal.current
    val folders by vm.folders.collectAsState()
    val allPdfs by vm.pdfs.collectAsState()
    when (val d = vm.pdfDialog) {
        is PdfDlg.Rename -> RenameDialog(d.pdf.title(), { vm.pdfDialog = null }, check = { t -> vm.renameProblem(d.pdf, t) }) { n -> vm.renamePdf(d.pdf, n); vm.pdfDialog = null }
        is PdfDlg.Move -> {
            val stats = remember(allPdfs, folders) { FolderStats(allPdfs, folders) }
            val exclude = remember(d.folderIds, folders) { d.folderIds.flatMap { descendants(it, folders) }.toSet() }
            val cur = remember(d.ids, d.folderIds, allPdfs, folders) {
                val pi = d.ids.toHashSet()
                val fi = d.folderIds.toHashSet()
                allPdfs.filter { it.id in pi }.map { it.folderId }.toSet() + folders.filter { it.id in fi }.map { it.parentId }.toSet()
            }
            val n = d.ids.size + d.folderIds.size
            val one = when {
                n != 1 -> null
                d.folderIds.size == 1 -> folders.firstOrNull { it.id == d.folderIds[0] }?.name
                else -> allPdfs.firstOrNull { it.id == d.ids[0] }?.title()
            }
            val noun = if (d.folderIds.isEmpty()) "PDFs" else if (d.ids.isEmpty()) "folders" else "items"
            FolderPicker(
                folders, exclude, if (n == 1) "Move to" else "Move $n $noun to",
                { vm.moveItems(d.ids, d.folderIds, it); vm.clearSel(); vm.pdfDialog = null }, { vm.pdfDialog = null },
                subtitle = one, current = cur, stats = stats, onNewFolder = { nm, c, par -> vm.createFolder(nm, c, par) },
                phoneOf = { id -> folders.firstOrNull { it.id == id }?.link != null },
                nameCheck = { parent, typed -> vm.folderNameProblem(parent, typed, null) }
            )
        }
        is PdfDlg.Copy -> {
            val stats = remember(allPdfs, folders) { FolderStats(allPdfs, folders) }
            val one = if (d.ids.size == 1) allPdfs.firstOrNull { it.id == d.ids[0] }?.title() else null
            FolderPicker(
                folders, emptySet(), if (d.ids.size == 1) "Copy to" else "Copy ${d.ids.size} PDFs to",
                { id -> if (id != null) vm.copyPdfs(d.ids, id); vm.clearSel(); vm.pdfDialog = null }, { vm.pdfDialog = null },
                subtitle = one, stats = stats, onNewFolder = { nm, c, par -> vm.createFolder(nm, c, par) },
                phoneOf = { id -> folders.firstOrNull { it.id == id }?.link != null }, needPhone = true,
                nameCheck = { parent, typed -> vm.folderNameProblem(parent, typed, null) }
            )
        }
        is PdfDlg.RemoveItems -> {
            val n = d.pdfIds.size + d.folderIds.size
            val what = if (n == 1) (if (d.folderIds.isNotEmpty()) "this folder" else "this PDF") else "these $n items"
            AlertDialog(
                onDismissRequest = { vm.pdfDialog = null }, containerColor = pal.surface,
                title = { Text("Remove $what from Shelf?", fontFamily = Serif, fontWeight = FontWeight.SemiBold) },
                text = {
                    Text(
                        (if (d.folderIds.isNotEmpty()) "A removed folder takes everything inside it along. " else "") +
                            "Your files stay on your phone, and you can restore everything any time in Settings › Your library."
                    )
                },
                confirmButton = { TextButton(onClick = { vm.removeItems(d.pdfIds, d.folderIds); vm.pdfDialog = null }) { Text("Remove", color = pal.accent) } },
                dismissButton = { TextButton(onClick = { vm.pdfDialog = null }) { Text("Cancel") } }
            )
        }
        is PdfDlg.Delete -> {
            val one = d.ids.size == 1
            AlertDialog(
                onDismissRequest = { vm.pdfDialog = null }, containerColor = pal.surface,
                title = {
                    Text(if (one) "Move “${d.title}” to Trash?" else "Move ${d.ids.size} PDFs to Trash?", fontFamily = Serif, fontWeight = FontWeight.SemiBold)
                },
                text = {
                    Text(
                        if (one) "Nothing is erased yet: the file moves to a hidden Trash folder on your phone. Restore it, or delete it forever, in Settings › Trash."
                        else "Nothing is erased yet: the files move to a hidden Trash folder on your phone. Restore them, or delete them forever, in Settings › Trash."
                    )
                },
                confirmButton = { TextButton(onClick = { vm.trashPdfs(d.ids); vm.pdfDialog = null }) { Text("Move to Trash", color = pal.accent) } },
                dismissButton = { TextButton(onClick = { vm.pdfDialog = null }) { Text("Cancel") } }
            )
        }
        null -> {}
    }
}


/** Bolds and tints the parts of [text] that match the search words. */
fun highlightText(text: String, tokens: List<String>, color: Color): AnnotatedString {
    if (tokens.isEmpty()) return AnnotatedString(text)
    val lower = text.lowercase()
    if (lower.length != text.length) return AnnotatedString(text)
    val hit = BooleanArray(text.length)
    for (t in tokens) {
        if (t.isEmpty()) continue
        var i = lower.indexOf(t)
        while (i >= 0) {
            for (k in i until minOf(i + t.length, text.length)) hit[k] = true
            i = lower.indexOf(t, i + t.length)
        }
    }
    return buildAnnotatedString {
        var k = 0
        while (k < text.length) {
            var e = k
            while (e < text.length && hit[e] == hit[k]) e++
            if (hit[k]) withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append(text.substring(k, e)) }
            else append(text.substring(k, e))
            k = e
        }
    }
}


/** A soft light sweep for placeholders: tells you something is loading, and keeps the layout from jumping when it arrives. */
fun Modifier.shimmer(base: Color, glow: Color): Modifier = composed {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-0.6f, 1.6f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "shimmerX")
    drawBehind {
        val w = size.width
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, glow.copy(alpha = 0.75f), Color.Transparent),
                start = Offset(x * w - w * 0.4f, 0f), end = Offset(x * w + w * 0.4f, size.height * 0.4f)
            )
        )
    }
}
