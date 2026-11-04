package com.folio.reader

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import java.util.Locale
import kotlin.math.abs

class ReaderSettings {
    var theme = "system"        // light, sepia, dark, black, system
    var fontSize = 19           // px
    var lineHeight = 1.6f
    var paraSpacing = 0.8f      // em
    var margin = 20             // px
    var letterSpacing = 0f      // em
    var font = "original"       // original, serif, sans, condensed, mono, custom:<file>
    var align = "original"      // original, left, justify
    var hyphenate = false
    var brightness = -1f        // -1 = system
    var keepScreenOn = false
    var volumeKeys = true
    var pullToTurn = true       // pull past the end/start of a chapter to change chapter

    data class Palette(val bg: Int, val fg: Int, val link: Int, val ui: Int, val dark: Boolean)

    fun palette(ctx: Context): Palette {
        val t = if (theme == "system") {
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            if (night) "dark" else "light"
        } else theme
        return when (t) {
            "sepia" -> Palette(0xFFF4ECD8.toInt(), 0xFF5B4636.toInt(), 0xFF8A5A2B.toInt(), 0xFFE9DEC4.toInt(), false)
            "dark" -> Palette(0xFF1E1E1E.toInt(), 0xFFD8D8D8.toInt(), 0xFF8AB4F8.toInt(), 0xFF2B2B2B.toInt(), true)
            "black" -> Palette(0xFF000000.toInt(), 0xFFB5B5B5.toInt(), 0xFF7BA7E8.toInt(), 0xFF141414.toInt(), true)
            else -> Palette(0xFFFFFFFF.toInt(), 0xFF1C1C1C.toInt(), 0xFF1A5FB4.toInt(), 0xFFF1F1F1.toInt(), false)
        }
    }

    private fun hex(c: Int, alpha: Int = 255) = String.format(Locale.US, "#%06X%02X", c and 0xFFFFFF, alpha)
    private fun f(x: Float) = String.format(Locale.US, "%.3f", x)

    private fun fontRule(ctx: Context): Pair<String, String>? = when {
        font == "serif" -> "" to "serif"
        font == "sans" -> "" to "sans-serif"
        font == "condensed" -> "" to "sans-serif-condensed, sans-serif"
        font == "mono" -> "" to "monospace"
        font.startsWith("custom:") -> {
            val name = font.removePrefix("custom:")
            if (java.io.File(Library.fontsDir(ctx), name).isFile) {
                val fam = "FolioCustom${abs(name.hashCode())}"
                "@font-face{font-family:'$fam';src:url('https://${EpubBook.HOST}/__fonts/${Uri.encode(name)}');}" to "'$fam', serif"
            } else null
        }
        else -> null
    }

