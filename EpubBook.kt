package com.folio.reader

import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Minimal, dependency-free EPUB 2/3 reader.
 * Parses container.xml -> OPF (metadata, manifest, spine) -> TOC (EPUB3 nav or EPUB2 NCX).
 * Content is read straight out of the zip on demand.
 */
class EpubBook private constructor(private val zip: ZipFile) : Closeable {

    data class Item(val id: String, val path: String, val mediaType: String, val properties: String)
    data class TocEntry(val title: String, val path: String, val fragment: String?, val depth: Int, val spineIndex: Int)

    var title: String = ""
        private set
    var author: String = ""
        private set
    var coverPath: String? = null
        private set

    val manifest = LinkedHashMap<String, Item>()
    val spine = ArrayList<Item>()
    val toc = ArrayList<TocEntry>()

    private var ncxId: String? = null
    private val pathToMediaType = HashMap<String, String>()
    private val lowerIndex: Map<String, ZipEntry> by lazy {
        zip.entries().asSequence().associateBy { it.name.lowercase() }
    }

    companion object {
        const val HOST = "book.local"

        fun open(file: File): EpubBook {
            val zip = ZipFile(file)
            try {
                val book = EpubBook(zip)
                book.parse()
                return book
            } catch (e: Exception) {
                zip.close()
                throw e
            }
        }

        /** Resolve an href relative to a directory inside the zip, normalising ./ and ../ */
        fun resolve(baseDir: String, href: String): String {
            val clean = Uri.decode(href.substringBefore('#').substringBefore('?'))
            val parts = ArrayList<String>()
            if (!clean.startsWith("/")) {
                baseDir.split('/').filter { it.isNotEmpty() }.forEach { parts.add(it) }
            }
            for (seg in clean.split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                    else -> parts.add(seg)
                }
            }
            return parts.joinToString("/")
        }

        fun dirOf(path: String): String = path.substringBeforeLast('/', "")

        private fun local(name: String?): String = name?.substringAfter(':') ?: ""

