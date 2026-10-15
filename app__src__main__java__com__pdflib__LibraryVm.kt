package com.pdflib

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
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
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import org.json.JSONArray
import org.json.JSONObject

class LibraryVm(app: Application) : AndroidViewModel(app) {
    private val ctx: Context = app.applicationContext
    private val db = AppDb.get(ctx)
    private val dao = db.dao()
    private val prefs = ctx.getSharedPreferences("shelf", Context.MODE_PRIVATE)

    var loaded by mutableStateOf(false)
    val folders = dao.folders().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pdfs = dao.pdfs().onEach { loaded = true }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
        sources = ctx.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocumentsContract.isTreeUri(it.uri) }
            .map { it.uri }
    }

    fun addSource(uri: Uri) {
        try { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: SecurityException) { }
        refreshSources(); rescan()
    }

    fun removeSource(uri: Uri) {
        try { ctx.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        refreshSources()
    }

    fun rescan() {
        if (scanning) return
        refreshSources()
        scanning = true; scanFound = 0
        val roots = sources
        viewModelScope.launch(Dispatchers.IO) {
            var base = 0
            for (root in roots) {
                try {
                    val found = Scanner.scan(ctx, root) { scanFound = base + it }
                    base += found.size
                    val rows = dao.pdfsOnce()
                    val known = rows.mapTo(HashSet()) { it.uri }
                    val seen = found.mapTo(HashSet()) { it.uri }
                    val prefix = root.toString() + "/document/"
                    // A file that was renamed/moved inside this source keeps its folder, stars and progress.
                    val gone = rows.filter { it.uri.startsWith(prefix) && it.uri !in seen }
                    val goneByKey = gone.groupBy { "${it.name}|${it.size}" }
                    val fresh = found.filter { it.uri !in known }.map { f ->
                        val old = goneByKey["${f.name}|${f.size}"]?.firstOrNull()
                        PdfEntity(
                            uri = f.uri, name = f.name, size = f.size, modified = f.modified,
                            folderId = old?.folderId, favorite = old?.favorite ?: false,
                            lastPage = old?.lastPage ?: 0, pageCount = old?.pageCount ?: 0, lastOpened = old?.lastOpened ?: 0
                        )
                    }
                    fresh.chunked(200).forEach { dao.insertPdfs(it) }
                    gone.map { it.uri }.chunked(400).forEach { dao.deleteByUris(it) }
                } catch (e: Exception) { }
            }
            withContext(Dispatchers.Main) { scanning = false }
        }
    }

    // ---------- folders ----------
    fun createFolder(name: String, color: Int, parent: Long?) = viewModelScope.launch {
        dao.insertFolder(FolderEntity(name = name, parentId = parent, color = color))
    }
    fun updateFolder(id: Long, name: String, color: Int) = viewModelScope.launch { dao.updateFolder(id, name, color) }
    fun moveFolder(id: Long, target: Long?) = viewModelScope.launch { dao.moveFolder(id, target) }
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
                f.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: JSONObject.NULL).put("color", it.color)) }
            })
            root.put("pdfs", JSONArray().also { a ->
                p.filter { it.folderId != null || it.favorite || it.lastPage > 0 }.forEach {
                    a.put(JSONObject().put("uri", it.uri).put("name", it.name).put("size", it.size)
                        .put("folderId", it.folderId ?: JSONObject.NULL).put("favorite", it.favorite)
                        .put("lastPage", it.lastPage).put("pageCount", it.pageCount))
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
                        parentId = if (o.isNull("parentId")) null else o.getLong("parentId"), color = o.optInt("color")
                    ))
                }
                val idByUri = HashMap<String, Long>()
                for (i in 0 until pa.length()) {
                    val o = pa.getJSONObject(i)
                    val u = o.getString("uri")
                    val m = byUri[u] ?: byKey["${o.getString("name")}|${o.getLong("size")}"]?.firstOrNull() ?: continue
                    matched++
                    idByUri[u] = m.id
                    dao.restorePdf(m.id, if (o.isNull("folderId")) null else o.getLong("folderId"), o.getBoolean("favorite"), o.getInt("lastPage"), o.getInt("pageCount"))
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
