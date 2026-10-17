package com.pdflib

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document

/**
 * Real file and folder changes on the phone, made through the folder access you granted.
 * Every function returns null / false instead of throwing, so a failed change never crashes Shelf.
 */
object PhoneOps {
    class Kid(val id: String, val name: String, val isDir: Boolean)

    /** Hidden folder (dot-names are skipped by the scanner) where deleted PDFs wait until you delete them for good. */
    const val TRASH = ".shelf-trash"

    fun isTreeDoc(uri: String): Boolean = uri.contains("/tree/") && uri.contains("/document/")
    fun treeOf(docUri: String): Uri = Uri.parse(docUri.substringBefore("/document/"))
    fun docUri(tree: Uri, id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
    fun rootId(tree: Uri): String = DocumentsContract.getTreeDocumentId(tree)
    fun idOf(docUri: String): String = try { DocumentsContract.getDocumentId(Uri.parse(docUri)) } catch (e: Exception) { "" }

    /** A folder or file name the phone accepts: unsafe characters become "-". */
    fun cleanName(typed: String): String = typed.trim().replace(Regex("[\\\\/:*?\"<>|]"), "-").trim()

    /** The folder that holds [docId]; for a file right at the top of the granted folder that is the folder itself. */
    fun parentId(docId: String, rootId: String): String {
        val i = docId.lastIndexOf('/')
        return if (i > 0 && docId.length > rootId.length) docId.substring(0, i) else rootId
    }

    fun kids(ctx: Context, tree: Uri, parentId: String): List<Kid> {
        val out = ArrayList<Kid>()
        try {
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

    fun createDir(ctx: Context, tree: Uri, parentId: String, name: String): String? = try {
        val made = DocumentsContract.createDocument(ctx.contentResolver, docUri(tree, parentId), Document.MIME_TYPE_DIR, name)
        if (made == null) null else DocumentsContract.getDocumentId(made)
    } catch (e: Exception) { null }

    fun ensureDir(ctx: Context, tree: Uri, parentId: String, name: String): String? {
        val have = kids(ctx, tree, parentId).firstOrNull { it.isDir && it.name == name }
        return if (have != null) have.id else createDir(ctx, tree, parentId, name)
    }

    fun rename(ctx: Context, tree: Uri, docId: String, newName: String): String? = try {
        val made = DocumentsContract.renameDocument(ctx.contentResolver, docUri(tree, docId), newName)
        if (made == null) null else DocumentsContract.getDocumentId(made)
    } catch (e: Exception) { null }

    /** Moves a file or folder to another folder of the same granted tree. Returns its new document id. */
    fun move(ctx: Context, tree: Uri, docId: String, fromParentId: String, toParentId: String): String? = try {
        val made = DocumentsContract.moveDocument(ctx.contentResolver, docUri(tree, docId), docUri(tree, fromParentId), docUri(tree, toParentId))
        if (made == null) null else DocumentsContract.getDocumentId(made)
    } catch (e: Exception) { null }

    fun exists(ctx: Context, docUri: String): Boolean = try {
        ctx.contentResolver.query(Uri.parse(docUri), arrayOf(Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { it.moveToFirst() } ?: false
    } catch (e: Exception) { false }

    fun delete(ctx: Context, docUri: String): Boolean = try {
        DocumentsContract.deleteDocument(ctx.contentResolver, Uri.parse(docUri))
    } catch (e: Exception) { false }

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
     * Moves or copies one PDF into [destParentId] of [destTree]. Same granted folder: the phone does it in one step.
     * Otherwise the bytes are copied and, for a move, the original is deleted only after the copy worked.
     * Returns the new address of the file, or null when it failed (nothing is lost in that case).
     */
    fun transfer(ctx: Context, srcUri: String, destTree: Uri, destParentId: String, name: String, move: Boolean): String? {
        if (!isTreeDoc(srcUri)) return null
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
