package com.pdflib

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.Settings
import java.io.File

/** "All files access": one switch in Android's settings, then Shelf reads the phone's storage directly (Android 11+). */
object Storage {
    fun supported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    @SuppressLint("NewApi")
    fun granted(): Boolean = supported() && Environment.isExternalStorageManager()

    /** Internal storage plus any mounted SD card. */
    @SuppressLint("NewApi")
    fun roots(ctx: Context): List<File> {
        val out = ArrayList<File>()
        try {
            val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
            for (v in sm.storageVolumes) {
                val d = v.directory ?: continue
                if (v.state == Environment.MEDIA_MOUNTED && d.canRead()) out.add(d)
            }
        } catch (e: Exception) { }
        if (out.isEmpty()) out.add(Environment.getExternalStorageDirectory())
        return out
    }

    /** Opens Android's "All files access" page for Shelf. */
    @SuppressLint("NewApi")
    fun openSettings(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            try { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e2: Exception) { }
        }
    }
}

/**
 * Real file and folder changes on the phone. They work in two ways: through a folder you granted (document ids),
 * or, with All files access, directly on the file system (the "document id" is then the full path).
 * Every function returns null / false instead of throwing, so a failed change never crashes Shelf.
 */
object PhoneOps {
    class Kid(val id: String, val name: String, val isDir: Boolean)

    /** Hidden folder (dot-names are skipped by the scanner) where deleted PDFs wait until you delete them for good. */
    const val TRASH = ".shelf-trash"

    /** Storage roots Shelf reads directly (empty unless All files access is on). Set by the library. */
    @Volatile var fileRoots: List<String> = emptyList()

    fun isFile(tree: Uri): Boolean = tree.scheme == "file"

    private fun fileRootFor(path: String): String? = fileRoots.firstOrNull { path.startsWith("$it/") }

    fun isTreeDoc(uri: String): Boolean =
        if (uri.startsWith("file://")) { val p = Uri.parse(uri).path; p != null && fileRootFor(p) != null }
        else uri.contains("/tree/") && uri.contains("/document/")

    fun treeOf(docUri: String): Uri {
        if (docUri.startsWith("file://")) {
            val p = Uri.parse(docUri).path ?: ""
            return Uri.fromFile(File(fileRootFor(p) ?: "/storage/emulated/0"))
        }
        return Uri.parse(docUri.substringBefore("/document/"))
    }

    fun docUri(tree: Uri, id: String): Uri =
        if (isFile(tree)) Uri.fromFile(File(id)) else DocumentsContract.buildDocumentUriUsingTree(tree, id)

    fun rootId(tree: Uri): String = if (isFile(tree)) (tree.path ?: "") else DocumentsContract.getTreeDocumentId(tree)

    fun idOf(docUri: String): String =
        if (docUri.startsWith("file://")) (Uri.parse(docUri).path ?: "")
        else try { DocumentsContract.getDocumentId(Uri.parse(docUri)) } catch (e: Exception) { "" }

    /** The start of every file address inside [tree]. */
    fun prefixOf(tree: Uri): String =
        if (isFile(tree)) tree.toString().trimEnd('/') + "/" else tree.toString() + "/document/"

    /** A folder or file name the phone accepts: unsafe characters become "-". */
    fun cleanName(typed: String): String = typed.trim().replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()

    /** The folder that holds [docId]; for something right at the top of the granted folder that is the folder itself. */
    fun parentId(docId: String, rootId: String): String {
        val i = docId.lastIndexOf('/')
        return if (i > 0 && docId.length > rootId.length) docId.substring(0, i) else rootId
    }

