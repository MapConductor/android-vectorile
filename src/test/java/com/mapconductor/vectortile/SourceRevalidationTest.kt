package com.mapconductor.vectortile

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * A kept source past its age is drawn at once and asked about in the
 * background, with the validators it was kept with; the answer decides
 * whether anything is redrawn. Against a real HTTP server on loopback.
 */
class SourceRevalidationTest {
    private val directory: File = Files.createTempDirectory("source-revalidation").toFile()
    private lateinit var server: HttpServer
    private lateinit var url: String
    private val requests = Collections.synchronizedList(mutableListOf<Map<String, String?>>())

    /** What the server answers next: a status and a body. */
    @Volatile
    private var answer: Pair<Int, ByteArray?> = 304 to null

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.add(
                mapOf(
                    "If-None-Match" to exchange.requestHeaders.getFirst("If-None-Match"),
                    "If-Modified-Since" to exchange.requestHeaders.getFirst("If-Modified-Since"),
                ),
            )
            val (status, body) = answer
            exchange.responseHeaders.add("ETag", "\"v2\"")
            exchange.responseHeaders.add("Cache-Control", "max-age=600")
            if (body == null) {
                exchange.sendResponseHeaders(status, -1)
            } else {
                exchange.sendResponseHeaders(status, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            exchange.close()
        }
        server.start()
        url = "http://127.0.0.1:${server.address.port}/14/1/2.mvt"
    }

    @After
    fun tearDown() {
        server.stop(0)
        directory.deleteRecursively()
    }

    /** A cache whose clock puts every kept tile a day and a bit in the past. */
    private fun staleCache(): SourceTileCache {
        var now = 0L
        val cache = SourceTileCache(directory, 1L shl 20, clock = { now })
        cache.put(url, byteArrayOf(1, 2, 3), etag = "\"v1\"", lastModified = "Mon, 26 Oct 2026 00:00:00 GMT")
        now = SourceTileCache.DEFAULT_MAX_AGE_MS + 1
        return cache
    }

    private fun revalidate(
        cache: SourceTileCache,
        fetch: ConditionalFetch = httpSourceFetch(emptyMap()),
    ): AtomicInteger {
        val changed = AtomicInteger()
        val entry = cache.get(url)!!
        assertTrue("the kept tile should be due", entry.stale)
        SourceRevalidator(cache, fetch, onChanged = { _, _ -> changed.incrementAndGet() }).use { revalidator ->
            revalidator.revalidate(url, entry)
            // In the background; wait for its effect on the cache.
            val deadline = System.nanoTime() + 5_000_000_000
            while (System.nanoTime() < deadline && cache.get(url)!!.stale && changed.get() == 0) Thread.sleep(20)
        }
        return changed
    }

    @Test
    fun notModifiedKeepsTheTileAndRestartsItsAge() {
        answer = 304 to null
        val cache = staleCache()
        val changed = revalidate(cache)
        assertEquals(1, requests.size)
        assertEquals("\"v1\"", requests[0]["If-None-Match"])
        assertEquals("Mon, 26 Oct 2026 00:00:00 GMT", requests[0]["If-Modified-Since"])
        assertEquals(0, changed.get())
        val entry = cache.get(url)!!
        assertFalse(entry.stale)
        assertArrayEquals(byteArrayOf(1, 2, 3), entry.bytes)
        assertEquals("\"v2\"", entry.etag)
    }

    @Test
    fun newBytesReplaceTheTileAndAskForARedraw() {
        answer = 200 to byteArrayOf(7, 7)
        val cache = staleCache()
        val epoch = cache.epoch
        val changed = revalidate(cache)
        assertEquals(1, changed.get())
        assertArrayEquals(byteArrayOf(7, 7), cache.get(url)!!.bytes)
        assertEquals(epoch + 1, cache.epoch)
    }

    @Test
    fun theSameBytesAgainChangeNothing() {
        answer = 200 to byteArrayOf(1, 2, 3)
        val cache = staleCache()
        val epoch = cache.epoch
        val changed = revalidate(cache)
        assertEquals(0, changed.get())
        assertFalse(cache.get(url)!!.stale)
        assertEquals(epoch, cache.epoch)
    }

    /** Offline, the package fetcher refuses, and nothing reaches the network. */
    @Test
    fun offlineVetoesTheQuestion() {
        val asked = AtomicInteger()
        val fetcher =
            OfflinePackage.Fetcher(
                pkg = emptyPackage(),
                upstream = { null },
                online = false,
                conditionalUpstream = { u, e, m ->
                    asked.incrementAndGet()
                    httpSourceFetch(emptyMap()).fetch(u, e, m)
                },
            )
        val cache = staleCache()
        val changed = AtomicInteger()
        SourceRevalidator(cache, fetcher.revalidation!!, onChanged = { _, _ -> changed.incrementAndGet() }).use {
            it.revalidate(url, cache.get(url)!!)
            Thread.sleep(300)
        }
        assertEquals(0, asked.get())
        assertEquals(0, requests.size)
        assertTrue("still due once the network is back", cache.get(url)!!.stale)
        assertEquals(0, changed.get())

        fetcher.online = true
        SourceRevalidator(cache, fetcher.revalidation!!, onChanged = { _, _ -> }).use {
            it.revalidate(url, cache.get(url)!!)
            val deadline = System.nanoTime() + 5_000_000_000
            while (System.nanoTime() < deadline && requests.isEmpty()) Thread.sleep(20)
        }
        assertEquals(1, asked.get())
    }

    /** A package with nothing in it; the fetcher under test never reads it. */
    private fun emptyPackage(): OfflinePackage {
        val manifest =
            OfflinePackage.Manifest(
                bounds = OfflinePackage.Bounds(0.0, 0.0, 0.0, 0.0),
                minZoom = 0,
                maxZoom = 0,
                styleDigest = "",
                createdAt = 0,
                tiles = 0,
                glyphs = 0,
                sprites = 0,
                bytes = 0,
                sources = emptyMap(),
                glyphsTemplate = null,
                spriteBase = null,
            )
        // The constructor is private and `create` writes JSON, which the
        // Android stubs cannot do on the JVM.
        val constructor =
            OfflinePackage::class.java.declaredConstructors
                .first { it.parameterCount == 3 }
                .apply { isAccessible = true }
        return constructor.newInstance(directory, manifest, emptyMap<String, String>()) as OfflinePackage
    }
}
