package com.mapconductor.vectortile

import java.io.Closeable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * What the local tile server answers, online, for a file an offline package
 * does not hold -- the direct path, where the map reads the package itself.
 *
 * The server answers on a handful of worker threads (eight), and the map's
 * requests for the package's own files queue behind whatever those threads
 * are doing. A fallback that fetched upstream inline held a worker for as
 * long as the network took, and on a Wi-Fi that connects and then answers
 * nothing that is the full fifteen-second timeout: eight such requests and
 * the package's tiles waited too, the downloaded area white with the rest.
 * So:
 *
 * - a kept copy (the source cache [VectorTileProvider] also uses) is answered
 *   at once, and asked about in the background once stale;
 * - otherwise the fetch runs on threads of its own, and the worker waits for
 *   it for at most [waitMs]. Past that the map is told there is no tile now
 *   (the server marks the answer `no-store`, so it is asked again), and the
 *   fetch carries on into the cache for the next time it is asked.
 */
internal class PackageFallback(
    private val upstreamUrl: (relativePath: String) -> String?,
    private val fetch: ConditionalFetch,
    private val cache: SourceTileCache?,
    private val waitMs: Long = DEFAULT_WAIT_MS,
    threads: Int = DEFAULT_THREADS,
) : (String) -> ByteArray?,
    Closeable {
    private val revalidator = cache?.let { SourceRevalidator(it, fetch, onChanged = { _, _ -> }) }

    /** Upstream fetches under way, so a map asking twice waits on one. */
    private val inFlight = ConcurrentHashMap<String, CompletableFuture<ByteArray?>>()

    private val fetchers =
        ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            LinkedBlockingQueue(),
            { runnable -> Thread(runnable, "mc-package-fallback").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }

    override fun invoke(relativePath: String): ByteArray? {
        val url = upstreamUrl(relativePath) ?: return null
        cache?.get(url)?.let { kept ->
            revalidator?.revalidate(url, kept)
            return kept.bytes
        }
        val mine = CompletableFuture<ByteArray?>()
        val future = inFlight.putIfAbsent(url, mine) ?: mine
        if (future === mine) {
            try {
                fetchers.execute {
                    val bytes =
                        runCatching {
                            val response = fetch.fetch(url, null, null)
                            response.bytes?.also {
                                cache?.put(url, it, response.etag, response.lastModified, response.maxAgeSeconds)
                            }
                        }.getOrNull()
                    inFlight.remove(url, mine)
                    mine.complete(bytes)
                }
            } catch (_: RejectedExecutionException) {
                inFlight.remove(url, mine)
                return null
            }
        }
        return try {
            future.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    override fun close() {
        revalidator?.close()
        fetchers.shutdownNow()
    }

    companion object {
        /**
         * How long a server worker waits for an upstream fetch.
         *
         * Long enough for a tile on a working network, so a good connection
         * sees no difference; short enough that a bad one cannot hold the
         * package's own files back for more than a moment.
         */
        const val DEFAULT_WAIT_MS: Long = 2_500
        const val DEFAULT_THREADS = 6
    }
}
