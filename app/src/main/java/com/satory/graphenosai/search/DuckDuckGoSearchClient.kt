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
 * Result links are wrapped in redirect URLs of the form
 *   //duckduckgo.com/l/?uddg=<url-encoded real url>&rut=...
 * and are decoded here. Rate limiting can occur under abusive volume (HTTP
 * 202 with an anomaly page); the client surfaces that as an empty result
 * list with a log line rather than an exception.
 */
class DuckDuckGoSearchClient {

    companion object {
        private const val TAG = "DuckDuckGoSearch"
        private const val DDG_HTML_URL = "https://html.duckduckgo.com/html/"
        private const val TIMEOUT_MS = 15000
        private const val MAX_RESULTS = 5

        private val RESULT_BLOCK = Regex(
            """<div[^>]*class="[^"]*\bresult\b[^"]*"[^>]*>(.*?)</div>\s*(?=<div[^>]*class="[^"]*\bresult\b|\Z)""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val RESULT_LINK = Regex("""<a[^>]+class="[^"]*result__a[^"]*"[^>]+href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val RESULT_SNIPPET = Regex("""<a[^>]+class="[^"]*result__snippet[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        private val TAG_REGEX = Regex("""<[^>]+>""")

        /** Decode DuckDuckGo's wrapped redirect href into the real target URL. */
        fun unwrapUrl(href: String): String {
            val decoded = href.replace("&", "&")
            val uddg = Regex("""[?&]uddg=([^&]+)""").find(decoded)?.groupValues?.get(1) ?: return decoded
            return try {
                URLDecoder.decode(uddg, "UTF-8")
            } catch (e: Exception) {
                uddg
            }
        }

        private fun stripTags(html: String): String =
            TAG_REGEX.replace(html, "")
                .replace("&", "&")
                .replace(""", "\"")
                .replace("&#x27;", "'")
                .replace("&#39;", "'")
                .replace("<", "<")
                .replace(">", ">")
                .replace("&nbsp;", " ")
                .trim()
    }

    /**
     * Always "configured": no API key is needed. Exists so this client plugs
     * into the same isConfigured() gate as the keyed engines.
     */
    fun isConfigured(): Boolean = true

    suspend fun search(query: String, maxResults: Int = MAX_RESULTS): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val results = searchDdg(query, maxResults)
                Log.i(TAG, "DuckDuckGo search returned ${results.size} results")
                results
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
            setRequestProperty("User-Agent", "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")
            setRequestProperty("Accept", "text/html,application/xhtml+xml")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }

        try {
            val form = "q=" + URLEncoder.encode(query, "UTF-8")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(form) }

            val responseCode = connection.responseCode
            if (responseCode != HttpsURLConnection.HTTP_OK) {
                // 202 = DDG's soft rate-limit/anomaly page
                Log.e(TAG, "DuckDuckGo HTML error: HTTP $responseCode")
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

        // Each organic result lives in an <a class="result__a"> inside a
        // result block, with an optional snippet anchor next to it.
        val links = RESULT_LINK.findAll(html).toList()
        for (link in links) {
            if (results.size >= maxResults) break
            val href = link.groupValues[1]
            val title = stripTags(link.groupValues[2])
            val url = unwrapUrl(href)
            if (title.isBlank() || url.isBlank() || !url.startsWith("http")) continue

            // Snippet: search for the anchor that follows this link within the block
            val after = html.substring(link.range.last + 1)
            val snippet = RESULT_SNIPPET.find(after)?.let { stripTags(it.groupValues[1]) } ?: ""

            results.add(SearchResult(title = title, url = url, snippet = snippet))
        }

        return results
    }
}
