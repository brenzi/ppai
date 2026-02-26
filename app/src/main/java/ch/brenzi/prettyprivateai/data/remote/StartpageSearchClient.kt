package ch.brenzi.prettyprivateai.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import java.util.concurrent.TimeUnit

data class SearchResult(val title: String, val snippet: String, val url: String)

class StartpageSearchClient(
    private val baseUrl: String = "https://www.startpage.com",
) {
    private val pageClient: OkHttpClient = PrivatemodeClient.sharedClient.newBuilder()
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    suspend fun search(query: String, maxResults: Int = 5): List<SearchResult> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/do/search?query=${java.net.URLEncoder.encode(query, "UTF-8")}")
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .get()
                    .build()

                val response = pageClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext emptyList()

                val html = response.body?.string() ?: return@withContext emptyList()
                parseResults(html, maxResults)
            } catch (_: Exception) {
                emptyList()
            }
        }

    internal fun parseResults(html: String, maxResults: Int): List<SearchResult> {
        val doc = Jsoup.parse(html)
        val results = mutableListOf<SearchResult>()

        for (element in doc.select("div.result")) {
            // Skip ad results
            if (element.hasClass("a-bg-result")) continue

            val titleLink = element.selectFirst("a.result-title")
            val url = titleLink?.attr("href") ?: continue
            if (!url.startsWith("http")) continue

            val title = element.selectFirst("h2.wgl-title")?.text()
                ?: titleLink.text()
            if (title.isBlank()) continue

            val snippet = element.selectFirst("p.description")?.text() ?: ""

            results.add(SearchResult(title = title, snippet = snippet, url = url))
            if (results.size >= maxResults) break
        }

        return results
    }

    suspend fun fetchPageContent(url: String, maxWords: Int = 2000): String? =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    .get()
                    .build()

                val response = pageClient.newCall(request).execute()
                if (!response.isSuccessful) return@withContext null

                val html = response.body?.string() ?: return@withContext null
                extractPageText(html, maxWords)
            } catch (_: Exception) {
                null
            }
        }

    internal fun extractPageText(html: String, maxWords: Int): String? {
        val doc = Jsoup.parse(html)
        doc.select("script, style, nav, header, footer, noscript, svg, iframe").remove()

        val text = Jsoup.clean(doc.body()?.html() ?: return null, Safelist.none())
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isBlank()) return null

        val words = text.split(" ")
        return if (words.size <= maxWords) text
        else words.take(maxWords).joinToString(" ") + "..."
    }
}
