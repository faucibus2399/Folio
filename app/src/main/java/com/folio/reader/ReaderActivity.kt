package com.folio.reader

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ID = "book_id"

        // Injected into every chapter. Handles scroll position, taps, live CSS updates, paging with volume keys.
        private val READER_JS = """
(function(){
  var token = __TOKEN__, target = __FRAC__, ready = false;
  function maxY(){ var d=document.documentElement, b=document.body; var h=Math.max(d.scrollHeight, b?b.scrollHeight:0); return Math.max(0, h - window.innerHeight); }
  function frac(){ var m=maxY(); return m>0 ? Math.min(1, Math.max(0, window.scrollY/m)) : 0; }
  function report(){ try { Android.onScroll(token, frac()); } catch(e){} }
  function restore(){ if (location.hash) return; window.scrollTo(0, Math.round(target*maxY())); }
  function markReady(){ if (ready) return; ready = true; report(); }
  document.addEventListener('DOMContentLoaded', restore);
  window.addEventListener('load', function(){ restore(); setTimeout(markReady, 50); });
  setTimeout(function(){ if(!ready){ restore(); markReady(); } }, 2000);
  var pending = null;
  window.addEventListener('scroll', function(){ if(!ready || pending) return; pending = setTimeout(function(){ pending=null; report(); }, 200); }, {passive:true});
  document.addEventListener('click', function(e){
    var el = e.target;
    while (el && el !== document) {
      var t = (el.tagName||'').toLowerCase();
      if (t==='a'||t==='button'||t==='input'||t==='select'||t==='textarea'||t==='label'||t==='audio'||t==='video') return;
      el = el.parentNode;
    }
    try { Android.onTap(); } catch(err){}
  });
  window.__setCss = function(css){
    var f = frac(); var s = document.getElementById('__folio_css'); if (s) s.textContent = css;
    requestAnimationFrame(function(){ window.scrollTo(0, Math.round(f*maxY())); report(); });
  };
  window.__goFrac = function(f){ window.scrollTo(0, Math.round(f*maxY())); report(); };
  window.__goId = function(id){ var el = document.getElementById(id) || document.getElementsByName(id)[0]; if (el) el.scrollIntoView(); report(); };
  window.__page = function(d){
    var m = maxY();
    if (d>0 && window.scrollY >= m-2) { Android.nextChapter(); return; }
    if (d<0 && window.scrollY <= 2) { Android.prevChapterEnd(); return; }
    window.scrollBy({top: d*Math.round(window.innerHeight*0.85), behavior:'smooth'});
  };
  window.__snippet = function(){
    for (var y = 80; y < window.innerHeight; y += 30) {
      var el = document.elementFromPoint(window.innerWidth/2, y);
      while (el && el !== document.body && (el.innerText||'').trim().length < 20) el = el.parentElement;
      if (el && el !== document.body && el !== document.documentElement) {
        var t = (el.innerText||'').replace(/\s+/g,' ').trim();
        if (t) return t.substring(0, 140);
      }
    }
    return '';
  };
})();
"""
    }

    private lateinit var root: View
    private lateinit var webView: WebView
    private lateinit var topBar: View
    private lateinit var bottomBar: View
    private lateinit var titleView: TextView
    private lateinit var chapterView: TextView
    private lateinit var percentView: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnBookmark: ImageButton
    private lateinit var btnSettings: MaterialButton
    private lateinit var iconButtons: List<ImageButton>

    private lateinit var settings: ReaderSettings
    private lateinit var record: BookRecord
    private var book: EpubBook? = null
    private var bookmarks = mutableListOf<Bookmark>()

    private var chapter = 0
    private var frac = 0f
    private var pageLoaded = false
    private var uiVisible = true
    private var lastSave = 0L
    private var safeTop = 0
    private var safeBottom = 0
    private var onFontsChanged: (() -> Unit)? = null

    @Volatile private var pendingFrac = 0f
    @Volatile private var loadToken = 0
    @Volatile private var cssText = ""

    private val fontPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFont(uri)
    }

    // ------------------------------------------------------------------ lifecycle

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = Color.TRANSPARENT
            window.navigationBarColor = Color.TRANSPARENT
        }
        setContentView(R.layout.activity_reader)

        root = findViewById(R.id.root)
        webView = findViewById(R.id.webView)
        topBar = findViewById(R.id.topBar)
        bottomBar = findViewById(R.id.bottomBar)
        titleView = findViewById(R.id.bookTitle)
        chapterView = findViewById(R.id.chapterTitle)
        percentView = findViewById(R.id.percent)
        seek = findViewById(R.id.chapterSeek)
        btnBookmark = findViewById(R.id.btnBookmark)
        btnSettings = findViewById(R.id.btnSettings)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnBookmarks = findViewById<ImageButton>(R.id.btnBookmarks)
        val btnToc = findViewById<ImageButton>(R.id.btnToc)
        val btnPrev = findViewById<ImageButton>(R.id.btnPrev)
        val btnNext = findViewById<ImageButton>(R.id.btnNext)
        iconButtons = listOf(btnBack, btnBookmark, btnBookmarks, btnToc, btnPrev, btnNext)

        settings = ReaderSettings.load(this)
        val rec = intent.getStringExtra(EXTRA_ID)?.let { Library.get(this, it) }
        if (rec == null) {
            finish()
            return
        }
        record = rec
        chapter = rec.chapter
        frac = rec.frac
        bookmarks = Bookmarks.load(this, rec.id)
        titleView.text = rec.title

        btnBack.setOnClickListener { finish() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnBookmarks.setOnClickListener { showBookmarks() }
        btnToc.setOnClickListener { showToc() }
        btnPrev.setOnClickListener { openChapter(chapter - 1, 0f) }
        btnNext.setOnClickListener { openChapter(chapter + 1, 0f) }
        btnSettings.setOnClickListener { showSettings() }
        topBar.setOnClickListener { }      // swallow taps so they don't reach the page
        bottomBar.setOnClickListener { }

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) chapterView.text = book?.chapterTitle(p) ?: ""
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                if (sb.progress != chapter) openChapter(sb.progress, 0f) else updateBars()
            }
        })

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val bars = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            topBar.setPadding(bars.left, bars.top, bars.right, 0)
            bottomBar.setPadding(bars.left, 0, bars.right, bars.bottom)
            val d = resources.displayMetrics.density
            val nt = (bars.top / d).roundToInt()
            val nb = (bars.bottom / d).roundToInt()
            if (nt != safeTop || nb != safeBottom) {
                safeTop = nt
                safeBottom = nb
                applySettings()
            }
            insets
        }

        setupWebView()
        applySettings()

        val file = Library.bookFile(this, rec.id)
        Thread {
            try {
                val b = EpubBook.open(file)
                runOnUiThread {
                    if (isDestroyed) b.close() else {
                        book = b
                        onBookReady()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Can't open this book")
                        .setMessage(e.message ?: "Unknown error")
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnCancelListener { finish() }
                        .show()
                }
            }
        }.start()
    }

    private fun onBookReady() {
        val b = book ?: return
        seek.max = (b.spine.size - 1).coerceAtLeast(0)
        val start = chapter.coerceIn(0, b.spine.size - 1)
        chapter = -1 // force a load
        openChapter(start, frac)
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (!prefs.getBoolean("hintShown", false)) {
            prefs.edit().putBoolean("hintShown", true).apply()
            toast("Scroll to read. Tap the page to show or hide the menu.")
        }
        setUiVisible(false)
    }

    override fun onPause() {
        super.onPause()
        if (::record.isInitialized) saveProgress()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.stopLoading()
            webView.destroy()
        }
        book?.close()
        book = null
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySettings() // picks up system dark mode changes when theme = Auto
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (settings.volumeKeys && book != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { js("window.__page&&window.__page(1)"); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { js("window.__page&&window.__page(-1)"); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ------------------------------------------------------------------ web view

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(webView.settings) {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            domStorageEnabled = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
            useWideViewPort = false
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        webView.addJavascriptInterface(Bridge(), "Android")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                if (url.host != EpubBook.HOST) {
                    return if (url.scheme == "http" || url.scheme == "https") notFound() else null
                }
                return try {
                    serve(url, request.isForMainFrame)
                } catch (e: Exception) {
                    notFound()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == EpubBook.HOST) {
                    val b = book ?: return true
                    val idx = b.spineIndexOf((url.path ?: "").removePrefix("/"))
                    if (idx >= 0) {
                        openChapter(idx, 0f, url.fragment)
                        return true
                    }
                    return false
                }
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, url))
                } catch (e: Exception) {
                    toast("No app can open this link")
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                pageLoaded = true
            }
        }
    }

    private fun notFound() =
        WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    private fun serve(url: Uri, isMainFrame: Boolean): WebResourceResponse {
        val path = (url.path ?: "").removePrefix("/")
        if (path.startsWith("__fonts/")) {
            val f = File(Library.fontsDir(this), path.removePrefix("__fonts/"))
            return if (f.isFile) WebResourceResponse(EpubBook.mimeForName(f.name) ?: "font/ttf", null, FileInputStream(f)) else notFound()
        }
        val b = book ?: return notFound()
        val mime = b.mimeFor(path)
        if (isMainFrame && mime == "text/html") {
            val raw = b.readText(path) ?: return notFound()
            val idx = b.spineIndexOf(path)
            val html = HtmlPrep.prepare(raw, headInjection(), if (idx >= 0) navInjection(idx, b.spine.size) else "")
            return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)))
        }
        val stream = b.openStream(path) ?: return notFound()
        return WebResourceResponse(mime, if (mime.startsWith("text/")) "utf-8" else null, stream)
    }

    private fun headInjection(): String {
        val script = READER_JS
            .replace("__TOKEN__", loadToken.toString())
            .replace("__FRAC__", String.format(Locale.US, "%.5f", pendingFrac))
        return "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no\">" +
            "<style id=\"__folio_css\">$cssText</style><script>$script</script>"
    }

    private fun navInjection(index: Int, size: Int): String {
        val sb = StringBuilder("<div id=\"__folio_nav\">")
        if (index > 0) sb.append("<button onclick=\"Android.prevChapter()\">‹ Previous chapter</button>")
        if (index < size - 1) sb.append("<button onclick=\"Android.nextChapter()\">Next chapter ›</button>")
        else sb.append("<div style=\"opacity:.6\">The End</div>")
        sb.append("</div>")
        return sb.toString()
    }

    private fun js(code: String) = webView.evaluateJavascript(code, null)

    inner class Bridge {
        @JavascriptInterface fun onScroll(token: Int, f: Double) = runOnUiThread { onScrolled(token, f.toFloat().coerceIn(0f, 1f)) }
        @JavascriptInterface fun onTap() = runOnUiThread { setUiVisible(!uiVisible) }
        @JavascriptInterface fun nextChapter() = runOnUiThread { openChapter(chapter + 1, 0f) }
        @JavascriptInterface fun prevChapter() = runOnUiThread { openChapter(chapter - 1, 0f) }
        @JavascriptInterface fun prevChapterEnd() = runOnUiThread { openChapter(chapter - 1, 1f) }
    }

    // ------------------------------------------------------------------ navigation

    private fun openChapter(index: Int, f: Float, fragment: String? = null) {
        val b = book ?: return
        if (index < 0) return
        if (index >= b.spine.size) {
            toast("You've reached the end of the book")
            return
        }
        if (index == chapter && pageLoaded) {
            if (fragment != null) js("window.__goId&&window.__goId(${JSONObject.quote(fragment)})")
            else js("window.__goFrac&&window.__goFrac($f)")
            return
        }
        chapter = index
        frac = f
        pendingFrac = f
        loadToken++
        pageLoaded = false
        updateBars()
        saveProgress()
        webView.loadUrl(b.urlFor(b.spine[index].path) + if (fragment != null) "#$fragment" else "")
    }

    private fun onScrolled(token: Int, f: Float) {
        if (token != loadToken) return
        frac = f
        updateProgressText()
        updateBookmarkIcon()
        val now = System.currentTimeMillis()
        if (now - lastSave > 4000) saveProgress()
    }

    private fun overallProgress(): Float {
        val b = book ?: return 0f
        return ((chapter + frac) / b.spine.size).coerceIn(0f, 1f)
    }

    private fun saveProgress() {
        if (book == null || chapter < 0) return
        record.chapter = chapter
        record.frac = frac
        record.progress = overallProgress()
        record.lastOpened = System.currentTimeMillis()
        Library.upsert(this, record)
        lastSave = record.lastOpened
    }

    private fun updateBars() {
        val b = book ?: return
        chapterView.text = b.chapterTitle(chapter)
        seek.progress = chapter
        updateProgressText()
        updateBookmarkIcon()
    }

    private fun updateProgressText() {
        val b = book ?: return
        percentView.text = String.format(Locale.getDefault(), "Ch %d/%d · %d%%", chapter + 1, b.spine.size, (overallProgress() * 100).roundToInt())
    }

    private fun setUiVisible(v: Boolean) {
        uiVisible = v
        topBar.isVisible = v
        bottomBar.isVisible = v
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (v) c.show(WindowInsetsCompat.Type.systemBars()) else c.hide(WindowInsetsCompat.Type.systemBars())
    }

    // ------------------------------------------------------------------ table of contents

    private fun showToc() {
        val b = book ?: return
        val labels = b.toc.map { " ".repeat(it.depth.coerceAtMost(4)) + it.title }.toTypedArray()
        val current = b.toc.indexOfFirst { it.spineIndex == chapter }.takeIf { it >= 0 }
            ?: b.toc.indexOfLast { it.spineIndex < chapter }
        MaterialAlertDialogBuilder(this)
            .setTitle("Chapters")
            .setSingleChoiceItems(labels, current) { d, which ->
                d.dismiss()
                val e = b.toc[which]
                openChapter(e.spineIndex, 0f, e.fragment)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    // ------------------------------------------------------------------ bookmarks

    private fun bookmarkHere(): Bookmark? = bookmarks.firstOrNull { it.chapter == chapter && abs(it.frac - frac) < 0.015f }

    private fun updateBookmarkIcon() {
        btnBookmark.setImageResource(if (bookmarkHere() != null) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
    }

    private fun toggleBookmark() {
        val b = book ?: return
        val existing = bookmarkHere()
        if (existing != null) {
            bookmarks.remove(existing)
            Bookmarks.save(this, record.id, bookmarks)
            updateBookmarkIcon()
            toast("Bookmark removed")
            return
        }
        val c = chapter
        val f = frac
        webView.evaluateJavascript("window.__snippet ? window.__snippet() : ''") { res ->
            val snippet = runCatching { JSONTokener(res).nextValue() as? String }.getOrNull().orEmpty()
            bookmarks.add(Bookmark(c, f, b.chapterTitle(c), snippet, System.currentTimeMillis()))
            bookmarks.sortWith(compareBy<Bookmark>({ it.chapter }, { it.frac }))
            Bookmarks.save(this, record.id, bookmarks)
            updateBookmarkIcon()
            toast("Bookmarked")
        }
    }

    private fun showBookmarks() {
        if (bookmarks.isEmpty()) {
            toast("No bookmarks yet. Tap the bookmark icon to add one.")
            return
        }
        val labels = bookmarks.map { bm ->
            val pct = (bm.frac * 100).roundToInt()
            val head = "${bm.chapterTitle} · $pct%"
            if (bm.snippet.isNotBlank()) "$head\n“${bm.snippet}”" else head
        }.toTypedArray()
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Bookmarks (hold to delete)")
            .setItems(labels) { _, which ->
                val bm = bookmarks[which]
                openChapter(bm.chapter, bm.frac)
            }
            .setNegativeButton("Close", null)
            .create()
        dlg.setOnShowListener {
            dlg.listView.setOnItemLongClickListener { _, _, pos, _ ->
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete bookmark?")
                    .setMessage(labels[pos])
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Delete") { _, _ ->
                        bookmarks.removeAt(pos)
                        Bookmarks.save(this, record.id, bookmarks)
                        updateBookmarkIcon()
                        dlg.dismiss()
                        if (bookmarks.isNotEmpty()) showBookmarks()
                    }
                    .show()
                true
            }
        }
        dlg.show()
    }

    // ------------------------------------------------------------------ settings

    private fun applySettings() {
        val p = settings.palette(this)
        cssText = settings.buildCss(this, p, safeTop, safeBottom)

        root.setBackgroundColor(p.bg)
        webView.setBackgroundColor(p.bg)
        topBar.setBackgroundColor(p.ui)
        bottomBar.setBackgroundColor(p.ui)
        val tint = ColorStateList.valueOf(p.fg)
        iconButtons.forEach { it.imageTintList = tint }
        titleView.setTextColor(p.fg)
        chapterView.setTextColor(p.fg)
        percentView.setTextColor(p.fg)
        btnSettings.setTextColor(p.fg)
        seek.progressTintList = ColorStateList.valueOf(p.link)
        seek.thumbTintList = ColorStateList.valueOf(p.link)
        seek.progressBackgroundTintList = ColorStateList.valueOf(p.fg)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !p.dark
            isAppearanceLightNavigationBars = !p.dark
        }
        window.attributes = window.attributes.apply {
            screenBrightness = if (settings.brightness < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            else settings.brightness.coerceIn(0.01f, 1f)
        }
        if (settings.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (pageLoaded) js("window.__setCss&&window.__setCss(${JSONObject.quote(cssText)})")
    }

    private fun snap(sl: Slider, v: Float): Float {
        val steps = ((v - sl.valueFrom) / sl.stepSize).roundToInt()
        val snapped = sl.valueFrom + steps * sl.stepSize
        return ((snapped * 100).roundToInt() / 100f).coerceIn(sl.valueFrom, sl.valueTo)
    }

    private fun showSettings() {
        val dialog = BottomSheetDialog(this)
        val v = layoutInflater.inflate(R.layout.sheet_settings, null)
        dialog.setContentView(v)
        dialog.window?.setDimAmount(0f) // keep the page visible so changes are previewed live
        val s = settings
        fun changed() {
            s.save(this)
            applySettings()
        }

        // Theme
        val themeGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.themeGroup)
        val themeIds = mapOf(
            "light" to R.id.themeLight, "sepia" to R.id.themeSepia, "dark" to R.id.themeDark,
            "black" to R.id.themeBlack, "system" to R.id.themeSystem,
        )
        themeGroup.check(themeIds[s.theme] ?: R.id.themeSystem)
        themeGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                s.theme = themeIds.entries.firstOrNull { it.value == id }?.key ?: "system"
                changed()
            }
        }

        // Sliders
        fun slider(sliderId: Int, valueId: Int, initial: Float, fmt: (Float) -> String, set: (Float) -> Unit) {
            val sl = v.findViewById<Slider>(sliderId)
            val tv = v.findViewById<TextView>(valueId)
            sl.value = snap(sl, initial)
            tv.text = fmt(sl.value)
            sl.addOnChangeListener { _, x, fromUser ->
                tv.text = fmt(x)
                if (fromUser) {
                    set(x)
                    changed()
                }
            }
        }
        slider(R.id.fontSizeSlider, R.id.fontSizeValue, s.fontSize.toFloat(), { "${it.roundToInt()}" }) { s.fontSize = it.roundToInt() }
        slider(R.id.lineSlider, R.id.lineValue, s.lineHeight, { String.format(Locale.US, "%.1f×", it) }) { s.lineHeight = it }
        slider(R.id.paraSlider, R.id.paraValue, s.paraSpacing, { String.format(Locale.US, "%.1f", it) }) { s.paraSpacing = it }
        slider(R.id.marginSlider, R.id.marginValue, s.margin.toFloat(), { "${it.roundToInt()}" }) { s.margin = it.roundToInt() }
        slider(R.id.letterSlider, R.id.letterValue, s.letterSpacing, { String.format(Locale.US, "%.2f", it) }) { s.letterSpacing = it }

        // Font
        val fontGroup = v.findViewById<ChipGroup>(R.id.fontGroup)
        fun buildFontChips() {
            fontGroup.setOnCheckedStateChangeListener(null)
            fontGroup.removeAllViews()
            val options = mutableListOf(
                "original" to "Book default", "serif" to "Serif", "sans" to "Sans-serif",
                "condensed" to "Condensed", "mono" to "Monospace",
            )
            Library.fontsDir(this).listFiles()?.filter { it.isFile }?.sortedBy { it.name.lowercase() }?.forEach {
                options.add("custom:${it.name}" to it.nameWithoutExtension)
            }
            var anyChecked = false
            for ((key, label) in options) {
                val chip = Chip(this).apply {
                    id = View.generateViewId()
                    text = label
                    tag = key
                    isCheckable = true
                }
                if (key.startsWith("custom:")) {
                    chip.setOnLongClickListener {
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Remove font?")
                            .setMessage("Remove “$label” from Folio?")
                            .setNegativeButton("Cancel", null)
                            .setPositiveButton("Remove") { _, _ ->
                                File(Library.fontsDir(this), key.removePrefix("custom:")).delete()
                                if (s.font == key) { s.font = "original"; changed() }
                                onFontsChanged?.invoke()
                            }
                            .show()
                        true
                    }
                }
                fontGroup.addView(chip)
                if (key == s.font) { fontGroup.check(chip.id); anyChecked = true }
            }
            if (!anyChecked) fontGroup.getChildAt(0)?.let { fontGroup.check(it.id) }
            fontGroup.setOnCheckedStateChangeListener { g, ids ->
                val key = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? String } ?: return@setOnCheckedStateChangeListener
                s.font = key
                changed()
            }
        }
        buildFontChips()
        onFontsChanged = { buildFontChips() }
        v.findViewById<View>(R.id.btnImportFont).setOnClickListener { fontPicker.launch(arrayOf("*/*")) }

        // Alignment
        val alignGroup = v.findViewById<MaterialButtonToggleGroup>(R.id.alignGroup)
        val alignIds = mapOf("original" to R.id.alignOriginal, "left" to R.id.alignLeft, "justify" to R.id.alignJustify)
        alignGroup.check(alignIds[s.align] ?: R.id.alignOriginal)
        alignGroup.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                s.align = alignIds.entries.firstOrNull { it.value == id }?.key ?: "original"
                changed()
            }
        }

        // Brightness
        val brightSlider = v.findViewById<Slider>(R.id.brightnessSlider)
        val brightAuto = v.findViewById<MaterialSwitch>(R.id.brightnessAuto)
        brightAuto.isChecked = s.brightness < 0
        brightSlider.isEnabled = s.brightness >= 0
        brightSlider.value = snap(brightSlider, if (s.brightness < 0) 0.5f else s.brightness)
        brightSlider.addOnChangeListener { _, x, fromUser ->
            if (fromUser) { s.brightness = x; changed() }
        }
        brightAuto.setOnCheckedChangeListener { _, auto ->
            brightSlider.isEnabled = !auto
            s.brightness = if (auto) -1f else brightSlider.value
            changed()
        }

        // Toggles
        v.findViewById<MaterialSwitch>(R.id.swHyphen).apply {
            isChecked = s.hyphenate
            setOnCheckedChangeListener { _, c -> s.hyphenate = c; changed() }
        }
        v.findViewById<MaterialSwitch>(R.id.swKeepOn).apply {
            isChecked = s.keepScreenOn
            setOnCheckedChangeListener { _, c -> s.keepScreenOn = c; changed() }
        }
        v.findViewById<MaterialSwitch>(R.id.swVolume).apply {
            isChecked = s.volumeKeys
            setOnCheckedChangeListener { _, c -> s.volumeKeys = c; changed() }
        }

        v.findViewById<View>(R.id.btnReset).setOnClickListener {
            settings = ReaderSettings()
            settings.save(this)
            applySettings()
            dialog.dismiss()
            showSettings()
        }

        dialog.setOnDismissListener { onFontsChanged = null }
        dialog.show()
    }

    private fun importFont(uri: Uri) {
        Thread {
            try {
                val name = Util.displayName(this, uri) ?: "Custom font.ttf"
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Can't open")
                if (bytes.size < 4) throw IOException("Too small")
                val magic = String(bytes, 0, 4, Charsets.ISO_8859_1)
                val trueType = bytes[0] == 0.toByte() && bytes[1] == 1.toByte() && bytes[2] == 0.toByte() && bytes[3] == 0.toByte()
                if (!(trueType || magic == "OTTO" || magic == "true" || magic == "wOFF" || magic == "wOF2")) throw IOException("Not a font")
                val ext = when (magic) { "OTTO" -> "otf"; "wOFF" -> "woff"; "wOF2" -> "woff2"; else -> "ttf" }
                val base = name.substringBeforeLast('.').replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "Custom font" }
                val f = File(Library.fontsDir(this), "$base.$ext")
                f.writeBytes(bytes)
                runOnUiThread {
                    settings.font = "custom:${f.name}"
                    settings.save(this)
                    applySettings()
                    onFontsChanged?.invoke()
                    toast("Font “$base” added")
                }
            } catch (e: Exception) {
                runOnUiThread { toast("That file isn't a supported font (.ttf, .otf, .woff)") }
            }
        }.start()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
