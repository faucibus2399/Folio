package com.folio.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.DigestInputStream
import java.security.MessageDigest

data class BookRecord(
    val id: String,
    var title: String,
    var author: String,
    var addedAt: Long,
    var lastOpened: Long = 0,
    var chapter: Int = 0,
    var frac: Float = 0f,
    var progress: Float = 0f,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("author", author)
        .put("addedAt", addedAt).put("lastOpened", lastOpened)
        .put("chapter", chapter).put("frac", frac.toDouble()).put("progress", progress.toDouble())

    companion object {
        fun fromJson(o: JSONObject) = BookRecord(
            id = o.getString("id"),
            title = o.optString("title", "Untitled"),
            author = o.optString("author", ""),
            addedAt = o.optLong("addedAt"),
            lastOpened = o.optLong("lastOpened"),
            chapter = o.optInt("chapter"),
            frac = o.optDouble("frac", 0.0).toFloat(),
            progress = o.optDouble("progress", 0.0).toFloat(),
        )
    }
}

data class Bookmark(val chapter: Int, val frac: Float, val chapterTitle: String, val snippet: String, val createdAt: Long) {
    fun toJson(): JSONObject = JSONObject().put("chapter", chapter).put("frac", frac.toDouble())
        .put("chapterTitle", chapterTitle).put("snippet", snippet).put("createdAt", createdAt)

    companion object {
        fun fromJson(o: JSONObject) = Bookmark(
            o.optInt("chapter"), o.optDouble("frac", 0.0).toFloat(),
            o.optString("chapterTitle"), o.optString("snippet"), o.optLong("createdAt"),
        )
    }
}

object Library {
    private fun prefs(c: Context) = c.getSharedPreferences("library", Context.MODE_PRIVATE)

    @Synchronized
    fun all(c: Context): MutableList<BookRecord> {
        val arr = runCatching { JSONArray(prefs(c).getString("books", "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { runCatching { BookRecord.fromJson(arr.getJSONObject(it)) }.getOrNull() }.toMutableList()
    }

    @Synchronized
    private fun saveAll(c: Context, list: List<BookRecord>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString("books", arr.toString()).apply()
    }

    fun get(c: Context, id: String): BookRecord? = all(c).firstOrNull { it.id == id }

    @Synchronized
    fun upsert(c: Context, r: BookRecord) {
        val list = all(c)
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r.copy() else list.add(r.copy())
        saveAll(c, list)
    }

    @Synchronized
    fun remove(c: Context, id: String) {
        saveAll(c, all(c).filter { it.id != id })
        bookFile(c, id).delete()
        coverFile(c, id).delete()
        Bookmarks.clear(c, id)
    }

    fun bookFile(c: Context, id: String) = File(File(c.filesDir, "books"), "$id.epub")
    fun coverFile(c: Context, id: String) = File(File(c.filesDir, "covers"), "$id.jpg")
    fun fontsDir(c: Context) = File(c.filesDir, "fonts").apply { mkdirs() }
}

object Bookmarks {
    private fun prefs(c: Context) = c.getSharedPreferences("bookmarks", Context.MODE_PRIVATE)

    fun load(c: Context, bookId: String): MutableList<Bookmark> {
        val arr = runCatching { JSONArray(prefs(c).getString(bookId, "[]")) }.getOrElse { JSONArray() }
        return (0 until arr.length()).mapNotNull { runCatching { Bookmark.fromJson(arr.getJSONObject(it)) }.getOrNull() }.toMutableList()
    }

    fun save(c: Context, bookId: String, list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs(c).edit().putString(bookId, arr.toString()).apply()
    }

    fun clear(c: Context, bookId: String) = prefs(c).edit().remove(bookId).apply()
}

object Util {
    fun displayName(ctx: Context, uri: Uri): String? {
        val fromQuery = try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return fromQuery ?: uri.lastPathSegment
    }
}

object BookImporter {
    class ImportException(msg: String) : IOException(msg)

    fun import(ctx: Context, uri: Uri): BookRecord {
        val tmp = File(ctx.cacheDir, "import_${System.nanoTime()}.tmp")
        val md = MessageDigest.getInstance("MD5")
        try {
            val input = ctx.contentResolver.openInputStream(uri) ?: throw ImportException("Couldn't open the file.")
            DigestInputStream(input, md).use { din -> tmp.outputStream().use { din.copyTo(it) } }
            checkFormat(tmp)
            val id = md.digest().joinToString("") { "%02x".format(it) }

            val existing = Library.get(ctx, id)
            if (existing != null && Library.bookFile(ctx, id).exists()) return existing

            val dest = Library.bookFile(ctx, id)
            dest.parentFile?.mkdirs()
            tmp.copyTo(dest, overwrite = true)

            val book = try {
                EpubBook.open(dest)
            } catch (e: Exception) {
                dest.delete()
                throw ImportException("Couldn't read this EPUB (${e.message ?: "unknown error"}).")
            }
            book.use { b ->
                runCatching { saveCover(ctx, id, b) }
                val fallbackTitle = Util.displayName(ctx, uri)?.substringBeforeLast('.') ?: "Untitled"
                val rec = BookRecord(id, b.title.ifBlank { fallbackTitle }, b.author, System.currentTimeMillis())
                Library.upsert(ctx, rec)
                return rec
            }
        } finally {
            tmp.delete()
        }
    }

    private fun checkFormat(f: File) {
        val head = ByteArray(68)
        val n = f.inputStream().use { it.read(head) }
        val kindleMobi = n >= 68 && String(head, 60, 8, Charsets.ISO_8859_1).let { it == "BOOKMOBI" || it == "TEXtREAd" }
        val kindleKfx = n >= 4 && String(head, 0, 4, Charsets.ISO_8859_1) == "CONT"
        if (kindleMobi || kindleKfx) {
            throw ImportException(
                "This is a Kindle file (MOBI/AZW/KFX). Folio reads EPUB only. " +
                    "If the book is DRM-free you can convert it to EPUB with the free Calibre app."
            )
        }
        if (n < 4 || head[0] != 'P'.code.toByte() || head[1] != 'K'.code.toByte()) {
            throw ImportException("This doesn't look like an EPUB file.")
        }
    }

    private fun saveCover(ctx: Context, id: String, b: EpubBook) {
        val path = b.coverPath ?: return
        val bytes = b.readBytes(path) ?: return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outHeight <= 0) return
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 360) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        val f = Library.coverFile(ctx, id)
        f.parentFile?.mkdirs()
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
    }
}
