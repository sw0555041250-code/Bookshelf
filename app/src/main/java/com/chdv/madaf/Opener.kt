package com.chdv.madaf

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Finds the book for a unit (a file, or the right file inside a folder) and opens it –
 * at the exact page when possible, through Search Plus's reader.
 */
object Opener {
    const val SP_PKG = "com.chdv.searchplus"
    const val SP_READER = "com.chdv.searchplus.ReaderActivity"

    class Doc(val name: String, val uri: Uri, val path: String?, val mime: String)

    private val folderCache = HashMap<String, List<Doc>>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    fun searchPlusInstalled(ctx: Context) = runCatching { ctx.packageManager.getPackageInfo(SP_PKG, 0); true }.getOrDefault(false)

    /** "primary:ספרים/x.pdf" → /storage/emulated/0/ספרים/x.pdf ; "1234-ABCD:x.pdf" → /storage/1234-ABCD/x.pdf */
    fun pathFromDocId(authority: String?, docId: String): String? {
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
        if (authority != "com.android.externalstorage.documents") return null
        val vol = docId.substringBefore(':', "")
        val rel = docId.substringAfter(':', "")
        if (vol.isEmpty()) return null
        val root = if (vol == "primary") "/storage/emulated/0" else "/storage/$vol"
        return if (rel.isEmpty()) root else "$root/$rel"
    }

    fun docFor(ctx: Context, uri: Uri, name: String): Doc {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
        val mime = ctx.contentResolver.getType(uri) ?: if (name.lowercase().endsWith(".pdf")) "application/pdf" else "*/*"
        return Doc(name, uri, id?.let { pathFromDocId(uri.authority, it) }, mime)
    }

    /** All files under a folder the user picked (a few levels deep). Slow on big folders: call off the main thread. */
    fun listFolder(ctx: Context, treeUri: String): List<Doc> {
        synchronized(folderCache) { folderCache[treeUri]?.let { return it } }
        val tree = Uri.parse(treeUri)
        val out = ArrayList<Doc>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(DocumentsContract.getTreeDocumentId(tree) to 0)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE)
        while (queue.isNotEmpty() && out.size < 8000) {
            val (dir, depth) = queue.removeFirst()
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, dir)
            runCatching {
                ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2) ?: ""
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) { if (depth < 5) queue.add(id to depth + 1) }
                        else out.add(Doc(name, DocumentsContract.buildDocumentUriUsingTree(tree, id), pathFromDocId(tree.authority, id), mime))
                    }
                }
            }
        }
        out.sortBy { it.name }
        synchronized(folderCache) { folderCache[treeUri] = out }
        return out
    }

    fun forget(treeUri: String) = synchronized(folderCache) { folderCache.remove(treeUri) }

    /** Hebrew-insensitive comparison: no niqqud, quotes, punctuation. */
    fun norm(s: String): String = s.replace(Regex("[\\u0591-\\u05C7]"), "")
        .replace(Regex("[\"'״׳_\\-–.()\\[\\],]"), " ")
        .replace(Regex("\\s+"), " ").trim().lowercase()

    /** The file whose name contains the first key that matches anything; shortest name wins. */
    fun pick(docs: List<Doc>, keys: List<String>): Doc? {
        for (k in keys) {
            val nk = norm(k)
            if (nk.isEmpty()) continue
            val hits = docs.filter { (" " + norm(it.name.substringBeforeLast('.')) + " ").contains(nk) }
            if (hits.isNotEmpty()) return hits.minByOrNull { it.name.length }
        }
        return null
    }

    fun pageCount(ctx: Context, uri: Uri): Int = runCatching {
        ctx.contentResolver.openFileDescriptor(uri, "r")!!.use { pfd -> PdfRenderer(pfd).use { it.pageCount } }
    }.getOrDefault(0)

    /** Finds the document for [unit] in the background and hands it back on the main thread (null = not found). */
    fun resolve(act: Activity, item: Item, unit: Int, done: (Doc?) -> Unit) {
        val t = item.track ?: return done(null)
        when (t.sourceType) {
            "file" -> done(docFor(act, Uri.parse(t.sourceUri), t.sourceName))
            "folder" -> {
                Toast.makeText(act, "מחפש את הספר…", Toast.LENGTH_SHORT).show()
                thread {
                    val docs = runCatching { listFolder(act, t.sourceUri) }.getOrDefault(emptyList())
                    val keys = t.fileKeys(unit)
                    var doc = pick(docs, keys)
                    if (doc == null && t.lastFile.isNotEmpty()) doc = docs.firstOrNull { it.uri.toString() == t.lastFile }
                    main.post {
                        if (doc != null || docs.isEmpty()) done(doc)
                        else chooseFrom(act, docs, "באיזה ספר ${t.fullLabel(unit)}?") { d ->
                            t.lastFile = d.uri.toString(); Store.save(); done(d)
                        }
                    }
                }
            }
            else -> done(null)
        }
    }

    fun chooseFrom(act: Activity, docs: List<Doc>, title: String, onPick: (Doc) -> Unit) {
        val shown = docs.take(400)
        AlertDialog.Builder(act, android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
            .setTitle(title)
            .setItems(shown.map { it.name }.toTypedArray()) { _, w -> onPick(shown[w]) }
            .setNegativeButton("ביטול", null)
            .show()
    }

    /** Opens unit [unit] of a track item. */
    fun openUnit(act: Activity, item: Item, unit: Int) {
        val t = item.track ?: return
        if (t.sourceType.isEmpty()) {
            if (item.linkType.isNotEmpty()) act.openLink(item)
            else Toast.makeText(act, "לא הוגדר ספר – לחיצה ארוכה › עריכה › \"מאיפה לומדים\"", Toast.LENGTH_LONG).show()
            return
        }
        resolve(act, item, unit) { doc ->
            if (doc == null) {
                Toast.makeText(act, "לא נמצא ספר מתאים בתיקייה", Toast.LENGTH_LONG).show()
                return@resolve
            }
            val page = t.pageFor(unit, doc.name)
            val label = t.fullLabel(unit)
            val isPdf = doc.mime == "application/pdf" || doc.name.lowercase().endsWith(".pdf")
            try {
                if (isPdf && doc.path != null && searchPlusInstalled(act)) {
                    act.startActivity(Intent().setClassName(SP_PKG, SP_READER)
                        .putExtra("path", doc.path).putExtra("page", page).putExtra("refLabel", label)
                        .putExtra("exact", page > 0))
                } else {
                    act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(doc.uri, doc.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                    if (page > 1) Toast.makeText(act, "$label · עמוד $page", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(act, "לא ניתן לפתוח את ${doc.name}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
