package com.pdflib

import android.app.Activity
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/** What the ⋮ menu (or the reader) asked to do with a PDF outside Shelf. */
enum class ShareKind { Share, OpenIn, Print }

class ShareReq(val pdfs: List<PdfEntity>, val kind: ShareKind) {
    constructor(pdf: PdfEntity, kind: ShareKind) : this(listOf(pdf), kind)
}

fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/** Sharing, "Open with…" and printing. Files are handed over as a clean copy (with the right name) through a FileProvider. */
object ShareTools {
    private const val DIR = "shared"

    private fun authority(ctx: Context) = ctx.packageName + ".files"

    private fun cleanName(title: String): String {
        val base = title.replace(Regex("[\\\\/:*?\"<>|]"), "-").trim().ifEmpty { "document" }
        return if (base.endsWith(".pdf", true)) base else "$base.pdf"
    }

    /** Copies the PDFs to the cache and returns shareable content:// addresses (files that can't be read are skipped). */
    suspend fun prepareAll(ctx: Context, list: List<PdfEntity>): List<Uri> = withContext(Dispatchers.IO) {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // only the latest shared files are kept
        val used = HashSet<String>()
        val out = ArrayList<Uri>()
        for (p in list) {
            try {
                var name = cleanName(p.title())
                var n = 2
                while (!used.add(name.lowercase())) name = cleanName(p.title()).removeSuffix(".pdf") + " (" + (n++) + ").pdf"
                val f = File(dir, name)
                val ok = ctx.contentResolver.openInputStream(Uri.parse(p.uri))?.use { input ->
                    FileOutputStream(f).use { input.copyTo(it) }
                    true
                } ?: false
                if (ok) out.add(FileProvider.getUriForFile(ctx, authority(ctx), f))
            } catch (e: Exception) { }
        }
        out
    }

    /** Most PDFs one share can carry: bigger batches overload many apps. */
    const val MAX_BATCH = 25

    suspend fun run(ctx: Context, req: ShareReq) {
        val all = req.pdfs
        val list = all.filter { !it.missing }
        if (list.isEmpty()) {
            toast(ctx, if (all.size == 1) "This file can't be found, so it can't be shared." else "These files can't be found, so they can't be shared.")
            return
        }
        if (list.size > MAX_BATCH) { toast(ctx, "Pick up to $MAX_BATCH PDFs to share at once."); return }
        if (list.sumOf { it.size } > 8L * 1024 * 1024) toast(ctx, if (list.size == 1) "Preparing PDF…" else "Preparing ${list.size} PDFs…")
        val uris = prepareAll(ctx, list)
        if (uris.isEmpty()) { toast(ctx, if (list.size == 1) "Couldn't read this PDF." else "Couldn't read these PDFs."); return }
        if (uris.size < all.size) toast(ctx, "${all.size - uris.size} of ${all.size} PDFs couldn't be included.")
        val p = list.first()
        val uri = uris.first()
        when (req.kind) {
            ShareKind.Share -> {
                val send = if (uris.size == 1) {
                    Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, p.title())
                        clipData = ClipData.newRawUri(p.title(), uri)
                    }
                } else {
                    Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                        type = "application/pdf"
                        putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                        putExtra(Intent.EXTRA_SUBJECT, "${uris.size} PDFs")
                        val cd = ClipData.newRawUri("PDFs", uris[0])
                        uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
                        clipData = cd
                    }
                }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launch(ctx, Intent.createChooser(send, if (uris.size == 1) "Share PDF" else "Share ${uris.size} PDFs").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            }
            ShareKind.OpenIn -> {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/pdf")
                    clipData = ClipData.newRawUri(p.title(), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                // Shelf itself reads PDFs now, so it is left out of its own "Open with" list.
                val chooser = Intent.createChooser(view, "Open with")
                    .putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, arrayOf(ComponentName(ctx, MainActivity::class.java)))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                launch(ctx, chooser)
            }
            ShareKind.Print -> {
                try {
                    val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
                    pm.print(p.title(), StreamPrintAdapter(ctx, uri, cleanName(p.title())), PrintAttributes.Builder().build())
                } catch (e: Exception) { toast(ctx, "Printing isn't available on this device.") }
            }
        }
    }

    private suspend fun toast(ctx: Context, m: String) = withContext(Dispatchers.Main) { Toast.makeText(ctx, m, Toast.LENGTH_LONG).show() }

    private suspend fun launch(ctx: Context, i: Intent) = withContext(Dispatchers.Main) {
        try {
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: Exception) { Toast.makeText(ctx, "No app can do that.", Toast.LENGTH_LONG).show() }
    }

    /** Called when the app starts: removes the temporary share copy left behind last time. */
    fun cleanup(ctx: Context) {
        try { File(ctx.cacheDir, DIR).listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
    }
}

