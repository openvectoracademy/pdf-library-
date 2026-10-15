package com.pdflib

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.io.File

/** Walks a granted folder tree using raw DocumentsContract queries (much faster than DocumentFile). */
object Scanner {
    class Found(val uri: String, val name: String, val size: Long, val modified: Long)

    fun scan(ctx: Context, tree: Uri, onCount: (Int) -> Unit): List<Found> {
        val out = ArrayList<Found>()
        val stack = ArrayList<String>()
        stack.add(DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED
        )
        while (stack.isNotEmpty()) {
            val parent = stack.removeAt(stack.lastIndex)
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            ctx.contentResolver.query(kids, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    val mime = c.getString(2) ?: ""
                    if (mime == Document.MIME_TYPE_DIR) {
                        if (!name.startsWith(".")) stack.add(id)
                    } else if (mime == "application/pdf" || name.endsWith(".pdf", true)) {
                        out.add(Found(DocumentsContract.buildDocumentUriUsingTree(tree, id).toString(), name, c.getLong(3), c.getLong(4)))
                        if (out.size % 25 == 0) onCount(out.size)
                    }
                }
            }
        }
        onCount(out.size)
        return out
    }
}

/** One open PDF. PdfRenderer allows a single page at a time, so every call goes through a mutex. */
class PdfDoc private constructor(private val pfd: ParcelFileDescriptor, private val renderer: PdfRenderer) {
    val pageCount: Int = renderer.pageCount
    private val mutex = Mutex()
    @Volatile private var closed = false
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 5).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    suspend fun pageSize(i: Int): Pair<Int, Int>? = mutex.withLock {
        if (closed) null else withContext(Dispatchers.IO) {
            try {
                val p = renderer.openPage(i)
                try { p.width to p.height } finally { p.close() }
            } catch (e: Exception) { null }
        }
    }

    suspend fun render(i: Int, widthPx: Int): Bitmap? {
        val key = "$i@$widthPx"
        cache.get(key)?.let { return it }
        return mutex.withLock {
            val hit = cache.get(key)
            if (hit != null) hit
            else if (closed) null
            else withContext(Dispatchers.IO) {
                try {
                    val page = renderer.openPage(i)
                    try {
                        val h = (widthPx.toFloat() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        cache.put(key, bmp)
                        bmp
                    } finally { page.close() }
                } catch (e: OutOfMemoryError) {
                    cache.evictAll(); null
                } catch (e: Exception) { null }
            }
        }
    }

    /**
     * Renders only a part of a page, at the exact pixel density it will be shown at. This is what keeps text razor sharp
     * at high zoom without ever allocating a gigantic full-page bitmap. Rectangle is in page fractions (0..1).
     */
    suspend fun renderRegion(i: Int, fullWidthPx: Float, l: Float, t: Float, r: Float, b: Float, maxPixels: Int): Bitmap? = mutex.withLock {
        if (closed) null else withContext(Dispatchers.IO) {
            try {
                val page = renderer.openPage(i)
                try {
                    var sx = fullWidthPx / page.width
                    var bw = ((r - l) * page.width * sx)
                    var bh = ((b - t) * page.height * sx)
                    if (bw * bh > maxPixels) {
                        val k = Math.sqrt(maxPixels / (bw * bh).toDouble()).toFloat()
                        sx *= k; bw *= k; bh *= k
                    }
                    val w = Math.ceil(bw.toDouble()).toInt().coerceAtLeast(1)
                    val h = Math.ceil(bh.toDouble()).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = android.graphics.Matrix()
                    m.setScale(sx, sx)
                    m.postTranslate(-l * page.width * sx, -t * page.height * sx)
                    page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bmp
                } finally { page.close() }
            } catch (e: OutOfMemoryError) {
                cache.evictAll(); null
            } catch (e: Exception) { null }
        }
    }

    fun close() {
        closed = true
        cache.evictAll()
        CoroutineScope(Dispatchers.IO).launch {
            mutex.withLock { try { renderer.close(); pfd.close() } catch (e: Exception) { } }
        }
    }

    companion object {
        suspend fun open(ctx: Context, uri: Uri): PdfDoc? = withContext(Dispatchers.IO) {
            try {
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext null
                try { PdfDoc(pfd, PdfRenderer(pfd)) } catch (e: Exception) { pfd.close(); null }
            } catch (e: Exception) { null }
        }
    }
}

/** First-page thumbnails: memory LRU + JPEG files in cacheDir. Only requested for rows that are on screen. */
object Thumbs {
    private val mem = object : LruCache<Long, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.byteCount
    }
    private val failed = HashSet<Long>()
    private val gate = Semaphore(2)

    suspend fun get(ctx: Context, p: PdfEntity): Bitmap? {
        mem.get(p.id)?.let { return it }
        if (synchronized(failed) { p.id in failed }) return null
        return gate.withPermit { withContext(Dispatchers.IO) { load(ctx, p) } }
    }

    private fun load(ctx: Context, p: PdfEntity): Bitmap? {
        try {
            val dir = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
            val file = File(dir, "${p.id}_${p.size}_v2.jpg")
            if (file.exists()) {
                val b = BitmapFactory.decodeFile(file.path)
                if (b != null) { mem.put(p.id, b); return b }
            }
            val pfd = ctx.contentResolver.openFileDescriptor(Uri.parse(p.uri), "r") ?: return null
            var r: PdfRenderer? = null
            try {
                r = PdfRenderer(pfd)
                if (r.pageCount == 0) return null
                val page = r.openPage(0)
                try {
                    // The long side is 420 px whatever the page shape, so wide slides and tall books are both crisp.
                    val k = 420f / maxOf(page.width, page.height).coerceAtLeast(1)
                    val w = (page.width * k).toInt().coerceAtLeast(1)
                    val h = (page.height * k).toInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
                    mem.put(p.id, bmp)
                    return bmp
                } finally { page.close() }
            } finally {
                try { r?.close() } catch (e: Exception) { }
                pfd.close()
            }
        } catch (e: Exception) {
            synchronized(failed) { failed.add(p.id) }
            return null
        }
    }
}
