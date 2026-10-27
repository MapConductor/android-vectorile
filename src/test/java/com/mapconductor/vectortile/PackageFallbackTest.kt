package com.mapconductor.vectortile

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the local tile server falls back to, online, for a file a package lacks. */
class PackageFallbackTest {
    private val directory: File = Files.createTempDirectory("package-fallback").toFile()

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    /** The direct path: a kept copy at once; a hung upstream gives up the worker, then fills the cache. */
    @Test
    fun packageFallbackAnswersFromTheCacheAndNeverHoldsAWorkerForLong() {
        val release = CountDownLatch(1)
        val hung =
            ConditionalFetch { _, _, _ ->
                release.await(10, TimeUnit.SECONDS)
                SourceResponse(byteArrayOf(5), etag = "\"x\"")
            }
        val cache = SourceTileCache(directory, 1L shl 20)
        val fallback =
            PackageFallback(
                upstreamUrl = { relative -> "https://tiles.example/$relative" },
                fetch = hung,
                cache = cache,
                waitMs = 200,
            )
        fallback.use {
            val started = System.nanoTime()
            assertNull(fallback("tiles/s/1/0/0.mvt"))
            val ms = (System.nanoTime() - started) / 1_000_000
            assertTrue("held the worker for ${ms}ms", ms < 1_000)
            release.countDown()
            val deadline = System.nanoTime() + 5_000_000_000
            val kept = "https://tiles.example/tiles/s/1/0/0.mvt"
            while (System.nanoTime() < deadline && cache.get(kept) == null) Thread.sleep(20)
            assertArrayEquals(byteArrayOf(5), fallback("tiles/s/1/0/0.mvt"))
        }
    }
}
