package com.pdflib

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

/** A short "done · Undo" message shown above the bottom bar. */
class UndoNote(val message: String, val action: () -> Unit)

/** A removed folder as Settings lists it: what it holds, so people know what Restore brings back. */
class RemovedFolder(val folder: FolderEntity, val pdfs: Int, val subfolders: Int)

/** Ids of folders that are removed, plus everything inside a removed folder. */
private fun hiddenFolderIds(all: List<FolderEntity>): Set<Long> {
    val kids = all.groupBy { it.parentId }
    val out = HashSet<Long>()
    fun mark(id: Long) { if (out.add(id)) kids[id]?.forEach { mark(it.id) } }
    all.filter { it.removed }.forEach { mark(it.id) }
    return out
}

/** The removed folders that were removed themselves (not just because a parent was). */
private fun removedRoots(all: List<FolderEntity>): List<FolderEntity> {
    val byId = all.associateBy { it.id }
    fun blocked(f: FolderEntity): Boolean {
        var p: Long? = f.parentId
        var n = 0
        while (n < 50) {
            val pid = p ?: return false
            val pf = byId[pid] ?: return false
            if (pf.removed) return true
            p = pf.parentId
            n++
        }
        return false
    }
    return all.filter { it.removed && !blocked(it) }.sortedBy { it.name.lowercase() }
}

class LibraryVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val db = AppDb.get(ctx)
    private val dao = db.dao()
    private val prefs = ctx.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    var loaded by mutableStateOf(false)
    private val allFolders = dao.folders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Folders you can see: not removed, and not inside a removed folder. */
    val folders = allFolders.map { l -> val gone = hiddenFolderIds(l); l.filter { it.id !in gone } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val allPdfs = dao.pdfs().onEach { loaded = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** What the app shows: everything except PDFs the user removed from Shelf. */
    val pdfs = combine(allPdfs, allFolders) { p, f ->
        val gone = hiddenFolderIds(f)
        p.filter { pdf -> val fid = pdf.folderId; !pdf.hidden && (fid == null || fid !in gone) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Removed folders waiting in Settings, with how many PDFs come back with each. */
    val removedFolders = combine(allFolders, allPdfs) { f, p ->
        val stats = FolderStats(p.filter { !it.hidden }, f)
        removedRoots(f).map { RemovedFolder(it, stats.pdfCount(it.id), stats.subCount(it.id)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hidden = allPdfs.map { l -> l.filter { it.hidden } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks = dao.bookmarks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Phone folders linked into Shelf folders. */
    val links = dao.links().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // navigation + UI state
    var tab by mutableIntStateOf(0)
    var readerId by mutableStateOf<Long?>(null)
    var searching by mutableStateOf(false)
    val stack = mutableStateListOf<Long>()
    val selection = mutableStateListOf<Long>()
    val folderSelection = mutableStateListOf<Long>()
    val selectedCount: Int get() = selection.size + folderSelection.size
    fun clearSel() { selection.clear(); folderSelection.clear() }
    fun toggleFolderSel(id: Long) { if (id in folderSelection) folderSelection.remove(id) else folderSelection.add(id) }
    val libState = LazyListState()

    // scanning
    var scanning by mutableStateOf(false)
    var scanFound by mutableIntStateOf(0)
    var sources by mutableStateOf<List<Uri>>(emptyList())
    var writable by mutableStateOf<Set<String>>(emptySet())
    private var again = false

    // dialogs + undo
    var pdfDialog by mutableStateOf<PdfDlg?>(null)
    var undo by mutableStateOf<UndoNote?>(null)

    var invert by mutableStateOf(prefs.getBoolean("invert", false))
    fun updateInvert(v: Boolean) { invert = v; prefs.edit().putBoolean("invert", v).apply() }

    var themeMode by mutableStateOf(ThemeMode.fromKey(prefs.getString("theme", null)))
    fun updateTheme(m: ThemeMode) { themeMode = m; prefs.edit().putString("theme", m.key).apply() }

    init {
        refreshSources()
        rescan()
    }

    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    // ---------- navigation ----------
    fun enter(id: Long) { clearSel(); stack.add(id) }
    fun up() { clearSel(); if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun toBreadcrumb(index: Int) { clearSel(); while (stack.size > index + 1) stack.removeAt(stack.lastIndex) }
    fun goToFolder(id: Long?) {
        clearSel(); stack.clear()
        if (id != null) {
            val map = folders.value.associateBy { it.id }
            val chain = ArrayList<Long>()
            var cur: Long? = id
            while (cur != null) { chain.add(cur); cur = map[cur]?.parentId }
            stack.addAll(chain.reversed())
        }
        tab = 0; searching = false
    }
    fun openReader(id: Long) { readerId = id; viewModelScope.launch { dao.touch(id, System.currentTimeMillis()) } }
    fun closeReader() { readerId = null }
    fun toggleSel(id: Long) { if (id in selection) selection.remove(id) else selection.add(id) }

    // ---------- sources & scanning ----------
    fun refreshSources() {
        val perms = ctx.contentResolver.persistedUriPermissions.filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
        sources = perms.map { it.uri }
        writable = perms.filter { it.isWritePermission }.mapTo(HashSet<String>()) { it.uri.toString() }
    }

    /** Asks for edit access too (needed to rename or delete real files); falls back to read-only. */
    private fun grant(uri: Uri) {
        val rw = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try { ctx.contentResolver.takePersistableUriPermission(uri, rw) }
        catch (e: Exception) {
            try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e2: Exception) { }
        }
    }

    fun addSource(uri: Uri) {
        grant(uri)
        refreshSources(); rescan()
    }

    /** Links a phone folder into a Shelf folder ([target] null = the Library). [mode] is LINK_MIRROR or LINK_FLAT. */
    fun linkFolder(uri: Uri, target: Long?, mode: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            grant(uri)
            val dup = dao.linksOnce().firstOrNull { it.uri == uri.toString() }
            if (dup == null) dao.insertLink(LinkEntity(uri = uri.toString(), target = target, mode = mode, name = treeFolderName(uri)))
            withContext(Dispatchers.Main) {
                refreshSources(); rescan()
                toast(if (dup == null) "Linked “${treeFolderName(uri)}”" else "That phone folder is already linked. Access refreshed.")
            }
        }
    }

    /** Picks an already linked folder again to bring back lost access or to allow renaming and deleting. */
    fun regrant(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            if (dao.linksOnce().none { it.uri == uri.toString() }) {
                withContext(Dispatchers.Main) { toast("That is a different folder. Choose the same linked folder again.") }
                return@launch
            }
            grant(uri)
            withContext(Dispatchers.Main) { refreshSources(); rescan(); toast("Access updated") }
        }
    }

    /** Stops reading a linked phone folder. Its PDFs stay in Shelf (marked not found) with their folders, stars and bookmarks. */
    fun unlink(link: LinkEntity) {
        viewModelScope.launch {
            dao.deleteLink(link.id)
            removeSource(Uri.parse(link.uri))
        }
    }

    /** Older installs had plain "sources": give each one a link that drops its PDFs into the Library, as before. */
    private suspend fun ensureLinks(roots: List<Uri>): List<LinkEntity> {
        val have = dao.linksOnce().mapTo(HashSet<String>()) { it.uri }
        for (r in roots) if (r.toString() !in have) dao.insertLink(LinkEntity(uri = r.toString(), target = null, mode = LINK_FLAT, name = treeFolderName(r)))
        return dao.linksOnce()
    }

    /** Stops watching a folder. Its PDFs keep their folders, stars and bookmarks; they are just flagged as not found. */
    fun removeSource(uri: Uri) {
        try {
            ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) { }
        refreshSources()
        viewModelScope.launch(Dispatchers.IO) {
            val prefix = uri.toString() + "/document/"
            val ids = dao.pdfsOnce().filter { it.uri.startsWith(prefix) }.map { it.id }
            ids.chunked(400).forEach { dao.setMissing(it, true) }
        }
    }

    /**
     * Syncs the library with the folders on the phone. It only ever ADDS, RELINKS or FLAGS rows. It never deletes one,
     * so a flaky scan (SD card out, permission hiccup) can't wipe your folders, stars, bookmarks or reading progress.
     */
    fun rescan() {
        if (scanning) { again = true; return }
        refreshSources()
        scanning = true; scanFound = 0
        val roots = sources
        val prefixes = roots.map { it.toString() + "/document/" }
        viewModelScope.launch(Dispatchers.IO) {
            val linkList = ensureLinks(roots)
            val folderRows = dao.foldersOnce()
            val folderIds = folderRows.mapTo(HashSet<Long>()) { it.id }
            val keyToId = HashMap<String, Long>()
            folderRows.forEach { f -> val k = f.link; if (k != null) keyToId[k] = f.id }
            suspend fun ensureFolder(key: String, name: String, parent: Long?): Long {
                keyToId[key]?.let { return it }
                val id = dao.insertFolder(FolderEntity(name = name, parentId = parent, color = keyToId.size % FolderTones.size, link = key))
                keyToId[key] = id
                return id
            }
            // Where a NEW pdf goes. Existing ones stay wherever you put them.
            suspend fun placeFor(link: LinkEntity?, rel: List<String>): Long? {
                if (link == null) return null
                val target = link.target?.takeIf { it in folderIds }
                if (link.mode == LINK_FLAT) return target
                val k = linkKey(link.uri)
                var parent: Long? = ensureFolder("$k|", link.name, target)
                var path = ""
                for (seg in rel) { path = if (path.isEmpty()) seg else "$path/$seg"; parent = ensureFolder("$k|$path", seg, parent) }
                return parent
            }
            var base = 0
            for (root in roots) {
                try {
                    val found = Scanner.scan(ctx, root) { scanFound = base + it }
                    base += found.size
                    val prefix = root.toString() + "/document/"
                    val link = linkList.firstOrNull { it.uri == root.toString() }
                    val rows = dao.pdfsOnce()
                    val known = rows.mapTo(HashSet<String>()) { it.uri }
                    val seen = found.mapTo(HashSet<String>()) { it.uri }
                    val inRoot = rows.filter { it.uri.startsWith(prefix) }

                    // Files that are back: clear the "not found" flag.
                    inRoot.filter { it.missing && it.uri in seen }.map { it.id }.chunked(400).forEach { dao.setMissing(it, false) }
                    // An empty result is more likely a failed scan than an emptied folder: change nothing.
                    if (found.isEmpty()) continue

                    // A file that was moved or renamed outside Shelf keeps its row: same folder, stars, bookmarks and progress.
                    // Rows from a folder you removed (or single files you added) can be matched too.
                    val candidates = rows.filter { r ->
                        r.uri !in seen && (r.uri.startsWith(prefix) || prefixes.none { r.uri.startsWith(it) })
                    }
                    val used = HashSet<Long>()
                    val fresh = ArrayList<PdfEntity>()
                    for (f in found) {
                        if (f.uri in known) continue
                        val old = candidates.firstOrNull { it.id !in used && it.name == f.name && it.size == f.size }
                            ?: candidates.firstOrNull { it.id !in used && f.size > 0 && it.size == f.size && it.modified == f.modified }
                        if (old != null) {
                            used.add(old.id)
                            dao.relink(old.id, f.uri, f.name, f.size, f.modified)
                        } else {
                            fresh.add(PdfEntity(uri = f.uri, name = f.name, size = f.size, modified = f.modified, folderId = placeFor(link, f.rel)))
                        }
                    }
                    fresh.chunked(200).forEach { dao.insertPdfs(it) }

                    // Gone from the phone: keep the row, flag it.
                    inRoot.filter { it.uri !in seen && it.id !in used && !it.missing }.map { it.id }
                        .chunked(400).forEach { dao.setMissing(it, true) }
                } catch (e: Exception) { }
            }
            withContext(Dispatchers.Main) {
                scanning = false
                if (again) { again = false; rescan() }
            }
        }
    }

    /** Single PDFs picked from storage; they land in the folder you are currently viewing. */
    fun addFiles(uris: List<Uri>, folderId: Long?) = viewModelScope.launch(Dispatchers.IO) {
        val known = dao.pdfsOnce().associateBy { it.uri }
        val fresh = ArrayList<PdfEntity>()
        val existing = ArrayList<Long>()
        for (u in uris) {
            grant(u)
            val have = known[u.toString()]
            if (have != null) { existing.add(have.id); continue }
            var name = "document.pdf"; var size = 0L
            try {
                ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { c.getString(0)?.let { name = it }; size = c.getLong(1) }
                }
            } catch (e: Exception) { }
            fresh.add(PdfEntity(uri = u.toString(), name = name, size = size, modified = System.currentTimeMillis(), folderId = folderId))
        }
        fresh.chunked(200).forEach { dao.insertPdfs(it) }
        existing.chunked(500).forEach { dao.movePdfs(it, folderId); dao.setHidden(it, false) }
        withContext(Dispatchers.Main) { toast("${uris.size} PDF" + (if (uris.size == 1) "" else "s") + " added") }
    }

    // ---------- folders ----------
    fun createFolder(name: String, color: Int, parent: Long?) = viewModelScope.launch {
        dao.insertFolder(FolderEntity(name = name, parentId = parent, color = color))
    }
    fun updateFolder(id: Long, name: String, color: Int) = viewModelScope.launch { dao.updateFolder(id, name, color) }
    private fun folderName(id: Long?): String = id?.let { t -> folders.value.firstOrNull { it.id == t }?.name } ?: "Library"

    fun moveFolder(id: Long, target: Long?) = moveItems(emptyList(), listOf(id), target)
    fun movePdfs(ids: List<Long>, target: Long?) = moveItems(ids, emptyList(), target)

    /** Moves PDFs and folders together. One Undo puts every item back where it came from. */
    fun moveItems(pdfIds: List<Long>, folderIds: List<Long>, target: Long?) {
        if (pdfIds.isEmpty() && folderIds.isEmpty()) return
        val beforeP = allPdfs.value.filter { it.id in pdfIds }.groupBy({ it.folderId }, { it.id })
        val beforeF = allFolders.value.filter { it.id in folderIds }.groupBy({ it.parentId }, { it.id })
        viewModelScope.launch {
            db.withTransaction {
                pdfIds.chunked(500).forEach { dao.movePdfs(it, target) }
                folderIds.chunked(500).forEach { dao.moveFolders(it, target) }
            }
        }
        if (beforeP.keys.all { it == target } && beforeF.keys.all { it == target }) return
        val n = pdfIds.size + folderIds.size
        val noun = if (folderIds.isEmpty()) "PDFs" else if (pdfIds.isEmpty()) "folders" else "items"
        val msg = (if (n == 1) "Moved" else "Moved $n $noun") + " to ${folderName(target)}"
        undo = UndoNote(msg) {
            viewModelScope.launch {
                db.withTransaction {
                    beforeP.forEach { (old, group) -> group.chunked(500).forEach { dao.movePdfs(it, old) } }
                    beforeF.forEach { (old, group) -> group.chunked(500).forEach { dao.moveFolders(it, old) } }
                }
            }
        }
    }

    // ---------- pin + manual order ----------
    /** Pinned PDFs sit above the rest of their folder. Newly pinned ones join the end of the pinned block. */
    fun setPdfPinned(ids: List<Long>, pin: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val all = allPdfs.value
            db.withTransaction {
                if (pin) {
                    all.filter { it.id in ids && !it.pinned }.groupBy { it.folderId }.forEach { (fid, list) ->
                        var next = (all.filter { it.folderId == fid && it.pinned }.maxOfOrNull { it.sortIndex } ?: 0) + 1
                        list.forEach { dao.setPdfPin(it.id, true, next++) }
                    }
                } else ids.forEach { dao.setPdfPin(it, false, 0) }
            }
        }
    }

    fun setFolderPinned(f: FolderEntity, pin: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            if (pin) {
                val next = (folders.value.filter { it.parentId == f.parentId && it.pinned }.maxOfOrNull { it.sortIndex } ?: 0) + 1
                dao.setFolderPin(f.id, true, next)
            } else dao.setFolderPin(f.id, false, 0)
        }
    }

    /** [ids] is one whole group (the pinned block or the unpinned block) in its new order. */
    fun reorderPdfs(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) { db.withTransaction { ids.forEachIndexed { i, id -> dao.setPdfOrder(id, i + 1) } } }
    }

    fun reorderFolders(ids: List<Long>) {
        viewModelScope.launch(Dispatchers.IO) { db.withTransaction { ids.forEachIndexed { i, id -> dao.setFolderOrder(id, i + 1) } } }
    }

    fun setFoldersFavorite(ids: List<Long>, fav: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch { ids.chunked(500).forEach { dao.setFolderFavorite(it, fav) } }
    }

    /** Pins or unpins several folders at once. Newly pinned ones join the end of their pinned block. */
    fun setFoldersPinned(ids: List<Long>, pin: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val all = folders.value
            db.withTransaction {
                if (pin) {
                    all.filter { it.id in ids && !it.pinned }.groupBy { it.parentId }.forEach { (pid, list) ->
                        var next = (all.filter { it.parentId == pid && it.pinned }.maxOfOrNull { it.sortIndex } ?: 0) + 1
                        list.forEach { dao.setFolderPin(it.id, true, next++) }
                    }
                } else ids.forEach { dao.setFolderPin(it, false, 0) }
            }
        }
    }

    /**
     * Removes PDFs and folders from Shelf without touching any file. A removed folder takes everything inside it along.
     * Undo, or Settings › Removed, brings them back exactly as they were.
     */
    fun removeItems(pdfIds: List<Long>, folderIds: List<Long>) {
        if (pdfIds.isEmpty() && folderIds.isEmpty()) return
        clearSel()
        val oneFolder = if (pdfIds.isEmpty() && folderIds.size == 1) allFolders.value.firstOrNull { it.id == folderIds[0] }?.name else null
        viewModelScope.launch {
            db.withTransaction {
                pdfIds.chunked(500).forEach { dao.setHidden(it, true) }
                folderIds.chunked(500).forEach { dao.setFoldersRemoved(it, true) }
            }
            val n = pdfIds.size + folderIds.size
            val noun = if (folderIds.isEmpty()) "PDFs" else if (pdfIds.isEmpty()) "folders" else "items"
            val msg = when {
                oneFolder != null -> "“$oneFolder” removed from Shelf"
                n == 1 -> "Removed from Shelf"
                else -> "$n $noun removed from Shelf"
            }
            undo = UndoNote(msg) {
                viewModelScope.launch {
                    db.withTransaction {
                        pdfIds.chunked(500).forEach { dao.setHidden(it, false) }
                    }
                    restoreFolders(folderIds, quiet = true)
                }
            }
        }
    }

    /**
     * Deletes removed folders for good, together with every subfolder. No file is touched: PDFs that were inside
     * stay removed from Shelf (they move to Settings › Removed PDFs, where they can be restored or deleted).
     */
    fun deleteFoldersForever(ids: List<Long>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val all = allFolders.value
            val gone = HashSet<Long>()
            ids.forEach { gone.addAll(descendants(it, all)) }
            val pdfIds = allPdfs.value.filter { p -> val f = p.folderId; f != null && f in gone }.map { it.id }
            db.withTransaction {
                pdfIds.chunked(500).forEach { dao.setHidden(it, true); dao.movePdfs(it, null) }
                gone.toList().chunked(500).forEach { dao.deleteFoldersByIds(it) }
            }
            toast(
                (if (ids.size == 1) "Folder deleted forever" else "${ids.size} folders deleted forever") +
                    (if (pdfIds.isNotEmpty()) ". Its PDFs are in Removed PDFs." else "")
            )
        }
    }

    /** Brings removed folders back. A folder whose parent is gone returns to the top level of the Library. */
    fun restoreFolders(ids: List<Long>, quiet: Boolean = false) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val all = allFolders.value
            val byId = all.associateBy { it.id }
            val gone = hiddenFolderIds(all.map { if (it.id in ids) it.copy(removed = false) else it })
            var toTop = 0
            db.withTransaction {
                ids.forEach { id ->
                    val parent = byId[id]?.parentId
                    if (parent != null && (byId[parent] == null || parent in gone)) { dao.moveFolder(id, null); toTop++ }
                }
                ids.chunked(500).forEach { dao.setFoldersRemoved(it, false) }
            }
            if (!quiet) {
                undo = null
                val name = if (ids.size == 1) byId[ids[0]]?.name else null
                toast(
                    (if (name != null) "Restored “$name”" else "Restored ${ids.size} folders") +
                        (if (toTop > 0) " to the Library" else "")
                )
            }
        }
    }

    fun deleteFolder(f: FolderEntity) {
        stack.remove(f.id)
        viewModelScope.launch {
            db.withTransaction {
                dao.reparentChildren(f.id, f.parentId)
                dao.reparentPdfs(f.id, f.parentId)
                dao.deleteFolder(f.id)
            }
        }
    }

    // ---------- pdfs ----------
    fun setFavorite(ids: List<Long>, fav: Boolean) = viewModelScope.launch { ids.chunked(500).forEach { dao.setFavorite(it, fav) } }
    /** Opens the right dialog (or acts straight away) for the ⋮ menu on a PDF row. */
    fun rowAction(p: PdfEntity, a: PdfMenuAction) {
        when (a) {
            PdfMenuAction.Pin -> setPdfPinned(listOf(p.id), !p.pinned)
            PdfMenuAction.Rename -> pdfDialog = PdfDlg.Rename(p)
            PdfMenuAction.Move -> pdfDialog = PdfDlg.Move(listOf(p.id))
            PdfMenuAction.Remove -> pdfDialog = PdfDlg.RemoveItems(listOf(p.id), emptyList())
            PdfMenuAction.Delete -> pdfDialog = PdfDlg.Delete(listOf(p.id), p.title())
        }
    }

    /** Renames the real file when Shelf has edit access; otherwise renames it inside Shelf only. */
    fun renamePdf(p: PdfEntity, newName: String) {
        val wanted = newName.trim()
        if (wanted.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val safe = wanted.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "document" }
            val fileName = if (safe.endsWith(".pdf", true)) safe else "$safe.pdf"
            var renamed: Uri? = null
            try { renamed = DocumentsContract.renameDocument(ctx.contentResolver, Uri.parse(p.uri), fileName) } catch (e: Exception) { }
            var ok = false
            if (renamed != null) {
                try {
                    dao.relink(p.id, renamed.toString(), fileName, p.size, p.modified)
                    dao.setLabel(p.id, null)
                    ok = true
                } catch (e: Exception) { }
            }
            if (ok) {
                withContext(Dispatchers.Main) { toast("Renamed") }
            } else {
                dao.setLabel(p.id, wanted)
                withContext(Dispatchers.Main) { toast("Renamed in Shelf. This file is read-only, so its name on your phone is unchanged.") }
            }
        }
    }

    /** Hides PDFs from Shelf without touching the files. Undo (or Settings › Removed PDFs) brings them back exactly as they were. */
    fun removeFromShelf(ids: List<Long>) {
        if (ids.isEmpty()) return
        selection.clear()
        viewModelScope.launch {
            ids.chunked(500).forEach { dao.setHidden(it, true) }
            val n = ids.size
            undo = UndoNote(if (n == 1) "Removed from Shelf" else "$n PDFs removed from Shelf") { restoreToShelf(ids) }
        }
    }

    fun restoreToShelf(ids: List<Long>) = viewModelScope.launch {
        ids.chunked(500).forEach { dao.setHidden(it, false) }
        undo = null
    }

    /** Permanently deletes the real files. Needs edit access to their folder. */
    fun deleteFromPhone(ids: List<Long>) {
        val targets = allPdfs.value.filter { it.id in ids }
        selection.clear()
        viewModelScope.launch(Dispatchers.IO) {
            val done = ArrayList<Long>()
            var failed = 0
            for (p in targets) {
                val gone = if (p.missing) true else try {
                    DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(p.uri))
                } catch (e: Exception) { false }
                if (gone) done.add(p.id) else failed++
            }
            done.chunked(400).forEach { dao.deleteByIds(it) }
            withContext(Dispatchers.Main) {
                when {
                    failed == 0 && done.size == 1 -> toast("Deleted from your phone")
                    failed == 0 -> toast("${done.size} PDFs deleted from your phone")
                    else -> toast("Couldn't delete $failed PDF" + (if (failed == 1) "" else "s") +
                        ". Open Settings and tap the folder to give Shelf edit access.")
                }
            }
        }
    }

    fun saveProgress(id: Long, page: Int, count: Int) = viewModelScope.launch { dao.progress(id, page, count) }
    fun toggleBookmark(pdfId: Long, page: Int, existing: BookmarkEntity?) = viewModelScope.launch {
        if (existing != null) dao.deleteBookmark(existing.id) else dao.addBookmark(BookmarkEntity(pdfId = pdfId, page = page))
    }
    fun deleteBookmark(id: Long) = viewModelScope.launch { dao.deleteBookmark(id) }

    // ---------- backup (organisation only, never the PDFs) ----------
    fun exportBackup(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val f = dao.foldersOnce(); val p = dao.pdfsOnce(); val b = dao.bookmarksOnce()
            val uriOf = p.associate { it.id to it.uri }
            val root = JSONObject().put("app", "shelf").put("version", 1)
            root.put("folders", JSONArray().also { a ->
                f.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: JSONObject.NULL).put("color", it.color).put("pinned", it.pinned).put("sortIndex", it.sortIndex).put("favorite", it.favorite).put("removed", it.removed).put("link", it.link ?: JSONObject.NULL)) }
            })
            root.put("pdfs", JSONArray().also { a ->
                p.filter { it.folderId != null || it.favorite || it.lastPage > 0 || it.label != null || it.hidden || it.pinned || it.sortIndex > 0 }.forEach {
                    a.put(JSONObject().put("uri", it.uri).put("name", it.name).put("size", it.size)
                        .put("folderId", it.folderId ?: JSONObject.NULL).put("favorite", it.favorite)
                        .put("lastPage", it.lastPage).put("pageCount", it.pageCount)
                        .put("label", it.label ?: JSONObject.NULL).put("hidden", it.hidden)
                        .put("pinned", it.pinned).put("sortIndex", it.sortIndex))
                }
            })
            root.put("bookmarks", JSONArray().also { a ->
                b.forEach { bm -> uriOf[bm.pdfId]?.let { u -> a.put(JSONObject().put("pdfUri", u).put("page", bm.page)) } }
            })
            ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(root.toString(1).toByteArray()) }
            withContext(Dispatchers.Main) { toast("Backup saved") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { toast("Backup failed") }
        }
    }

    fun importBackup(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val root = JSONObject(text)
            val fa = root.getJSONArray("folders"); val pa = root.getJSONArray("pdfs"); val ba = root.getJSONArray("bookmarks")
            val current = dao.pdfsOnce()
            val byUri = current.associateBy { it.uri }
            val byKey = current.groupBy { "${it.name}|${it.size}" }
            var matched = 0
            db.withTransaction {
                dao.resetPdfFolders(); dao.clearBookmarks(); dao.clearFolders()
                for (i in 0 until fa.length()) {
                    val o = fa.getJSONObject(i)
                    dao.insertFolder(FolderEntity(
                        id = o.getLong("id"), name = o.getString("name"),
                        parentId = if (o.isNull("parentId")) null else o.getLong("parentId"), color = o.optInt("color"),
                        pinned = o.optBoolean("pinned", false), sortIndex = o.optInt("sortIndex", 0),
                        favorite = o.optBoolean("favorite", false), removed = o.optBoolean("removed", false),
                        link = if (o.isNull("link")) null else o.optString("link", "").ifEmpty { null }
                    ))
                }
                val idByUri = HashMap<String, Long>()
                for (i in 0 until pa.length()) {
                    val o = pa.getJSONObject(i)
                    val u = o.getString("uri")
                    val m = byUri[u] ?: byKey["${o.getString("name")}|${o.getLong("size")}"]?.firstOrNull() ?: continue
                    matched++
                    idByUri[u] = m.id
                    dao.restorePdf(
                        m.id, if (o.isNull("folderId")) null else o.getLong("folderId"), o.getBoolean("favorite"), o.getInt("lastPage"), o.getInt("pageCount"),
                        if (o.isNull("label")) null else o.getString("label"), o.optBoolean("hidden", false),
                        o.optBoolean("pinned", false), o.optInt("sortIndex", 0)
                    )
                }
                for (i in 0 until ba.length()) {
                    val o = ba.getJSONObject(i)
                    idByUri[o.getString("pdfUri")]?.let { dao.addBookmark(BookmarkEntity(pdfId = it, page = o.getInt("page"))) }
                }
            }
            withContext(Dispatchers.Main) { stack.clear(); clearSel(); toast("Restored $matched of ${pa.length()} PDFs") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { toast("That file isn't a valid Shelf backup") }
        }
    }
}