    fun kids(ctx: Context, tree: Uri, parentId: String): List<Kid> {
        val out = ArrayList<Kid>()
        try {
            if (isFile(tree)) {
                File(parentId).listFiles()?.forEach { out.add(Kid(it.path, it.name, it.isDirectory)) }
                return out
            }
            val q = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
            ctx.contentResolver.query(q, arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out.add(Kid(id, name, c.getString(2) == Document.MIME_TYPE_DIR))
                }
            }
        } catch (e: Exception) { }
        return out
    }

    /** Does [parentId] already hold something called [name]? (Ignores [ignoreId], e.g. the folder being renamed.) */
    fun hasName(ctx: Context, tree: Uri, parentId: String, name: String, ignoreId: String? = null): Boolean =
        kids(ctx, tree, parentId).any { it.id != ignoreId && it.name.equals(name, true) }

    /** Document id of the folder at [rel] ("Chapter 1/Notes") below the granted folder; null if it isn't there. */
    fun resolveDir(ctx: Context, tree: Uri, rel: String): String? {
        var id = rootId(tree)
        if (rel.isEmpty()) return id
        for (seg in rel.split('/')) {
            val k = kids(ctx, tree, id).firstOrNull { it.isDir && it.name == seg } ?: return null
            id = k.id
        }
        return id
    }

    fun createDir(ctx: Context, tree: Uri, parentId: String, name: String): String? {
        if (isFile(tree)) {
            val d = File(parentId, name)
            return if (d.isDirectory || d.mkdir()) d.path else null
        }
        return try {
            val made = DocumentsContract.createDocument(ctx.contentResolver, docUri(tree, parentId), Document.MIME_TYPE_DIR, name)
            if (made == null) null else DocumentsContract.getDocumentId(made)
        } catch (e: Exception) { null }
    }

    fun ensureDir(ctx: Context, tree: Uri, parentId: String, name: String): String? {
        val have = kids(ctx, tree, parentId).firstOrNull { it.isDir && it.name == name }
        return if (have != null) have.id else createDir(ctx, tree, parentId, name)
    }

    fun rename(ctx: Context, tree: Uri, docId: String, newName: String): String? {
        if (isFile(tree)) {
            val src = File(docId)
            val dst = File(src.parentFile, newName)
            return if (!dst.exists() && src.renameTo(dst)) dst.path else null
        }
        return try {
            val made = DocumentsContract.renameDocument(ctx.contentResolver, docUri(tree, docId), newName)
            if (made == null) null else DocumentsContract.getDocumentId(made)
        } catch (e: Exception) { null }
    }

    /** Renames one PDF that Shelf reads directly (a file:// address). Returns its new address. */
    fun renameFile(fileUri: String, newName: String): Uri? {
        val src = File(idOf(fileUri))
        val dst = File(src.parentFile, newName)
        return if (!dst.exists() && src.renameTo(dst)) Uri.fromFile(dst) else null
    }

    /** Moves a file or folder to another folder of the same tree. Returns its new document id. */
    fun move(ctx: Context, tree: Uri, docId: String, fromParentId: String, toParentId: String): String? {
        if (isFile(tree)) {
            val src = File(docId)
            val dst = File(toParentId, src.name)
            return if (!dst.exists() && src.renameTo(dst)) dst.path else null
        }
        return try {
            val made = DocumentsContract.moveDocument(ctx.contentResolver, docUri(tree, docId), docUri(tree, fromParentId), docUri(tree, toParentId))
            if (made == null) null else DocumentsContract.getDocumentId(made)
        } catch (e: Exception) { null }
    }

    fun exists(ctx: Context, docUri: String): Boolean {
        if (docUri.startsWith("file://")) return File(idOf(docUri)).exists()
        return try {
            ctx.contentResolver.query(Uri.parse(docUri), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) { false }
    }

    fun delete(ctx: Context, docUri: String): Boolean {
        if (docUri.startsWith("file://")) {
            val f = File(idOf(docUri))
            return try { if (f.isDirectory) f.deleteRecursively() else f.delete() } catch (e: Exception) { false }
        }
        return try { DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(docUri)) } catch (e: Exception) { false }
    }

    private fun copyBytes(ctx: Context, src: Uri, destTree: Uri, destParentId: String, name: String): String? {
        val r = ctx.contentResolver
        var made: Uri? = null
        return try {
            val doc = DocumentsContract.createDocument(r, docUri(destTree, destParentId), "application/pdf", name) ?: return null
            made = doc
            val input = r.openInputStream(src) ?: throw IllegalStateException("no input")
            val output = r.openOutputStream(doc, "w") ?: throw IllegalStateException("no output")
            input.use { i -> output.use { o -> i.copyTo(o) } }
            docUri(destTree, DocumentsContract.getDocumentId(doc)).toString()
        } catch (e: Exception) {
            val m = made
            if (m != null) { try { DocumentsContract.deleteDocument(r, m) } catch (x: Exception) { } }
            null
        }
    }

    /**
     * Moves or copies one PDF into [destParentId] of [destTree]. On the same volume the phone does it in one step.
     * Otherwise the bytes are copied and, for a move, the original is deleted only after the copy worked.
     * Returns the new address of the file, or null when it failed (nothing is lost in that case).
     */
    fun transfer(ctx: Context, srcUri: String, destTree: Uri, destParentId: String, name: String, move: Boolean): String? {
        if (!isTreeDoc(srcUri)) return null
        if (isFile(destTree)) {
            val dst = File(destParentId, name)
            if (dst.exists()) return null
            if (move && srcUri.startsWith("file://") && File(idOf(srcUri)).renameTo(dst)) return Uri.fromFile(dst).toString()
            val ok = try {
                val input = ctx.contentResolver.openInputStream(Uri.parse(srcUri))
                if (input == null) false else { input.use { i -> dst.outputStream().use { o -> i.copyTo(o) } }; dst.exists() }
            } catch (e: Exception) { false }
            if (!ok) { dst.delete(); return null }
            if (move && !delete(ctx, srcUri)) { dst.delete(); return null }
            return Uri.fromFile(dst).toString()
        }
        val srcTree = treeOf(srcUri)
        val r = ctx.contentResolver
        if (srcTree == destTree) {
            val made: Uri? = try {
                if (move) DocumentsContract.moveDocument(r, Uri.parse(srcUri), docUri(srcTree, parentId(idOf(srcUri), rootId(srcTree))), docUri(destTree, destParentId))
                else DocumentsContract.copyDocument(r, Uri.parse(srcUri), docUri(destTree, destParentId))
            } catch (e: Exception) { null }
            if (made != null) return docUri(destTree, DocumentsContract.getDocumentId(made)).toString()
        }
        val copied = copyBytes(ctx, Uri.parse(srcUri), destTree, destParentId, name) ?: return null
        if (move && !delete(ctx, srcUri)) {
            delete(ctx, copied)
            return null
        }
        return copied
    }
}
