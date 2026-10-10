package com.satory.graphenosai.search

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStreamWriter
import java.net.URL
import java.net.URLEncoder
import java.net.URLDecoder
import javax.net.ssl.HttpsURLConnection

/**
 * Keyless DuckDuckGo search client.
 *
 * Uses the no-JS HTML endpoint (html.duckduckgo.com/html/), which serves a
 * complete results page in a single POST with no API key, no vqd token and
 * no JavaScript. Free and privacy-friendly: DuckDuckGo does not track queries
 * to a user profile.
 *
 * Result links may be wrapped in redirect URLs of the form
 *   //duckduckgo.com/l/?uddg=<url-encoded real url>&rut=...
 * and are decoded here (current HTML serves direct URLs; both formats work).
 * Rate limiting can occur under abusive volume (HTTP 202 with an anomaly
 * page); the client surfaces that as an empty result list with a log line
 * rather than an exception.
 */
class DuckDuckGoSearchClient {

    companion object {
        private const val TAG = "DuckDuckGoSearch"
        private const val DDG_HTML_URL = "https://html.duckduckgo.com/html/"
        private const val TIMEOUT_MS = 15000
        private const val MAX_RESULTS = 5

        private val RESULT_LINK = Regex(
            """<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val RESULT_SNIPPET = Regex(
            """<a[^>]+class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val TAG_REGEX = Regex("""<[^>]+>""")

        // HTML entities seen in DDG titles/snippets
        private val ENTITY_AMP = Regex("""&""")
        private val ENTITY_QUOT = Regex(""""""")
        private val ENTITY_LT = Regex("""<""")
        private val ENTITY_GT = Regex(""">""")
        private val ENTITY_APOS = Regex("""&(#x27|#39);""")
        private val ENTITY_NBSP = Regex("""&nbsp;""")

        /** Decode DuckDuckGo's wrapped redirect href into the real target URL. */
        fun unwrapUrl(href: String): String {
            val uddg = Regex("""[?&]uddg=([^&]+)""").find(href)?.groupValues?.get(1) ?: return href
            return try {
                URLDecoder.decode(uddg, "UTF-8")
            } catch (e: Exception) {
                uddg
            }
        }

        private fun stripTags(html: String): String {
            var out = TAG_REGEX.replace(html, "")
            out = ENTITY_NBSP.replace(out, " ")
            out = ENTITY_APOS.replace(out, "'")
            out = ENTITY_QUOT.replace(out, "\"")
            out = ENTITY_LT.replace(out, "<")
            out = ENTITY_GT.replace(out, ">")
            out = ENTITY_AMP.replace(out, "&")
            return out.trim()
        }
    }

    /**
     * Always "configured": no API key is needed. Exists so this client plugs
     * into the same isConfigured() gate as the keyed engines.
     */
    fun isConfigured(): Boolean = true

    suspend fun search(query: String, maxResults: Int = MAX_RESULTS): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val found = searchDdg(query, maxResults)
                Log.i(TAG, "DuckDuckGo search returned ${found.size} results")
                found
            } catch (e: Exception) {
                Log.e(TAG, "DuckDuckGo search failed", e)
                emptyList()
            }
        }

    private fun searchDdg(query: String, maxResults: Int): List<SearchResult> {
        val connection = (URL(DDG_HTML_URL).openConnection() as HttpsURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            doOutput = true
            // A normal browser UA keeps the bot filter from 202-ing the request
            setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
            )
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        try {
            val form = "q=" + URLEncoder.encode(query, "UTF-8")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(form) }

            val status = connection.responseCode
            if (status != HttpsURLConnection.HTTP_OK) {
                // 202 = DDG's soft rate-limit/anomaly page
                Log.e(TAG, "DuckDuckGo HTML error: HTTP $status")
                return emptyList()
            }

            val body = connection.inputStream.bufferedReader().readText()
            return parseResults(body, maxResults)
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResults(html: String, maxResults: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()

        // Each organic result has an <a class="result__a"> title link inside a
        // result block, with an optional <a class="result__snippet"> after it.
        for (link in RESULT_LINK.findAll(html)) {
            if (results.size >= maxResults) break
            val href = link.groupValues[1]
            val title = stripTags(link.groupValues[2])
            val url = unwrapUrl(href)
            if (title.isBlank() || url.isBlank() || !url.startsWith("http")) continue

            val after = html.substring(link.range.last + 1)
            val snippet = RESULT_SNIPPET.find(after)?.let { stripTags(it.groupValues[1]) } ?: ""

            results.add(SearchResult(title = title, url = url, snippet = snippet))
        }

        return results
    }
}