        fun mimeForName(name: String): String? = when (name.substringAfterLast('.', "").lowercase()) {
            "xhtml", "html", "htm", "xht" -> "text/html"
            "css" -> "text/css"
            "js" -> "application/javascript"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "svg" -> "image/svg+xml"
            "ttf" -> "font/ttf"
            "otf" -> "font/otf"
            "woff" -> "font/woff"
            "woff2" -> "font/woff2"
            "mp3" -> "audio/mpeg"
            "mp4", "m4v" -> "video/mp4"
            else -> null
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun parser(bytes: ByteArray): XmlPullParser {
        val p = Xml.newPullParser()
        try { p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false) } catch (_: Exception) {}
        try { p.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false) } catch (_: Exception) {}
        // Relaxed mode tolerates HTML entities like &nbsp; that aren't declared in XML
        try { p.setFeature("http://xmlpull.org/v1/doc/features.html#relaxed", true) } catch (_: Exception) {}
        p.setInput(ByteArrayInputStream(bytes), null)
        return p
    }

    private fun attr(p: XmlPullParser, name: String): String? {
        for (i in 0 until p.attributeCount) {
            if (local(p.getAttributeName(i)) == name) return p.getAttributeValue(i)
        }
        return null
    }

    private fun parse() {
        val container = readBytes("META-INF/container.xml")
            ?: throw IOException("This isn't a valid EPUB (missing META-INF/container.xml).")
        var opfPath: String? = null
        val p = parser(container)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && local(p.name) == "rootfile") {
                val fp = attr(p, "full-path")
                if (!fp.isNullOrEmpty()) {
                    opfPath = resolve("", fp)
                    break
                }
            }
        }
        val opf = opfPath ?: throw IOException("EPUB has no package document.")
        val opfBytes = readBytes(opf) ?: throw IOException("EPUB package document is missing: $opf")
        parseOpf(opfBytes, dirOf(opf))
        if (spine.isEmpty()) throw IOException("EPUB has no readable chapters.")
        parseToc()
    }

    private fun parseOpf(bytes: ByteArray, dir: String) {
        val p = parser(bytes)
        var coverId: String? = null
        val spineIds = ArrayList<String>()
        var capture: String? = null
        val sb = StringBuilder()
        var inMetadata = false

        loop@ while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break@loop
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "metadata" -> inMetadata = true
                    "title" -> if (inMetadata && title.isEmpty()) { capture = "title"; sb.setLength(0) }
                    "creator" -> if (inMetadata && author.isEmpty()) { capture = "creator"; sb.setLength(0) }
                    "meta" -> if (attr(p, "name") == "cover") coverId = attr(p, "content")
                    "item" -> {
                        val id = attr(p, "id")
                        val href = attr(p, "href")
                        if (id != null && href != null) {
                            val item = Item(id, resolve(dir, href), attr(p, "media-type") ?: "", attr(p, "properties") ?: "")
                            manifest[id] = item
                            pathToMediaType[item.path] = item.mediaType
                        }
                    }
                    "spine" -> ncxId = attr(p, "toc")
                    "itemref" -> attr(p, "idref")?.let { spineIds.add(it) }
                }
                XmlPullParser.TEXT -> if (capture != null) sb.append(p.text)
                XmlPullParser.END_TAG -> {
                    val n = local(p.name)
                    if (n == "metadata") inMetadata = false
                    if (capture != null && n == capture) {
                        val v = sb.toString().trim().replace(Regex("\\s+"), " ")
                        if (capture == "title") title = v else author = v
                        capture = null
                    }
                }
            }
        }

        for (id in spineIds) {
            val item = manifest[id] ?: continue
            val isHtml = item.mediaType.contains("html") || mimeForName(item.path) == "text/html"
            if (isHtml) spine.add(item)
        }

        coverPath = manifest.values.firstOrNull { "cover-image" in it.properties.split(' ') }?.path
            ?: coverId?.let { manifest[it] }?.takeIf { it.mediaType.startsWith("image") }?.path
            ?: manifest.values.firstOrNull {
                it.mediaType.startsWith("image") && (it.id.contains("cover", true) || it.path.contains("cover", true))
            }?.path
    }

    private fun parseToc() {
        val nav = manifest.values.firstOrNull { "nav" in it.properties.split(' ') }
        val ncx = ncxId?.let { manifest[it] } ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (nav != null) runCatching { parseNav(nav) }
        if (toc.isEmpty() && ncx != null) {
            toc.clear()
            runCatching { parseNcx(ncx) }
        }
        if (toc.isEmpty()) {
            spine.forEachIndexed { i, item -> toc.add(TocEntry("Section ${i + 1}", item.path, null, 0, i)) }
        }
    }

    private fun addToc(rawTitle: String, dir: String, href: String, depth: Int) {
        val path = resolve(dir, href)
        val frag = href.substringAfter('#', "").ifEmpty { null }
        val idx = spineIndexOf(path)
        if (idx < 0) return
        val t = rawTitle.trim().replace(Regex("\\s+"), " ").ifEmpty { "Untitled" }
        toc.add(TocEntry(t, path, frag, depth.coerceAtLeast(0), idx))
    }

    private fun parseNcx(item: Item) {
        val bytes = readBytes(item.path) ?: return
        val dir = dirOf(item.path)
        val p = parser(bytes)
        var depth = -1
        var inMap = false
        var inText = false
        val sb = StringBuilder()
        var label: String? = null
        loop@ while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break@loop
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "navMap" -> inMap = true
                    "navPoint" -> if (inMap) { depth++; label = null }
                    "text" -> if (inMap) { inText = true; sb.setLength(0) }
                    "content" -> if (inMap) {
                        val src = attr(p, "src")
                        if (src != null) addToc(label ?: "", dir, src, depth)
                    }
                }
                XmlPullParser.TEXT -> if (inText) sb.append(p.text)
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "navMap" -> inMap = false
                    "navPoint" -> if (inMap) depth--
                    "text" -> if (inText) {
                        inText = false
                        if (label == null) label = sb.toString()
                    }
                }
            }
        }
    }

    private fun parseNav(item: Item) {
        val bytes = readBytes(item.path) ?: return
        val dir = dirOf(item.path)
        val p = parser(bytes)
        var inToc = false
        var olDepth = 0
        var inA = false
        var href: String? = null
        val sb = StringBuilder()
        loop@ while (true) {
            when (p.next()) {
                XmlPullParser.END_DOCUMENT -> break@loop
                XmlPullParser.START_TAG -> when (local(p.name)) {
                    "nav" -> inToc = (attr(p, "type") ?: "").split(' ').contains("toc")
                    "ol" -> if (inToc) olDepth++
                    "a" -> if (inToc) { inA = true; href = attr(p, "href"); sb.setLength(0) }
                }
                XmlPullParser.TEXT -> if (inA) sb.append(p.text)
                XmlPullParser.END_TAG -> when (local(p.name)) {
                    "nav" -> inToc = false
                    "ol" -> if (inToc) olDepth--
                    "a" -> if (inA) {
                        inA = false
                        val h = href
                        if (h != null) addToc(sb.toString(), dir, h, olDepth - 1)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- content access

    private fun entry(path: String): ZipEntry? = zip.getEntry(path) ?: lowerIndex[path.lowercase()]

    fun readBytes(path: String): ByteArray? = entry(path)?.let { e -> zip.getInputStream(e).use { it.readBytes() } }

    fun openStream(path: String): InputStream? = entry(path)?.let { zip.getInputStream(it) }

    fun readText(path: String): String? = readBytes(path)?.let { decode(it) }

    private fun decode(b: ByteArray): String {
        if (b.size >= 2 && ((b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) || (b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()))) {
            return String(b, Charsets.UTF_16)
        }
        val head = String(b, 0, minOf(b.size, 1024), Charsets.ISO_8859_1)
        val m = Regex("(?:encoding|charset)\\s*=\\s*[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(head)
        val cs = m?.groupValues?.get(1)?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        var s = String(b, cs)
        if (s.startsWith("\uFEFF")) s = s.substring(1)
        return s
    }

    fun mimeFor(path: String): String =
        mimeForName(path) ?: pathToMediaType[path]?.takeIf { it.isNotEmpty() } ?: "application/octet-stream"

    fun urlFor(path: String): String = "https://$HOST/" + path.split('/').joinToString("/") { Uri.encode(it) }

    fun spineIndexOf(path: String): Int = spine.indexOfFirst { it.path == path }

    fun chapterTitle(index: Int): String {
        toc.firstOrNull { it.spineIndex == index }?.let { return it.title }
        toc.lastOrNull { it.spineIndex < index }?.let { return it.title }
        return "Section ${index + 1}"
    }

    override fun close() = zip.close()
}
