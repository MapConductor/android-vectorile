package com.mapconductor.vectortile

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mapconductor.core.tileserver.TileRequest
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * A downloaded area on a Wi-Fi that answers nothing.
 *
 * The package holds every source the area's tiles read; everything around
 * it has to come from upstream, which hangs. Reported from a mall: online
 * with a package, the map stayed white, the downloaded area included.
 */
@RunWith(AndroidJUnit4::class)
class SlowNetworkTest {
    private lateinit var styleJson: String
    private lateinit var tileBytes: ByteArray
    private lateinit var directory: File
    private val release = CountDownLatch(1)
    private val background = Executors.newFixedThreadPool(64)

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val assets = instrumentation.context.assets
        styleJson = assets.open("demo-style.json").use { it.readBytes().decodeToString() }
        tileBytes = assets.open("tile-0-0-0.pbf").use { it.readBytes() }
        directory = File(instrumentation.targetContext.cacheDir, "slow-network-${System.nanoTime()}")
    }

    @After
    fun tearDown() {
        release.countDown()
        background.shutdownNow()
        directory.deleteRecursively()
    }

    /** A package holding exactly the sources tile [z]/[x]/[y] reads. */
    private fun packageFor(
        z: Int,
        x: Int,
        y: Int,
    ): OfflinePackage {
        val planner = VectorTileRenderer.create(styleJson, 512)
        val plan = JSONArray(planner.plan(z, x, y))
        planner.close()
        val index = HashMap<String, String>()
        for (i in 0 until plan.length()) {
            val url = plan.getJSONObject(i).getString("url")
            val relative = "tiles/source/$i.mvt"
            File(directory, relative).apply { parentFile?.mkdirs() }.writeBytes(tileBytes)
            index[url] = relative
        }
        File(directory, OfflinePackage.STYLE_FILE).writeText(styleJson)
        return OfflinePackage.create(
            directory,
            OfflinePackage.Manifest(
                bounds = OfflinePackage.Bounds(0.0, 0.0, 1.0, 1.0),
                minZoom = 0,
                maxZoom = z,
                styleDigest = "test",
                createdAt = 0,
                tiles = index.size,
                glyphs = 0,
                sprites = 0,
                bytes = 0,
                sources = emptyMap(),
                glyphsTemplate = null,
                spriteBase = null,
            ),
            index,
        )
    }

    /**
     * Sixty-four tiles around the area, each waiting on an upstream that
     * hangs for ten seconds, fill the fetch threads; then the area's own tile
     * is asked for. Measured on the emulator before the fix: 20.1 seconds,
     * two rounds of the thirty-two fetch threads timing out, for a tile whose
     * every source was on the device.
     */
    @Test
    fun theDownloadedAreaDrawsWhileUpstreamHangs() {
        val pkg = packageFor(6, 32, 22)
        val fetcher =
            OfflinePackage.Fetcher(
                pkg = pkg,
                upstream = {
                    release.await(10, TimeUnit.SECONDS)
                    null
                },
                online = true,
            )
        VectorTileProvider
            .create(
                styleJson = styleJson,
                tileSize = 512,
                renderScale = 1,
                renderMode = VectorTileProvider.RenderMode.CPU,
                fetchTile = fetcher,
                localTile = { url -> fetcher.local(url) },
            ).use { provider ->
                // The screen around the area: every one of these reads sources
                // the package does not have.
                for (x in 0 until 16) {
                    for (y in 0 until 4) {
                        background.execute { provider.geometryTiles.renderTile(TileRequest(x = x, y = y, z = 6)) }
                    }
                }
                Thread.sleep(500)
                val started = System.nanoTime()
                val png = provider.geometryTiles.renderTile(TileRequest(x = 32, y = 22, z = 6))
                val seconds = (System.nanoTime() - started) / 1e9
                Log.i(TAG, "in-area tile took ${"%.2f".format(seconds)}s bytes=${png?.size} stats=${fetcher.stats}")
                assertTrue("the in-area tile took ${seconds}s", seconds < 2.0)
                // Whole, not merely in time: drawn from the package, not drawn
                // short of it after the grace.
                assertTrue("drawn without its packaged sources", wholeTile(6, 32, 22).contentEquals(png))
            }
    }

    /** The tile as drawn with every source to hand. */
    private fun wholeTile(
        z: Int,
        x: Int,
        y: Int,
    ): ByteArray =
        VectorTileProvider
            .create(
                styleJson = styleJson,
                tileSize = 512,
                renderScale = 1,
                renderMode = VectorTileProvider.RenderMode.CPU,
                fetchTile = { tileBytes },
            ).use { it.geometryTiles.renderTile(TileRequest(x = x, y = y, z = z))!! }

    /**
     * A tile whose source is slow is drawn without it after the grace, is not
     * kept, and is asked for again -- through [VectorTileProvider.onSourcesLoaded]
     * -- once the source lands; the redraw is kept.
     */
    @Test
    fun aTileDrawnShortOfASlowSourceIsRedrawnWhenItLands() {
        val arrived = CountDownLatch(1)
        VectorTileProvider
            .create(
                styleJson = styleJson,
                tileSize = 512,
                renderScale = 1,
                renderMode = VectorTileProvider.RenderMode.CPU,
                diskCacheDir = directory,
                fetchTile = {
                    release.await(10, TimeUnit.SECONDS)
                    tileBytes
                },
            ).use { provider ->
                provider.onSourcesLoaded = { arrived.countDown() }
                val request = TileRequest(x = 0, y = 0, z = 0)
                val started = System.nanoTime()
                val short = provider.geometryTiles.renderTile(request)
                val seconds = (System.nanoTime() - started) / 1e9
                assertNotNull(short)
                assertTrue("waited ${seconds}s for a hung source", seconds < 2.0)
                assertTrue("a tile drawn short was kept", renderedTiles().isEmpty())

                release.countDown()
                assertTrue("no handover after the source landed", arrived.await(5, TimeUnit.SECONDS))
                val whole = provider.geometryTiles.renderTile(request)
                assertNotNull(whole)
                assertFalse("the redraw drew nothing more", short!!.contentEquals(whole!!))
                assertEquals(1, renderedTiles().size)
            }
    }

    /** Rendered tiles in the disk cache: PNGs at its top level. */
    private fun renderedTiles(): List<File> =
        directory.listFiles()?.filter { it.isFile && it.name.endsWith(".png") } ?: emptyList()

    private companion object {
        const val TAG = "SlowNetworkTest"
    }
}
