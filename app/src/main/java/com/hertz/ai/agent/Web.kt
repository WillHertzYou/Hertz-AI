package com.hertz.ai.agent

import android.text.Html
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/** Hertz's internet access: web search results and readable page text, fetched directly. */
object Web {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 15; SM-S931U) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0 Mobile Safari/537.36"

    data class Page(val url: String, val title: String, val text: String)
    data class Result(val title: String, val url: String, val snippet: String)

    private fun get(url: String, maxBytes: Int = 2_000_000): Pair<String, String> {
        var current = url
        repeat(5) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.5")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            }
            val code = conn.responseCode
            if (code in 300..399) {
                val loc = conn.getHeaderField("Location") ?: throw IllegalStateException("Redirect without a location")
                current = URL(URL(current), loc).toString()
                conn.disconnect()
                return@repeat
            }
            if (code >= 400) {
                conn.disconnect()
                throw IllegalStateException("The site answered with error $code")
            }
            val type = conn.contentType ?: ""
            if (!(type.contains("text") || type.contains("json") || type.contains("xml") || type.isBlank())) {
                conn.disconnect()
                throw IllegalStateException("That link is a ${type.substringBefore(';')} file, not a web page. Use open_link to open it.")
            }
            val bytes = conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (out.size() < maxBytes) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
            conn.disconnect()
            val cs = Regex("charset=([\\w-]+)").find(type)?.groupValues?.get(1) ?: "UTF-8"
            return current to String(bytes, runCatching { java.nio.charset.Charset.forName(cs) }.getOrDefault(Charsets.UTF_8))
        }
        throw IllegalStateException("Too many redirects")
    }

    private fun htmlToText(html: String): String {
        val cleaned = html
            .replace(Regex("(?is)<(script|style|noscript|svg|nav|footer|header|form)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/h[1-6]|/tr)[^>]*>"), "\n")
        return Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
            .replace(' ', ' ')
            .lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
    }

    fun fetch(url: String, maxChars: Int = 6000): Page {
        val full = if (url.startsWith("http://") || url.startsWith("https://")) url else "https://$url"
        val (finalUrl, body) = get(full)
        val isHtml = body.contains("<html", ignoreCase = true) || body.contains("<body", ignoreCase = true)
        val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(body)?.groupValues?.get(1)
            ?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() }
            ?: finalUrl
        val text = if (isHtml) htmlToText(body) else body
        val clipped = if (text.length > maxChars) text.take(maxChars) + "\n…(page continues)" else text
        return Page(finalUrl, title, clipped)
    }

    fun search(query: String, max: Int = 6): List<Result> {
        val (_, html) = get("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, "UTF-8"))
        val links = Regex("(?is)<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>").findAll(html).toList()
        val snippets = Regex("(?is)class=\"result__snippet\"[^>]*>(.*?)</a>").findAll(html).map { it.groupValues[1] }.toList()
        return links.take(max).mapIndexed { i, m ->
            var href = Html.fromHtml(m.groupValues[1], Html.FROM_HTML_MODE_LEGACY).toString()
            Regex("uddg=([^&]+)").find(href)?.let { href = URLDecoder.decode(it.groupValues[1], "UTF-8") }
            if (href.startsWith("//")) href = "https:$href"
            Result(
                title = Html.fromHtml(m.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString().trim(),
                url = href,
                snippet = snippets.getOrNull(i)?.let { Html.fromHtml(it, Html.FROM_HTML_MODE_LEGACY).toString().trim() } ?: "",
            )
        }.filter { !it.url.contains("duckduckgo.com/y.js") }
    }
}
