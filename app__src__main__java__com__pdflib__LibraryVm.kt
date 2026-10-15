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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

/** A short "done · Undo" message shown above the bottom bar. */
class UndoNote(val message: String, val action: () -> Unit)

class LibraryVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val db = AppDb.get(ctx)
    private val dao = db.dao()
    private val prefs = ctx.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    var loaded by mutableStateOf(false)
    val folders = dao.folders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val allPdfs = dao.pdfs().onEach { loaded = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** What the app shows: everything except PDFs the user removed from Shelf. */
    val pdfs = allPdfs.map { l -> l.filter { !it.hidden } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hidden = allPdfs.map { l -> l.filter { it.hidden } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val bookmarks = dao.bookmarks().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // navigation + UI state
    var tab by mutableIntStateOf(0)
    var readerId by mutableStateOf<Long?>(null)
    var searching by mutableStateOf(false)
    val stack = mutableStateListOf<Long>()
    val selection = mutableStateListOf<Long>()
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

    init {
        refreshSources()
        rescan()
    }

    private fun toast(m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()

    // ---------- navigation ----------
    fun enter(id: Long) { selection.clear(); stack.add(id) }
    fun up() { selection.clear(); if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }
    fun toBreadcrumb(index: Int) { selection.clear(); while (stack.size > index + 1) stack.removeAt(stack.lastIndex) }
    fun goToFolder(id: Long?) {
        selection.clear(); stack.clear()
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
            var base = 0
            for (root in roots) {
                try {
                    val found = Scanner.scan(ctx, root) { scanFound = base + it }
                    base += found.size
                    val prefix = root.toString() + "/document/"
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
                            fresh.add(PdfEntity(uri = f.uri, name = f.name, size = f.size, modified = f.modified))
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
    fun moveFolder(id: Long, target: Long?) = viewModelScope.launch { dao.moveFolder(id, target) }
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
    fun movePdfs(ids: List<Long>, target: Long?) = viewModelScope.launch { ids.chunked(500).forEach { dao.movePdfs(it, target) } }
    fun setFavorite(ids: List<Long>, fav: Boolean) = viewModelScope.launch { ids.chunked(500).forEach { dao.setFavorite(it, fav) } }
    /** Opens the right dialog (or acts straight away) for the ⋮ menu on a PDF row. */
    fun rowAction(p: PdfEntity, a: PdfMenuAction) {
        when (a) {
            PdfMenuAction.Pin -> setPdfPinned(listOf(p.id), !p.pinned)
            PdfMenuAction.Rename -> pdfDialog = PdfDlg.Rename(p)
            PdfMenuAction.Move -> pdfDialog = PdfDlg.Move(listOf(p.id))
            PdfMenuAction.Remove -> removeFromShelf(listOf(p.id))
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
                f.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: JSONObject.NULL).put("color", it.color).put("pinned", it.pinned).put("sortIndex", it.sortIndex)) }
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
                        pinned = o.optBoolean("pinned", false), sortIndex = o.optInt("sortIndex", 0)
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
            withContext(Dispatchers.Main) { stack.clear(); selection.clear(); toast("Restored $matched of ${pa.length()} PDFs") }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { toast("That file isn't a valid Shelf backup") }
        }
    }
}
