package com.dallycontrol.agent.support

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.dallycontrol.core.support.SupportProtocol
import java.io.File
import java.io.OutputStream

/**
 * File transfer inside a support session (ADR 0012).
 *
 * - Console → phone: the file lands in the phone's **Descargas/Soporte** folder (MediaStore on Android 10+, so no
 *   storage permission is involved), where the person and every app can find it.
 * - Phone → console: the operator browses the shared storage the agent can read. With "all files access" (given by
 *   the USB enroller) that is everything; without it Android only shows the agent its own Soporte folder and the
 *   phone's photos, videos and audio (media permissions a Device Owner grants itself).
 */
class SupportFiles(private val context: Context) {

    private class Incoming(val name: String, val stream: OutputStream, val uri: Uri?, val file: File?, var received: Long, val size: Long)

    private val incoming = HashMap<Int, Incoming>()

    /** `file.put`: open the destination. Returns an error text, or null. */
    @Synchronized
    fun begin(id: Int, rawName: String?, size: Long): String? {
        if (size < 0 || size > SupportProtocol.MAX_UPLOAD_BYTES) return "el archivo supera el límite de 200 MB"
        incoming.remove(id)?.let { abort(it) }
        val name = SupportProtocol.safeName(rawName)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("no se pudo crear el archivo")
                val stream = context.contentResolver.openOutputStream(uri) ?: error("no se pudo abrir el archivo")
                incoming[id] = Incoming(name, stream, uri, null, 0, size)
            } else {
                val dir = legacyFolder()
                val file = unique(dir, name)
                incoming[id] = Incoming(file.name, file.outputStream(), null, file, 0, size)
            }
            null
        }.getOrElse { it.message ?: "no se pudo guardar" }
    }

    /** A chunk of an upload: `[3][id][bytes…]`. */
    @Synchronized
    fun chunk(id: Int, data: ByteArray, offset: Int, length: Int): String? {
        val t = incoming[id] ?: return "transferencia desconocida"
        if (t.received + length > SupportProtocol.MAX_UPLOAD_BYTES) {
            incoming.remove(id)
            abort(t)
            return "el archivo supera el límite de 200 MB"
        }
        return runCatching {
            t.stream.write(data, offset, length)
            t.received += length
            null
        }.getOrElse {
            incoming.remove(id)
            abort(t)
            it.message ?: "error al escribir"
        }
    }

    /** `file.end`: close and publish. Returns the visible path, or throws with the reason. */
    @Synchronized
    fun finish(id: Int): String {
        val t = incoming.remove(id) ?: error("transferencia desconocida")
        t.stream.close()
        if (t.uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.contentResolver.update(t.uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        }
        return "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/${t.name}"
    }

    @Synchronized
    fun abortAll() {
        incoming.values.forEach(::abort)
        incoming.clear()
    }

    private fun abort(t: Incoming) {
        runCatching { t.stream.close() }
        runCatching { t.uri?.let { context.contentResolver.delete(it, null, null) } }
        runCatching { t.file?.delete() }
    }

    private fun legacyFolder(): File {
        val public = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)
        if ((public.isDirectory || public.mkdirs()) && public.canWrite()) return public
        return File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER).apply { mkdirs() }
    }

    private fun unique(dir: File, name: String): File {
        var f = File(dir, name)
        var n = 1
        while (f.exists()) {
            val dot = name.lastIndexOf('.')
            f = File(dir, if (dot > 0) "${name.substring(0, dot)} ($n)${name.substring(dot)}" else "$name ($n)")
            n++
        }
        return f
    }

    // --- phone → console -----------------------------------------------------------------------------------------

    data class Entry(val name: String, val path: String, val dir: Boolean, val size: Long, val modified: Long)
    data class Listing(val path: String, val entries: List<Entry>, val limited: Boolean)

    private fun root(): File = Environment.getExternalStorageDirectory()

    /** The shared storage is readable as plain files (all-files access, or the pre-Android 11 storage permission). */
    private fun fullAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else root().canRead() && root().list() != null

    /** A path the operator sent, confined to the shared storage. Null when it points outside. */
    private fun resolve(path: String?): File? {
        val base = root().canonicalFile
        val f = File(base, (path ?: "").trimStart('/')).canonicalFile
        return if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f else null
    }

    fun list(path: String?): Listing {
        val base = root().canonicalFile
        if (!fullAccess()) return Listing("", mediaEntries(), limited = true)
        val dir = resolve(path)?.takeIf { it.isDirectory } ?: base
        val rel = dir.path.removePrefix(base.path).trimStart(File.separatorChar)
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .take(MAX_ENTRIES)
            .map { Entry(it.name, if (rel.isEmpty()) it.name else "$rel/${it.name}", it.isDirectory, if (it.isFile) it.length() else 0, it.lastModified()) }
        return Listing(rel, entries, limited = false)
    }

    /** Without all-files access: the newest photos, videos, audio and downloads the agent may read. */
    private fun mediaEntries(): List<Entry> {
        val out = ArrayList<Entry>()
        val collections = mutableListOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) collections += MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val columns = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED)
        for (collection in collections) {
            runCatching {
                context.contentResolver.query(collection, columns, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                    var n = 0
                    while (c.moveToNext() && n++ < MAX_MEDIA_PER_KIND) {
                        val uri = Uri.withAppendedPath(collection, c.getLong(0).toString())
                        out += Entry(c.getString(1) ?: "archivo", uri.toString(), false, c.getLong(2), c.getLong(3) * MILLIS)
                    }
                }
            }.onFailure { Log.i(TAG, "media listing of $collection failed: ${it.message}") }
        }
        return out.sortedByDescending { it.modified }.take(MAX_ENTRIES)
    }

    class Outgoing(val name: String, val size: Long, val stream: java.io.InputStream)

    /** Open a file the operator asked for (`file.get`): a path under the shared storage, or a media content URI. */
    fun open(path: String): Outgoing? = runCatching {
        if (path.startsWith("content://")) {
            val uri = Uri.parse(path)
            if (uri.authority != MediaStore.AUTHORITY) return null
            var name = "archivo"
            var size = -1L
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { name = c.getString(0) ?: name; size = c.getLong(1) }
            }
            Outgoing(name, size, context.contentResolver.openInputStream(uri) ?: return null)
        } else {
            val f = resolve(path)?.takeIf { it.isFile && it.canRead() } ?: return null
            Outgoing(f.name, f.length(), f.inputStream())
        }
    }.getOrNull()

    private companion object {
        const val TAG = "SupportFiles"
        const val FOLDER = "Soporte"
        const val MAX_ENTRIES = 500
        const val MAX_MEDIA_PER_KIND = 150
        const val MILLIS = 1000L
    }
}