    fun buildCss(ctx: Context, p: Palette, safeTop: Int, safeBottom: Int): String {
        val fg = hex(p.fg)
        val bg = hex(p.bg)
        val link = hex(p.link)
        val sb = StringBuilder()
        sb.append("html,body{background:$bg !important;color:$fg !important;}")
        sb.append("html{-webkit-text-size-adjust:100%;text-size-adjust:100%;}")
        sb.append(
            "body{margin:0 !important;padding:${safeTop + 20}px ${margin}px ${safeBottom + 28}px ${margin}px !important;" +
                "font-size:${fontSize}px !important;line-height:${f(lineHeight)} !important;" +
                "letter-spacing:${f(letterSpacing)}em !important;overflow-wrap:break-word;" +
                "max-width:none !important;width:auto !important;box-sizing:border-box;}"
        )
        sb.append("p,li,blockquote,dd,dt,td,th,span,div{line-height:${f(lineHeight)} !important;}")
        if (letterSpacing != 0f) sb.append("p,li,span,div,blockquote{letter-spacing:inherit !important;}")
        sb.append("p{margin-top:0 !important;margin-bottom:${f(paraSpacing)}em !important;}")
        if (align != "original") sb.append("p,li{text-align:$align !important;}")
        if (hyphenate) sb.append("p,li{-webkit-hyphens:auto !important;hyphens:auto !important;}")
        fontRule(ctx)?.let { (face, fam) ->
            sb.append(face)
            sb.append("body,p,div,span,li,blockquote,dd,dt,td,th,h1,h2,h3,h4,h5,h6,a,em,i,b,strong,small,sup,sub,cite{font-family:$fam !important;}")
            sb.append("pre,code,kbd,samp,tt{font-family:monospace !important;}")
        }
        if (p.dark) {
            sb.append("body *{color:inherit !important;background-color:transparent !important;border-color:${hex(p.fg, 0x44)} !important;}")
            sb.append("img{filter:brightness(.88);}")
        }
        sb.append("body a,body a *{color:$link !important;}")
        sb.append("img{max-width:100% !important;height:auto !important;}svg{max-width:100% !important;}")
        sb.append("::selection{background:${hex(p.link, 0x55)};}")
        sb.append("#__folio_nav{display:flex;gap:12px;justify-content:center;flex-wrap:wrap;margin:56px 0 12px 0 !important;padding:0 !important;text-align:center;}")
        sb.append(
            "#__folio_nav button,#__folio_nav div{font-family:sans-serif !important;font-size:15px !important;line-height:1.2 !important;" +
                "padding:12px 20px;border-radius:24px;letter-spacing:normal !important;color:$fg !important;background:transparent !important;}"
        )
        sb.append("#__folio_nav button{border:1px solid ${hex(p.fg, 0x66)} !important;}")
        sb.append(
            "#__folio_pull{position:fixed;left:50%;transform:translateX(-50%);z-index:2147483647;pointer-events:none;" +
                "opacity:0;transition:opacity .12s,background-color .12s,color .12s;white-space:nowrap;" +
                "font-family:sans-serif !important;font-size:14px !important;line-height:1.2 !important;letter-spacing:normal !important;" +
                "padding:10px 18px;border-radius:22px;background:${hex(p.ui)} !important;color:$fg !important;" +
                "border:1px solid ${hex(p.fg, 0x33)} !important;}"
        )
        sb.append("#__folio_pull.b{bottom:${safeBottom + 18}px;}#__folio_pull.t{top:${safeTop + 18}px;}")
        sb.append("#__folio_pull.armed{background:$link !important;color:$bg !important;border-color:$link !important;}")
        return sb.toString()
    }

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString("theme", theme).putInt("fontSize", fontSize).putFloat("lineHeight", lineHeight)
            .putFloat("paraSpacing", paraSpacing).putInt("margin", margin).putFloat("letterSpacing", letterSpacing)
            .putString("font", font).putString("align", align).putBoolean("hyphenate", hyphenate)
            .putFloat("brightness", brightness).putBoolean("keepScreenOn", keepScreenOn).putBoolean("volumeKeys", volumeKeys)
            .putBoolean("pullToTurn", pullToTurn)
            .apply()
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("reader_settings", Context.MODE_PRIVATE)

        fun load(ctx: Context): ReaderSettings {
            val p = prefs(ctx)
            val d = ReaderSettings()
            return ReaderSettings().apply {
                theme = p.getString("theme", d.theme) ?: d.theme
                fontSize = p.getInt("fontSize", d.fontSize)
                lineHeight = p.getFloat("lineHeight", d.lineHeight)
                paraSpacing = p.getFloat("paraSpacing", d.paraSpacing)
                margin = p.getInt("margin", d.margin)
                letterSpacing = p.getFloat("letterSpacing", d.letterSpacing)
                font = p.getString("font", d.font) ?: d.font
                align = p.getString("align", d.align) ?: d.align
                hyphenate = p.getBoolean("hyphenate", d.hyphenate)
                brightness = p.getFloat("brightness", d.brightness)
                keepScreenOn = p.getBoolean("keepScreenOn", d.keepScreenOn)
                volumeKeys = p.getBoolean("volumeKeys", d.volumeKeys)
                pullToTurn = p.getBoolean("pullToTurn", d.pullToTurn)
            }
        }
    }
}

/** Prepares an EPUB XHTML chapter for display in a WebView. */
object HtmlPrep {
    // XHTML allows <div/>, <a id="x"/>, <title/> etc. The HTML parser doesn't, so expand them.
    private val selfClosing = Regex(
        "<(?!(?:area|base|br|col|embed|hr|img|input|link|meta|param|source|track|wbr)\\b)([a-zA-Z][\\w:.-]*)(\\s[^<>]*?)?/>"
    )

    fun prepare(src: String, headInject: String, bodyEndInject: String): String {
        val s = selfClosing.replace(src) { m ->
            val tag = m.groupValues[1]
            "<$tag${m.groupValues[2]}></$tag>"
        }
        val sb = StringBuilder(s)
        val bodyEnd = Regex("</body\\s*>", RegexOption.IGNORE_CASE).findAll(sb).lastOrNull()?.range?.first ?: -1
        if (bodyEnd >= 0) sb.insert(bodyEnd, bodyEndInject) else sb.append(bodyEndInject)
        val headEnd = Regex("</head\\s*>", RegexOption.IGNORE_CASE).find(sb)?.range?.first ?: -1
        if (headEnd >= 0) sb.insert(headEnd, headInject) else sb.insert(0, headInject)
        return sb.toString()
    }
}
