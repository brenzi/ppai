package ch.brenzi.prettyprivateai

import ch.brenzi.prettyprivateai.data.remote.StartpageSearchClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class StartpageSearchClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: StartpageSearchClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = StartpageSearchClient(baseUrl = server.url("/").toString().trimEnd('/'))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parseResults extracts title, url, and snippet from Startpage HTML`() {
        val html = """
            <html><body>
            <div class="result css-abc123">
                <a class="result-title result-link css-xyz" href="https://example.com/page1" target="_blank">
                    <h2 class="wgl-title css-i3irj7">Example Page One</h2>
                </a>
                <p class="description css-1507v2l">This is the first result snippet.</p>
            </div>
            <div class="result css-abc123">
                <a class="result-title result-link css-xyz" href="https://example.com/page2" target="_blank">
                    <h2 class="wgl-title css-i3irj7">Example Page Two</h2>
                </a>
                <p class="description css-1507v2l">Second result snippet text.</p>
            </div>
            <div class="result css-abc123">
                <a class="result-title result-link css-xyz" href="https://example.com/page3" target="_blank">
                    <h2 class="wgl-title css-i3irj7">Example Page Three</h2>
                </a>
                <p class="description css-1507v2l">Third result snippet.</p>
            </div>
            </body></html>
        """.trimIndent()

        val results = client.parseResults(html, maxResults = 5)

        assertEquals(3, results.size)
        assertEquals("Example Page One", results[0].title)
        assertEquals("https://example.com/page1", results[0].url)
        assertEquals("This is the first result snippet.", results[0].snippet)
        assertEquals("Example Page Two", results[1].title)
        assertEquals("https://example.com/page2", results[1].url)
    }

    @Test
    fun `parseResults respects maxResults`() {
        val html = """
            <html><body>
            <div class="result css-a"><a class="result-title" href="https://a.com"><h2 class="wgl-title">A</h2></a></div>
            <div class="result css-b"><a class="result-title" href="https://b.com"><h2 class="wgl-title">B</h2></a></div>
            <div class="result css-c"><a class="result-title" href="https://c.com"><h2 class="wgl-title">C</h2></a></div>
            </body></html>
        """.trimIndent()

        val results = client.parseResults(html, maxResults = 2)
        assertEquals(2, results.size)
    }

    @Test
    fun `parseResults skips ad results`() {
        val html = """
            <html><body>
            <div class="a-bg-result result css-ad">
                <a class="result-title" href="https://ad.com"><h2 class="wgl-title">Ad</h2></a>
            </div>
            <div class="result css-real">
                <a class="result-title" href="https://real.com"><h2 class="wgl-title">Real</h2></a>
            </div>
            </body></html>
        """.trimIndent()

        val results = client.parseResults(html, maxResults = 5)
        assertEquals(1, results.size)
        assertEquals("Real", results[0].title)
    }

    @Test
    fun `parseResults skips results without http URL`() {
        val html = """
            <html><body>
            <div class="result css-a"><a class="result-title" href="javascript:void(0)"><h2 class="wgl-title">Bad</h2></a></div>
            <div class="result css-b"><a class="result-title" href="https://good.com"><h2 class="wgl-title">Good</h2></a></div>
            </body></html>
        """.trimIndent()

        val results = client.parseResults(html, maxResults = 5)
        assertEquals(1, results.size)
        assertEquals("Good", results[0].title)
    }

    @Test
    fun `parseResults returns empty list for no results`() {
        val html = "<html><body><p>No results found</p></body></html>"
        val results = client.parseResults(html, maxResults = 5)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search returns results from mock server`() = runTest {
        val html = """
            <html><body>
            <div class="result css-a">
                <a class="result-title" href="https://example.com"><h2 class="wgl-title">Test Result</h2></a>
                <p class="description css-d">Test snippet</p>
            </div>
            </body></html>
        """.trimIndent()

        server.enqueue(MockResponse().setResponseCode(200).setBody(html))

        val results = client.search("test query")
        assertEquals(1, results.size)
        assertEquals("Test Result", results[0].title)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("/do/search"))
        assertTrue(request.path!!.contains("query=test+query"))
    }

    @Test
    fun `search returns empty list on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))

        val results = client.search("test")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `extractPageText strips nav, script, style and truncates`() {
        val html = """
            <html><body>
            <nav>Navigation</nav>
            <script>var x = 1;</script>
            <style>.foo { color: red; }</style>
            <header>Header</header>
            <main><p>Word1 Word2 Word3 Word4 Word5</p></main>
            <footer>Footer</footer>
            </body></html>
        """.trimIndent()

        val text = client.extractPageText(html, maxWords = 3)
        assertNotNull(text)
        assertFalse(text!!.contains("Navigation"))
        assertFalse(text.contains("var x"))
        assertFalse(text.contains("Header"))
        assertFalse(text.contains("Footer"))
        assertTrue(text.endsWith("..."))
        // Should have at most 3 words + "..."
        assertEquals(3, text.removeSuffix("...").trim().split(" ").size)
    }

    @Test
    fun `extractPageText returns null for empty body`() {
        val html = "<html><body></body></html>"
        val text = client.extractPageText(html, maxWords = 100)
        assertNull(text)
    }

    @Test
    fun `fetchPageContent returns text from mock server`() = runTest {
        val html = "<html><body><p>Hello world content here</p></body></html>"
        server.enqueue(MockResponse().setResponseCode(200).setBody(html))

        val text = client.fetchPageContent(server.url("/page").toString())
        assertNotNull(text)
        assertTrue(text!!.contains("Hello world content here"))
    }

    @Test
    fun `fetchPageContent returns null on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val text = client.fetchPageContent(server.url("/missing").toString())
        assertNull(text)
    }
}
