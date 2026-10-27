package com.mapconductor.vectortile

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** How long a kept source counts as current, and what is kept to ask about it. */
class SourceTileCacheTest {
    private val directory: File = Files.createTempDirectory("source-cache").toFile()
    private var now = 1_000_000L
    private val url = "https://tiles.example/14/1/2.mvt"

    private fun cache() = SourceTileCache(directory, 1L shl 20, clock = { now })

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun currentForADayThenStaleButStillServed() {
        cache().put(url, byteArrayOf(1, 2, 3), etag = "\"v1\"", lastModified = "Mon, 26 Oct 2026 00:00:00 GMT")
        val fresh = cache().get(url)!!
        assertFalse(fresh.stale)
        assertEquals("\"v1\"", fresh.etag)
        assertEquals("Mon, 26 Oct 2026 00:00:00 GMT", fresh.lastModified)

        now += SourceTileCache.DEFAULT_MAX_AGE_MS
        val stale = cache().get(url)!!
        assertTrue(stale.stale)
        assertArrayEquals(byteArrayOf(1, 2, 3), stale.bytes)
    }

    @Test
    fun theServersMaxAgeWins() {
        cache().put(url, byteArrayOf(1), maxAgeSeconds = 60)
        now += 59_000
        assertFalse(cache().get(url)!!.stale)
        now += 1_000
        assertTrue(cache().get(url)!!.stale)
    }

    @Test
    fun notModifiedRestartsTheAgeAndKeepsTheValidators() {
        cache().put(url, byteArrayOf(1), etag = "\"v1\"")
        now += SourceTileCache.DEFAULT_MAX_AGE_MS
        cache().refresh(url, etag = null, lastModified = "Tue, 27 Oct 2026 00:00:00 GMT", maxAgeSeconds = null)
        val entry = cache().get(url)!!
        assertFalse(entry.stale)
        assertEquals("\"v1\"", entry.etag)
        assertEquals("Tue, 27 Oct 2026 00:00:00 GMT", entry.lastModified)
    }

    /** What an earlier build kept (bytes only, the same file name) is found, and asked about. */
    @Test
    fun aTileKeptWithoutMetadataIsStaleWithNoValidators() {
        directory.mkdirs()
        val digest =
            MessageDigest
                .getInstance("SHA-256")
                .digest(url.toByteArray())
                .joinToString("") { "%02x".format(it) }
        File(directory, "$digest.pbf").writeBytes(byteArrayOf(9))
        val entry = cache().get(url)!!
        assertTrue(entry.stale)
        assertNull(entry.etag)
        assertNull(entry.lastModified)
        assertArrayEquals(byteArrayOf(9), entry.bytes)
    }

    @Test
    fun theEpochSurvivesARestart() {
        assertEquals(0L, cache().epoch)
        cache().bumpEpoch()
        cache().bumpEpoch()
        assertEquals(2L, cache().epoch)
    }

    @Test
    fun cacheControlIsRead() {
        assertEquals(3600L, parseMaxAge("public, max-age=3600"))
        assertEquals(0L, parseMaxAge("no-cache"))
        assertEquals(0L, parseMaxAge("max-age=60, no-store"))
        assertNull(parseMaxAge("public"))
        assertNull(parseMaxAge(null))
    }
}
