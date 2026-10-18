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
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
        p.filter { pdf -> val fid = pdf.folderId; !pdf.hidden && pdf.trashed == 0L && (fid == null || fid !in gone) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Removed folders waiting in Settings, with how many PDFs come back with each. */
    val removedFolders = combine(allFolders, allPdfs) { f, p ->
        val stats = FolderStats(p.filter { !it.hidden && it.trashed == 0L }, f)
        removedRoots(f).map { RemovedFolder(it, stats.pdfCount(it.id), stats.subCount(it.id)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val hidden = allPdfs.map { l -> l.filter { it.hidden && it.trashed == 0L } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** PDFs deleted from the phone but still recoverable: newest first. */
    val trash = allPdfs.map { l -> l.filter { it.trashed > 0L }.sortedByDescending { it.trashed } }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
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
    /** All files access is on: Shelf reads the phone's storage directly. */
    var allFiles by mutableStateOf(Storage.granted())
    private var again = false
    private var lastScanAt = 0L

    // dialogs + undo
    var pdfDialog by mutableStateOf<PdfDlg?>(null)
    var undo by mutableStateOf<UndoNote?>(null)

    var invert by mutableStateOf(prefs.getBoolean("invert", false))
    fun updateInvert(v: Boolean) { invert = v; prefs.edit().putBoolean("invert", v).apply() }

    /** Library "Sort by" (see SortLabels). Remembered between launches. */
    var sortMode by mutableIntStateOf(prefs.getInt("sort", 0))
    fun updateSort(m: Int) { sortMode = m; prefs.edit().putInt("sort", m).apply() }

    /** Library as a list (default) or as a grid of page previews. */
    var gridView by mutableStateOf(prefs.getBoolean("grid", false))
    fun updateGrid(v: Boolean) { gridView = v; prefs.edit().putBoolean("grid", v).apply() }

    /** Folders as a list (default) or as a grid of folder cards. */
    var foldersGrid by mutableStateOf(prefs.getBoolean("foldersGrid", false))
    fun updateFoldersGrid(v: Boolean) { foldersGrid = v; prefs.edit().putBoolean("foldersGrid", v).apply() }

    /** Library status filter: 0 All, 1 Unread, 2 In progress, 3 Finished. */
    var statusFilter by mutableIntStateOf(0)

    // Reading comfort (applies to every PDF; the zoom is remembered per PDF).
    var keepOn by mutableStateOf(prefs.getBoolean("keepOn", true))
    fun updateKeepOn(v: Boolean) { keepOn = v; prefs.edit().putBoolean("keepOn", v).apply() }
    /** Reader brightness 0.05..1, or -1 to follow the phone. */
    var brightness by mutableFloatStateOf(prefs.getFloat("brightness", -1f))
    fun updateBrightness(v: Float) { brightness = v; prefs.edit().putFloat("brightness", v).apply() }
    /** 0 follow the phone, 1 portrait, 2 landscape. */
    var orient by mutableIntStateOf(prefs.getInt("orient", 0))
    fun updateOrient(v: Int) { orient = v; prefs.edit().putInt("orient", v).apply() }
    /** false = continuous scroll, true = one page at a time (swipe sideways). */
    var pageMode by mutableStateOf(prefs.getBoolean("pageMode", false))
    fun updatePageMode(v: Boolean) { pageMode = v; prefs.edit().putBoolean("pageMode", v).apply() }

    /** Seconds you usually spend on a page, learned from your own reading (0 = not known yet). Feeds "time left". */
    var paceSec by mutableFloatStateOf(prefs.getFloat("paceSec", 0f))
    fun updatePace(v: Float) { paceSec = v; prefs.edit().putFloat("paceSec", v).apply() }

    /** Asked for from a menu or the reader; the screen (which owns an Activity) carries it out. */
    var shareReq by mutableStateOf<ShareReq?>(null)
    /** True while a PDF from another app is being copied in. */
    var importing by mutableStateOf(false)

    var themeMode by mutableStateOf(ThemeMode.fromKey(prefs.getString("theme", null)))
    fun updateTheme(m: ThemeMode) { themeMode = m; prefs.edit().putString("theme", m.key).apply() }

    init {
        refreshSources()
        rescan()
        viewModelScope.launch(Dispatchers.IO) { ShareTools.cleanup(ctx) }
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
        allFiles = Storage.granted()
        val direct = if (allFiles) Storage.roots(ctx) else emptyList()
        PhoneOps.fileRoots = direct.map { it.path }
        if (allFiles) writable = writable + direct.map { Uri.fromFile(it).toString() }
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
        for (r in roots) if (r.toString() !in have) dao.insertLink(LinkEntity(uri = r.toString(), target = null, mode = if (r.scheme == "file") LINK_MIRROR else LINK_FLAT, name = treeFolderName(r)))
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
        lastScanAt = System.currentTimeMillis()
        scanning = true; scanFound = 0
        val roots: List<Uri> = if (allFiles) PhoneOps.fileRoots.map { Uri.fromFile(java.io.File(it)) } else sources
        val prefixes = roots.map { PhoneOps.prefixOf(it) }
        viewModelScope.launch(Dispatchers.IO) {
            val linkList = ensureLinks(roots)
            val folderRows = dao.foldersOnce()
            val folderIds = folderRows.mapTo(HashSet<Long>()) { it.id }
            val keyToId = HashMap<String, Long>()
            val idToKey = HashMap<Long, String>()
            folderRows.forEach { f -> val k = f.link; if (k != null) { keyToId[k] = f.id; idToKey[f.id] = k } }
            suspend fun ensureFolder(key: String, name: String, parent: Long?): Long {
                keyToId[key]?.let { return it }
                val id = dao.insertFolder(FolderEntity(name = name, parentId = parent, color = keyToId.size % FolderTones.size, link = key))
                keyToId[key] = id
                idToKey[id] = key
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
                    val prefix = PhoneOps.prefixOf(root)
                    val link = linkList.firstOrNull { it.uri == root.toString() }
                    val rows = dao.pdfsOnce().filter { it.trashed == 0L }
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
                    // 1) same content fingerprint (survives any rename), 2) same name and size, 3) same size and date.
                    val candFp = candidates.filter { val x = it.fp; x != null && x != "-" }
                    var fpBudget = 200
                    // PDFs that changed folders on the phone: (pdf id, Shelf folder it was in, its folder on the phone now)
                    val moves = ArrayList<Triple<Long, Long, String>>()
                    val lk: String? = if (link != null && link.mode == LINK_MIRROR) linkKey(link.uri) else null
                    for (f in found) {
                        if (f.uri in known) continue
                        val newFp: String? = if (candFp.isNotEmpty() && fpBudget > 0) { fpBudget--; Fingerprint.of(ctx, Uri.parse(f.uri)) } else null
                        val old: PdfEntity? =
                            (if (newFp != null) candFp.firstOrNull { it.id !in used && it.fp == newFp && it.size == f.size } else null)
                                ?: candidates.firstOrNull { it.id !in used && it.name == f.name && it.size == f.size }
                                ?: candidates.firstOrNull { it.id !in used && f.size > 0 && it.size == f.size && it.modified == f.modified }
                        if (old != null) {
                            used.add(old.id)
                            dao.relink(old.id, f.uri, f.name, f.size, f.modified)
                            if (old.fp != newFp) dao.setFp(old.id, newFp)
                            val oldFolder = old.folderId
                            val oldKey = if (oldFolder != null) idToKey[oldFolder] else null
                            if (lk != null && oldFolder != null && oldKey != null && oldKey.startsWith("$lk|")) {
                                val newRel = f.rel.joinToString("/")
                                if (oldKey.substringAfter('|', "") != newRel) moves.add(Triple(old.id, oldFolder, newRel))
                            }
                        } else {
                            fresh.add(PdfEntity(uri = f.uri, name = f.name, size = f.size, modified = f.modified, folderId = placeFor(link, f.rel)))
                        }
                    }
                    fresh.chunked(200).forEach { dao.insertPdfs(it) }

                    // Follow the phone. A folder renamed or moved outside Shelf is renamed or moved in Shelf too,
                    // and a PDF that changed folders on the phone changes folders here, instead of leaving stale copies behind.
                    if (link != null && lk != null && moves.isNotEmpty()) {
                        val dirsNow = HashSet<String>()
                        for (f in found) {
                            var path = ""
                            for (seg in f.rel) { path = if (path.isEmpty()) seg else "$path/$seg"; dirsNow.add(path) }
                        }
                        val byOld = moves.groupBy { it.second }
                        val order = byOld.entries.sortedBy { e -> e.value.first().third.count { c -> c == '/' } }
                        for (entry in order) {
                            val oldFolder = entry.key
                            val oldKey = idToKey[oldFolder] ?: continue
                            if (oldKey.substringAfter('|', "") in dirsNow) continue
                            val newRel = entry.value.groupBy { it.third }.maxByOrNull { it.value.size }?.key ?: continue
                            if (newRel.isEmpty() || keyToId["$lk|$newRel"] != null) continue
                            val parentRel = newRel.substringBeforeLast('/', "")
                            val parentId = placeFor(link, if (parentRel.isEmpty()) emptyList() else parentRel.split('/'))
                            val newKey = "$lk|$newRel"
                            dao.renameMirror(oldFolder, newRel.substringAfterLast('/'), newKey, parentId)
                            keyToId.remove(oldKey); keyToId[newKey] = oldFolder; idToKey[oldFolder] = newKey
                        }
                        for (m in moves) {
                            val target = placeFor(link, if (m.third.isEmpty()) emptyList() else m.third.split('/'))
                            if (target != null && target != m.second) dao.movePdfs(listOf(m.first), target)
                        }
                    }

                    // Gone from the phone: keep the row, flag it.
                    inRoot.filter { it.uri !in seen && it.id !in used && !it.missing }.map { it.id }
                        .chunked(400).forEach { dao.setMissing(it, true) }
                } catch (e: Exception) { }
            }
            withContext(Dispatchers.Main) {
                scanning = false
                if (again) { again = false; rescan() }
            }
            fillFingerprints()
        }
    }

    /** Quietly fingerprints PDFs that don't have one yet (a batch per scan), so a later rename can always be traced. */
    private suspend fun fillFingerprints() {
        val todo = dao.pdfsOnce().filter { it.fp == null && !it.missing }.take(60)
        for (p in todo) dao.setFp(p.id, Fingerprint.of(ctx, Uri.parse(p.uri)) ?: "-")
    }

    /** Called whenever Shelf comes back to the screen: files renamed or added meanwhile are picked up. */
    fun onForeground() {
        val was = allFiles
        refreshSources()
        if (allFiles != was) { rescan(); return }
        if (System.currentTimeMillis() - lastScanAt > 30_000) rescan()
    }

    /** Used when a PDF can't be opened: rescan so a renamed file is found, then return its new address (null = still not found). */
    suspend fun repairPdf(id: Long): String? {
        val before = dao.pdfsOnce().firstOrNull { it.id == id }?.uri ?: return null
        withContext(Dispatchers.Main) { rescan() }
        withTimeoutOrNull(25_000) { while (scanning || again) delay(100) }
        val after = dao.pdfsOnce().firstOrNull { it.id == id } ?: return null
        return if (after.uri != before && !after.missing) after.uri else null
    }

    /** "Find this file": point a lost PDF at the file you pick. Folder, stars, bookmarks and progress stay. */
    fun relinkManually(id: Long, uri: Uri, onDone: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            grant(uri)
            val taken = dao.pdfsOnce().any { it.uri == uri.toString() && it.id != id }
            var name = "document.pdf"; var size = 0L
            try {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) { c.getString(0)?.let { name = it }; size = c.getLong(1) }
                }
            } catch (e: Exception) { }
            if (!name.endsWith(".pdf", true)) name += ".pdf"
            var ok = false
            if (!taken) {
                try {
                    dao.relink(id, uri.toString(), name, size, System.currentTimeMillis())
                    dao.setFp(id, Fingerprint.of(ctx, uri) ?: "-")
                    ok = true
                } catch (e: Exception) { }
            }
            withContext(Dispatchers.Main) {
                if (taken) toast("That file is already in Shelf under another entry.")
                onDone(ok)
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
    /** A new folder. Inside a phone folder it is created on the phone too; anywhere else it only exists in Shelf. */
    fun createFolder(name: String, color: Int, parent: Long?) = viewModelScope.launch(Dispatchers.IO) {
        val pd = phoneDirOf(parent)
        if (pd == null) {
            dao.insertFolder(FolderEntity(name = name, parentId = parent, color = color))
            return@launch
        }
        if (pd.link.uri !in writable) { say(READ_ONLY); return@launch }
        val clean = PhoneOps.cleanName(name)
        if (clean.isEmpty()) return@launch
        val dirId = PhoneOps.resolveDir(ctx, pd.tree, pd.rel)
        if (dirId == null) { say("Couldn't find this folder on your phone."); return@launch }
        if (PhoneOps.hasName(ctx, pd.tree, dirId, clean)) { say("A folder named “$clean” already exists on your phone here."); return@launch }
        if (PhoneOps.createDir(ctx, pd.tree, dirId, clean) == null) { say("Couldn't create the folder on your phone."); return@launch }
        val rel = if (pd.rel.isEmpty()) clean else pd.rel + "/" + clean
        dao.insertFolder(FolderEntity(name = clean, parentId = parent, color = color, link = linkKey(pd.link.uri) + "|" + rel))
    }

    /** Renames a folder. A phone folder is renamed on the phone too, and its PDFs keep every star, bookmark and page. */
    fun updateFolder(id: Long, name: String, color: Int) = viewModelScope.launch(Dispatchers.IO) {
        val f = allFolders.value.firstOrNull { it.id == id } ?: return@launch
        val clean = PhoneOps.cleanName(name)
        val pd = phoneDirOf(id)
        if (pd == null || pd.rel.isEmpty() || clean.isEmpty() || clean == f.name) {
            dao.updateFolder(id, name, color)
            return@launch
        }
        if (pd.link.uri !in writable) { say(READ_ONLY); return@launch }
        val oldId = PhoneOps.resolveDir(ctx, pd.tree, pd.rel)
        if (oldId == null) { say("Couldn't find this folder on your phone."); return@launch }
        val inDir = PhoneOps.parentId(oldId, PhoneOps.rootId(pd.tree))
        if (PhoneOps.hasName(ctx, pd.tree, inDir, clean, oldId)) { say("A folder named “$clean” already exists on your phone here."); return@launch }
        val newId = PhoneOps.rename(ctx, pd.tree, oldId, clean)
        if (newId == null) { say("Couldn't rename the folder on your phone."); return@launch }
        val parentRel = pd.rel.substringBeforeLast('/', "")
        rebase(pd, pd.rel, if (parentRel.isEmpty()) clean else "$parentRel/$clean", oldId, newId)
        dao.updateFolder(id, clean, color)
        withContext(Dispatchers.Main) { rescan() }
    }

    private fun folderName(id: Long?): String = id?.let { t -> folders.value.firstOrNull { it.id == t }?.name } ?: "Library"

    fun moveFolder(id: Long, target: Long?) = moveItems(emptyList(), listOf(id), target)
    fun movePdfs(ids: List<Long>, target: Long?) = moveItems(ids, emptyList(), target)

    /** Moves PDFs and folders together. One Undo puts every item back where it came from. */
    fun moveItems(pdfIds: List<Long>, folderIds: List<Long>, target: Long?) {
        if (pdfIds.isEmpty() && folderIds.isEmpty()) return
        if (target != null && allFolders.value.firstOrNull { it.id == target }?.link != null) {
            moveOnPhone(pdfIds, folderIds, target)
            return
        }
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

    // ---------- real changes on the phone ----------
    private class PhoneDir(val link: LinkEntity, val tree: Uri, val rel: String)

    private val READ_ONLY = "This phone folder is read-only. In Settings, tap the folder and choose it again to allow changes."

    private suspend fun say(m: String) = withContext(Dispatchers.Main) { toast(m) }

    /** The phone folder behind a Shelf folder, or null when it is a Shelf-only folder. */
    private suspend fun phoneDirOf(folderId: Long?): PhoneDir? {
        val f = folderId?.let { id -> allFolders.value.firstOrNull { it.id == id } } ?: return null
        val key = f.link ?: return null
        val k = key.substringBefore('|')
        val rel = key.substringAfter('|', "")
        val link = dao.linksOnce().firstOrNull { linkKey(it.uri) == k } ?: return null
        return PhoneDir(link, Uri.parse(link.uri), rel)
    }

    /** Live check for the folder name box: a message when the phone already has a folder with that name. */
    suspend fun folderNameProblem(parent: Long?, typed: String, selfId: Long?): String? = withContext(Dispatchers.IO) {
        val clean = PhoneOps.cleanName(typed)
        if (clean.isEmpty()) return@withContext null
        val pd = phoneDirOf(parent) ?: return@withContext null
        if (pd.link.uri !in writable) return@withContext null
        val dirId = PhoneOps.resolveDir(ctx, pd.tree, pd.rel) ?: return@withContext null
        var selfDir: String? = null
        if (selfId != null) {
            val sp = phoneDirOf(selfId)
            if (sp != null && sp.rel.isNotEmpty()) selfDir = PhoneOps.resolveDir(ctx, sp.tree, sp.rel)
        }
        if (PhoneOps.hasName(ctx, pd.tree, dirId, clean, selfDir)) "A folder named “$clean” already exists on your phone here. Choose another name." else null
    }

    /** After a phone folder was renamed or moved: update its Shelf folders' link keys and its PDFs' addresses. */
    private suspend fun rebase(pd: PhoneDir, oldRel: String, newRel: String, oldDirId: String, newDirId: String) {
        val k = linkKey(pd.link.uri)
        val oldKey = "$k|$oldRel"
        for (f in dao.foldersOnce()) {
            val key = f.link ?: continue
            if (key == oldKey || key.startsWith("$oldKey/")) dao.setFolderLink(f.id, "$k|$newRel" + key.removePrefix(oldKey))
        }
        val treePrefix = PhoneOps.prefixOf(pd.tree)
        for (p in dao.pdfsOnce()) {
            if (!p.uri.startsWith(treePrefix)) continue
            val id = PhoneOps.idOf(p.uri)
            if (id.startsWith("$oldDirId/")) {
                dao.relink(p.id, PhoneOps.docUri(pd.tree, newDirId + id.removePrefix(oldDirId)).toString(), p.name, p.size, p.modified)
            }
        }
    }

    /** Moves PDFs and folders into a phone folder: the real files and folders move on the phone too. */
    private fun moveOnPhone(pdfIds: List<Long>, folderIds: List<Long>, target: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val pd = phoneDirOf(target) ?: return@launch
            if (pd.link.uri !in writable) { say(READ_ONLY); return@launch }
            val destId = PhoneOps.resolveDir(ctx, pd.tree, pd.rel)
            if (destId == null) { say("Couldn't find “${folderName(target)}” on your phone."); return@launch }
            val taken = PhoneOps.kids(ctx, pd.tree, destId).mapTo(HashSet<String>()) { it.name.lowercase() }
            var moved = 0; var clash = 0; var failed = 0
            for (p in allPdfs.value.filter { it.id in pdfIds }) {
                if (p.missing || !PhoneOps.isTreeDoc(p.uri)) { failed++; continue }
                val srcTree = PhoneOps.treeOf(p.uri)
                val from = PhoneOps.parentId(PhoneOps.idOf(p.uri), PhoneOps.rootId(srcTree))
                if (srcTree == pd.tree && from == destId) { dao.movePdfs(listOf(p.id), target); moved++; continue }
                if (p.name.lowercase() in taken) { clash++; continue }
                val newUri = PhoneOps.transfer(ctx, p.uri, pd.tree, destId, p.name, move = true)
                if (newUri == null) { failed++; continue }
                dao.relink(p.id, newUri, p.name, p.size, p.modified)
                dao.movePdfs(listOf(p.id), target)
                taken.add(p.name.lowercase()); moved++
            }
            for (f in allFolders.value.filter { it.id in folderIds }) {
                val fpd = phoneDirOf(f.id)
                if (fpd == null) { dao.moveFolders(listOf(f.id), target); moved++; continue }
                if (fpd.rel.isEmpty() || fpd.tree != pd.tree || pd.rel == fpd.rel || pd.rel.startsWith(fpd.rel + "/")) { failed++; continue }
                val oldId = PhoneOps.resolveDir(ctx, fpd.tree, fpd.rel)
                if (oldId == null) { failed++; continue }
                val fromParent = PhoneOps.parentId(oldId, PhoneOps.rootId(fpd.tree))
                if (fromParent == destId) { dao.moveFolders(listOf(f.id), target); moved++; continue }
                val dirName = fpd.rel.substringAfterLast('/')
                if (dirName.lowercase() in taken) { clash++; continue }
                val newId = PhoneOps.move(ctx, fpd.tree, oldId, fromParent, destId)
                if (newId == null) { failed++; continue }
                rebase(fpd, fpd.rel, if (pd.rel.isEmpty()) dirName else pd.rel + "/" + dirName, oldId, newId)
                dao.moveFolders(listOf(f.id), target)
                taken.add(dirName.lowercase()); moved++
            }
            val parts = ArrayList<String>()
            if (moved > 0) parts.add("Moved $moved to “${folderName(target)}” on your phone")
            if (clash > 0) parts.add("$clash skipped because the same name already exists there")
            if (failed > 0) parts.add("$failed couldn't be moved")
            withContext(Dispatchers.Main) {
                clearSel()
                if (parts.isNotEmpty()) toast(parts.joinToString(". "))
                rescan()
            }
        }
    }

    /** Copies PDFs into a phone folder (real copies on the phone). Files whose name already exists there are skipped. */
    fun copyPdfs(ids: List<Long>, target: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val pd = phoneDirOf(target)
            if (pd == null) { say("Pick a phone folder to copy into."); return@launch }
            if (pd.link.uri !in writable) { say(READ_ONLY); return@launch }
            val destId = PhoneOps.resolveDir(ctx, pd.tree, pd.rel)
            if (destId == null) { say("Couldn't find “${folderName(target)}” on your phone."); return@launch }
            val taken = PhoneOps.kids(ctx, pd.tree, destId).mapTo(HashSet<String>()) { it.name.lowercase() }
            var copied = 0; var clash = 0; var failed = 0
            for (p in allPdfs.value.filter { it.id in ids }) {
                if (p.missing || !PhoneOps.isTreeDoc(p.uri)) { failed++; continue }
                if (p.name.lowercase() in taken) { clash++; continue }
                val newUri = PhoneOps.transfer(ctx, p.uri, pd.tree, destId, p.name, move = false)
                if (newUri == null) { failed++; continue }
                dao.insertPdfs(listOf(PdfEntity(uri = newUri, name = p.name, size = p.size, modified = System.currentTimeMillis(), folderId = target)))
                taken.add(p.name.lowercase()); copied++
            }
            val parts = ArrayList<String>()
            if (copied > 0) parts.add("Copied $copied to “${folderName(target)}” on your phone")
            if (clash > 0) parts.add("$clash skipped because the same name already exists there")
            if (failed > 0) parts.add("$failed couldn't be copied")
            withContext(Dispatchers.Main) {
                clearSel()
                if (parts.isNotEmpty()) toast(parts.joinToString(". "))
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
            PdfMenuAction.Copy -> pdfDialog = PdfDlg.Copy(listOf(p.id))
            PdfMenuAction.Remove -> pdfDialog = PdfDlg.RemoveItems(listOf(p.id), emptyList())
            PdfMenuAction.Delete -> pdfDialog = PdfDlg.Delete(listOf(p.id), p.title())
            PdfMenuAction.Share -> shareReq = ShareReq(p, ShareKind.Share)
            PdfMenuAction.OpenIn -> shareReq = ShareReq(p, ShareKind.OpenIn)
            PdfMenuAction.Print -> shareReq = ShareReq(p, ShareKind.Print)
            PdfMenuAction.ToggleRead -> markRead(listOf(p.id), p.status() != ReadStatus.Finished)
        }
    }

    /** "Mark as read" / "Mark as unread" (unread also resets the reading position). */
    fun markRead(ids: List<Long>, read: Boolean) {
        viewModelScope.launch {
            ids.chunked(500).forEach { if (read) dao.markRead(it) else dao.markUnread(it) }
            undo = UndoNote(if (read) "Marked as read" else "Marked as unread") { markRead(ids, !read) }
        }
    }

    // ---------- PDFs that arrive from other apps ----------
    /** "Open with" (one PDF: it opens) and "Share to Shelf" (any number: they are added to the Library). */
    fun handleIntent(intent: Intent?) {
        val uris = Imports.urisOf(intent)
        if (uris.isEmpty()) return
        intent?.action = null; intent?.data = null   // handled: a recreated activity must not import it again
        viewModelScope.launch {
            importing = true
            val results = withContext(Dispatchers.IO) { uris.mapNotNull { importOne(it) } }
            importing = false
            val ids = results.map { it.first }
            val fresh = results.filter { it.second }.map { it.first }
            val failed = uris.size - results.size
            if (failed > 0) toast(if (uris.size == 1) "That file couldn't be opened. Is it a PDF?" else "$failed file" + (if (failed == 1) "" else "s") + " couldn't be added.")
            if (ids.isEmpty()) return@launch
            searching = false; pdfDialog = null
            if (ids.size == 1) {
                if (fresh.isNotEmpty()) toast("Added to your Library")
                openReader(ids[0])
            } else {
                stack.clear(); clearSel(); tab = 0; closeReader()
                undo = UndoNote("${fresh.size} PDF" + (if (fresh.size == 1) "" else "s") + " added to Library") { discardImports(fresh) }
            }
        }
    }

    /** Returns (pdf id, true when it is new to the Library). A file already in Shelf is reused. */
    private suspend fun importOne(u: Uri): Pair<Long, Boolean>? {
        val known = dao.pdfsOnce()
        known.firstOrNull { it.uri == u.toString() && it.trashed == 0L }?.let {
            if (it.hidden) dao.setHidden(listOf(it.id), false)
            return it.id to false
        }
        val file = Imports.copyIn(ctx, u) ?: return null
        val fp = Fingerprint.of(ctx, Uri.fromFile(file)) ?: "-"
        val same = if (fp != "-") known.firstOrNull { it.fp == fp && it.size == file.length() && !it.missing && it.trashed == 0L } else null
        if (same != null) {
            file.delete()
            if (same.hidden) dao.setHidden(listOf(same.id), false)
            return same.id to false
        }
        val entity = PdfEntity(uri = Uri.fromFile(file).toString(), name = file.name, size = file.length(), modified = System.currentTimeMillis(), folderId = null, fp = fp)
        dao.insertPdfs(listOf(entity))
        val id = dao.pdfsOnce().firstOrNull { it.uri == entity.uri }?.id ?: return null
        return id to true
    }

    private fun discardImports(ids: List<Long>) {
        undo = null
        viewModelScope.launch(Dispatchers.IO) {
            dao.pdfsOnce().filter { it.id in ids }.forEach { Imports.delete(ctx, it.uri) }
            ids.chunked(400).forEach { dao.deleteByIds(it) }
        }
    }

    /** Renames the real file when Shelf has edit access; otherwise renames it inside Shelf only. */
    /** What the file will be called on the phone: unsafe characters become "-", ".pdf" is added. */
    private fun fileNameFor(typed: String): String {
        val safe = typed.trim().replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "document" }
        return if (safe.endsWith(".pdf", true)) safe else "$safe.pdf"
    }

    private fun canWrite(p: PdfEntity) = if (p.uri.startsWith("file://")) (allFiles || Imports.isImported(ctx, p.uri)) else p.uri.substringBefore("/document/") in writable

    /** Does the phone folder of [p] already hold a file called [fileName]? Asks the phone itself. */
    private fun folderHasFile(p: PdfEntity, fileName: String): Boolean {
        if (p.uri.startsWith("file://")) {
            val me = java.io.File(PhoneOps.idOf(p.uri))
            return me.parentFile?.listFiles()?.any { it.name.equals(fileName, true) && it.path != me.path } ?: false
        }
        return try {
            val uri = Uri.parse(p.uri)
            val docId = DocumentsContract.getDocumentId(uri)
            if ('/' !in docId) return false
            val tree = DocumentsContract.buildTreeDocumentUri(uri.authority, DocumentsContract.getTreeDocumentId(uri))
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId.substringBeforeLast('/'))
            ctx.contentResolver.query(
                kids, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getString(0) != docId && (c.getString(1) ?: "").equals(fileName, true)) return true
                }
            }
            false
        } catch (e: Exception) { false }
    }

    /** Live check for the rename box: a message to show, or null when the name is fine. */
    suspend fun renameProblem(p: PdfEntity, typed: String): String? = withContext(Dispatchers.IO) {
        val t = typed.trim()
        if (t.isEmpty()) return@withContext null
        val fileName = fileNameFor(t)
        if (fileName.length > 120) return@withContext "That name is too long."
        if (!canWrite(p) || fileName.equals(p.name, true)) return@withContext null
        val dir = p.uri.substringBeforeLast("%2F", "")
        val knownClash = dir.isNotEmpty() && dao.pdfsOnce().any {
            it.id != p.id && !it.missing && it.name.equals(fileName, true) && it.uri.substringBeforeLast("%2F", "") == dir
        }
        if (knownClash || folderHasFile(p, fileName)) "A file named “$fileName” already exists in this phone folder. Choose another name." else null
    }

    /** Renames the real file when Shelf has edit access; otherwise only the name inside Shelf changes. */
    fun renamePdf(p: PdfEntity, newName: String) {
        val wanted = newName.trim()
        if (wanted.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val fileName = fileNameFor(wanted)
            val problem = renameProblem(p, wanted)
            if (problem != null) { withContext(Dispatchers.Main) { toast(problem) }; return@launch }
            var renamed: Uri? = null
            var exists = false
            if (p.uri.startsWith("file://")) {
                renamed = PhoneOps.renameFile(p.uri, fileName)
            } else {
                try { renamed = DocumentsContract.renameDocument(ctx.contentResolver, Uri.parse(p.uri), fileName) }
                catch (e: IllegalStateException) { exists = (e.message ?: "").contains("exist", true) }
                catch (e: Exception) { }
            }
            if (exists) {
                withContext(Dispatchers.Main) { toast("A file named “$fileName” already exists in this phone folder. Choose another name.") }
                return@launch
            }
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

    /**
     * "Delete from phone": the file moves into a hidden Trash folder next to it on the phone, so a slip is never final.
     * Restore (or Undo) puts it back; Delete forever in Settings › Trash removes it for good. Needs edit access.
     */
    fun trashPdfs(ids: List<Long>) {
        val targets = allPdfs.value.filter { it.id in ids && it.trashed == 0L }
        clearSel()
        viewModelScope.launch(Dispatchers.IO) {
            val done = ArrayList<Long>()
            var failed = 0
            var gone = 0
            for (p in targets) {
                // A PDF that came in from another app lives in Shelf's own storage: deleting it just deletes that copy.
                if (Imports.isImported(ctx, p.uri)) { Imports.delete(ctx, p.uri); dao.deleteByIds(listOf(p.id)); gone++; continue }
                if (p.missing) { dao.deleteByIds(listOf(p.id)); continue }
                if (!PhoneOps.isTreeDoc(p.uri) || !canWrite(p)) { failed++; continue }
                val tree = PhoneOps.treeOf(p.uri)
                val root = PhoneOps.rootId(tree)
                val srcId = PhoneOps.idOf(p.uri)
                val from = PhoneOps.parentId(srcId, root)
                val bin = PhoneOps.ensureDir(ctx, tree, root, PhoneOps.TRASH)
                val slot = if (bin != null) PhoneOps.ensureDir(ctx, tree, bin, p.id.toString()) else null
                val newId = if (slot != null) PhoneOps.move(ctx, tree, srcId, from, slot) else null
                if (newId == null) { failed++; continue }
                dao.setTrash(p.id, PhoneOps.docUri(tree, newId).toString(), System.currentTimeMillis(), from)
                done.add(p.id)
            }
            withContext(Dispatchers.Main) {
                if (gone > 0) toast(if (gone == 1) "Deleted" else "$gone PDFs deleted")
                if (failed > 0) {
                    toast("Couldn't move $failed PDF" + (if (failed == 1) "" else "s") + " to the Trash. Open Settings and tap the folder to give Shelf edit access.")
                }
                if (done.isNotEmpty()) {
                    undo = UndoNote(if (done.size == 1) "Moved to Trash" else "${done.size} PDFs moved to Trash") { restoreFromTrash(done.toList()) }
                }
            }
        }
    }

    /** Puts trashed PDFs back where they were. A file whose name is already taken there is left in the Trash. */
    fun restoreFromTrash(ids: List<Long>) {
        val targets = allPdfs.value.filter { it.id in ids && it.trashed > 0L }
        viewModelScope.launch(Dispatchers.IO) {
            var ok = 0; var clash = 0; var failed = 0
            for (p in targets) {
                if (!PhoneOps.isTreeDoc(p.uri)) { failed++; continue }
                val tree = PhoneOps.treeOf(p.uri)
                val root = PhoneOps.rootId(tree)
                val srcId = PhoneOps.idOf(p.uri)
                val slot = PhoneOps.parentId(srcId, root)
                var dest = p.origDir ?: root
                if (PhoneOps.hasName(ctx, tree, dest, p.name)) { clash++; continue }
                var newId = PhoneOps.move(ctx, tree, srcId, slot, dest)
                if (newId == null && dest != root) {
                    dest = root
                    if (PhoneOps.hasName(ctx, tree, dest, p.name)) { clash++; continue }
                    newId = PhoneOps.move(ctx, tree, srcId, slot, dest)
                }
                if (newId == null) { failed++; continue }
                dao.untrash(p.id, PhoneOps.docUri(tree, newId).toString())
                PhoneOps.delete(ctx, PhoneOps.docUri(tree, slot).toString())
                ok++
            }
            withContext(Dispatchers.Main) {
                undo = null
                val parts = ArrayList<String>()
                if (ok > 0) parts.add(if (ok == 1) "Restored" else "Restored $ok PDFs")
                if (clash > 0) parts.add("$clash not restored: a file with the same name is already in that folder")
                if (failed > 0) parts.add("$failed couldn't be restored")
                if (parts.isNotEmpty()) toast(parts.joinToString(". "))
            }
        }
    }

    /** Deletes trashed PDFs for good. This cannot be undone. */
    fun deleteForever(ids: List<Long>) {
        val targets = allPdfs.value.filter { it.id in ids && it.trashed > 0L }
        viewModelScope.launch(Dispatchers.IO) {
            val done = ArrayList<Long>()
            var failed = 0
            for (p in targets) {
                val gone = PhoneOps.delete(ctx, p.uri) || !PhoneOps.exists(ctx, p.uri)
                if (!gone) { failed++; continue }
                if (PhoneOps.isTreeDoc(p.uri)) {
                    val tree = PhoneOps.treeOf(p.uri)
                    PhoneOps.delete(ctx, PhoneOps.docUri(tree, PhoneOps.parentId(PhoneOps.idOf(p.uri), PhoneOps.rootId(tree))).toString())
                }
                done.add(p.id)
            }
            done.chunked(400).forEach { dao.deleteByIds(it) }
            withContext(Dispatchers.Main) {
                when {
                    failed == 0 && done.size == 1 -> toast("Deleted forever")
                    failed == 0 -> toast("${done.size} PDFs deleted forever")
                    else -> toast("Couldn't delete $failed PDF" + (if (failed == 1) "" else "s") + ". Open Settings and tap the folder to give Shelf edit access.")
                }
            }
        }
    }

    fun saveProgress(id: Long, page: Int, count: Int) = viewModelScope.launch {
        dao.progress(id, page, count)
        if (count > 0 && page >= count - 1) dao.setDone(listOf(id), true)   // reached the last page: finished
    }
    fun saveZoom(id: Long, scale: Float, tx: Float) = viewModelScope.launch { dao.setZoom(id, scale, tx) }
    fun toggleBookmark(pdfId: Long, page: Int, existing: BookmarkEntity?) = viewModelScope.launch {
        if (existing != null) dao.deleteBookmark(existing.id) else dao.addBookmark(BookmarkEntity(pdfId = pdfId, page = page))
    }
    fun deleteBookmark(id: Long) = viewModelScope.launch { dao.deleteBookmark(id) }

    // ---------- backup (organisation only, never the PDFs) ----------
    fun exportBackup(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        try {
            val f = dao.foldersOnce(); val p = dao.pdfsOnce().filter { it.trashed == 0L }; val b = dao.bookmarksOnce()
            val uriOf = p.associate { it.id to it.uri }
            val root = JSONObject().put("app", "shelf").put("version", 1)
            root.put("folders", JSONArray().also { a ->
                f.forEach { a.put(JSONObject().put("id", it.id).put("name", it.name).put("parentId", it.parentId ?: JSONObject.NULL).put("color", it.color).put("pinned", it.pinned).put("sortIndex", it.sortIndex).put("favorite", it.favorite).put("removed", it.removed).put("link", it.link ?: JSONObject.NULL)) }
            })
            root.put("pdfs", JSONArray().also { a ->
                p.filter { it.folderId != null || it.favorite || it.lastPage > 0 || it.label != null || it.hidden || it.pinned || it.sortIndex > 0 || it.done }.forEach {
                    a.put(JSONObject().put("uri", it.uri).put("name", it.name).put("size", it.size)
                        .put("folderId", it.folderId ?: JSONObject.NULL).put("favorite", it.favorite)
                        .put("lastPage", it.lastPage).put("pageCount", it.pageCount)
                        .put("label", it.label ?: JSONObject.NULL).put("hidden", it.hidden)
                        .put("pinned", it.pinned).put("sortIndex", it.sortIndex).put("done", it.done))
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
                    if (o.optBoolean("done", false)) dao.setDone(listOf(m.id), true)
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