/** Streams an existing PDF straight to the system print service. */
private class StreamPrintAdapter(private val ctx: Context, private val uri: Uri, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(
        oldAttributes: PrintAttributes?, newAttributes: PrintAttributes?, cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback, extras: Bundle?
    ) {
        if (cancellationSignal?.isCanceled == true) { callback.onLayoutCancelled(); return }
        val info = PrintDocumentInfo.Builder(name)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancellationSignal: CancellationSignal?, callback: WriteResultCallback) {
        Thread {
            try {
                ctx.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destination.fileDescriptor).use { out -> input.copyTo(out) }
                } ?: throw IllegalStateException("unreadable")
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }.start()
    }
}

/** PDFs that arrive from other apps (Open with / Share to Shelf) are copied into Shelf's own storage. */
object Imports {
    private fun dir(ctx: Context) = File(ctx.filesDir, "imported").apply { mkdirs() }

    fun isImported(ctx: Context, uri: String): Boolean =
        uri.startsWith("file://") && (Uri.parse(uri).path ?: "").startsWith(dir(ctx).path)

    /** The PDF addresses carried by an incoming VIEW / SEND / SEND_MULTIPLE intent. */
    @Suppress("DEPRECATION")
    fun urisOf(i: Intent?): List<Uri> {
        if (i == null) return emptyList()
        val out = ArrayList<Uri>()
        when (i.action) {
            Intent.ACTION_VIEW -> i.data?.let { out.add(it) }
            Intent.ACTION_SEND -> (i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: i.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri)?.let { out.add(it) }
            Intent.ACTION_SEND_MULTIPLE -> i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.addAll(it) }
        }
        return out.distinct()
    }

    fun displayName(ctx: Context, uri: Uri): String {
        var name: String? = null
        try {
            if (uri.scheme == "content") {
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0)
                }
            }
        } catch (e: Exception) { }
        val n = (name ?: uri.lastPathSegment ?: "document").substringAfterLast('/').trim().ifEmpty { "document" }
        val safe = n.replace(Regex("[\\\\/:*?\"<>|]"), "-")
        return if (safe.endsWith(".pdf", true)) safe else "$safe.pdf"
    }

    /** Copies [uri] into Shelf's storage. Returns the new file, or null when it can't be read or isn't a PDF. */
    fun copyIn(ctx: Context, uri: Uri): File? {
        return try {
            val d = dir(ctx)
            val want = displayName(ctx, uri)
            var out = File(d, want)
            var n = 1
            while (out.exists()) { out = File(d, want.removeSuffix(".pdf").removeSuffix(".PDF") + " (${n++}).pdf") }
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            input.use { ins ->
                FileOutputStream(out).use { ins.copyTo(it) }
            }
            // Must look like a PDF: "%PDF" near the start of the file.
            val head = ByteArray(1024)
            val read = out.inputStream().use { it.read(head) }
            val ok = read >= 4 && String(head, 0, read, Charsets.ISO_8859_1).contains("%PDF")
            if (!ok) { out.delete(); null } else out
        } catch (e: Exception) { null }
    }

    fun delete(ctx: Context, uri: String) {
        try { if (isImported(ctx, uri)) File(Uri.parse(uri).path ?: return).delete() } catch (e: Exception) { }
    }
}
