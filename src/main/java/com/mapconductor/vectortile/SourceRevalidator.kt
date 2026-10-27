package com.mapconductor.vectortile

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Asks the server about kept source tiles that have gone stale, in the
 * background.
 *
 * Never in the way: the stale tile has already been drawn by the time this
 * runs, two threads do the asking so a bad network can tie up nothing else,
 * and a queue that is full drops the question -- the tile is asked about
 * again the next time it is drawn. What comes back:
 *
 * - 304: the kept tile is current; its age starts again.
 * - 200 with the same bytes: the same, for a server that ignores validators.
 * - 200 with new bytes: kept, and [onChanged] is told, so what was drawn from
 *   the old ones can be drawn again.
 * - anything else, or a throw (offline, a failed connection): nothing; the
 *   kept tile stays as it is.
 */
internal class SourceRevalidator(
    private val cache: SourceTileCache,
    private val fetch: ConditionalFetch,
    private val onChanged: (url: String, bytes: ByteArray) -> Unit,
    threads: Int = DEFAULT_THREADS,
    queueSize: Int = DEFAULT_QUEUE,
) : Closeable {
    /** URLs asked about or waiting to be, so a viewport asks once per tile. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var closed = false

    private val executor =
        ThreadPoolExecutor(
            threads,
            threads,
            30,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(queueSize),
            { runnable -> Thread(runnable, "mc-revalidate").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }

    /** Asks about [entry] if it is stale and not already being asked about. */
    fun revalidate(
        url: String,
        entry: SourceTileCache.Entry,
    ) {
        if (closed || !entry.stale || !pending.add(url)) return
        try {
            executor.execute {
                try {
                    if (!closed) ask(url, entry)
                } finally {
                    pending.remove(url)
                }
            }
        } catch (_: RejectedExecutionException) {
            pending.remove(url)
        }
    }

    private fun ask(
        url: String,
        entry: SourceTileCache.Entry,
    ) {
        val response = runCatching { fetch.fetch(url, entry.etag, entry.lastModified) }.getOrNull() ?: return
        if (response.notModified) {
            cache.refresh(url, response.etag, response.lastModified, response.maxAgeSeconds)
            return
        }
        // "No such tile" now, for a tile that was there: left alone. A source
        // dropping a tile it served yesterday is rarer than a server having a
        // bad moment, and an empty map is the worse mistake.
        val bytes = response.bytes ?: return
        cache.put(url, bytes, response.etag, response.lastModified, response.maxAgeSeconds)
        if (bytes.contentEquals(entry.bytes)) return
        cache.bumpEpoch()
        if (!closed) onChanged(url, bytes)
    }

    override fun close() {
        closed = true
        executor.shutdownNow()
    }

    companion object {
        const val DEFAULT_THREADS = 2
        const val DEFAULT_QUEUE = 64
    }
}
