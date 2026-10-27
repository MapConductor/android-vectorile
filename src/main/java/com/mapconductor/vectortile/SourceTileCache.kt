package com.mapconductor.vectortile

import java.io.File
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

/**
 * Source tiles kept on disk, with what is needed to ask about them later.
 *
 * Kept and preferred: a tile here is drawn at once, whatever the network is
 * doing. That is the point -- on a Wi-Fi that connects and then answers
 * nothing, a cached tile drawn now beats a fresh one in fifteen seconds. Its
 * age is tracked beside it, and a tile past [maxAge][Entry.stale] is still
 * used but reported stale, so the caller can ask the server in the background
 * with the validators kept here (`If-None-Match`, `If-Modified-Since`).
 *
 * The bytes live where [StyleAssetCache] kept them (the same file names), so
 * the tiles an earlier build stored are still found; they come back without
 * metadata, which reads as stale with no validators -- asked about once,
 * plainly, then kept properly.
 *
 * No `android.util` here, so the policy can be checked on the JVM. Failures
 * are swallowed: a cache that cannot read or write is a cache miss.
 */
internal class SourceTileCache(
    private val directory: File,
    private val budgetBytes: Long,
    private val defaultMaxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** A kept tile, and whether it is due to be asked about. */
    class Entry(
        val bytes: ByteArray,
        val etag: String?,
        val lastModified: String?,
        val stale: Boolean,
    )

    private val writesSinceSweep = AtomicLong(0)

    private fun digest(url: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private fun bytesFile(url: String) = File(directory, "${digest(url)}$BYTES_SUFFIX")

    private fun metaFile(url: String) = File(directory, "${digest(url)}$META_SUFFIX")

    fun get(url: String): Entry? =
        try {
            val file = bytesFile(url)
            if (!file.isFile) {
                null
            } else {
                val bytes = file.readBytes()
                if (bytes.isEmpty()) {
                    null
                } else {
                    // Touched for the sweep, which is least-recently-used.
                    file.setLastModified(clock())
                    val meta = readMeta(url)
                    val fetchedAt = meta?.getProperty(KEY_FETCHED_AT)?.toLongOrNull()
                    val maxAgeMs =
                        meta?.getProperty(KEY_MAX_AGE)?.toLongOrNull()?.let { it * 1000 } ?: defaultMaxAgeMs
                    Entry(
                        bytes = bytes,
                        etag = meta?.getProperty(KEY_ETAG),
                        lastModified = meta?.getProperty(KEY_LAST_MODIFIED),
                        stale = fetchedAt == null || clock() - fetchedAt >= maxAgeMs,
                    )
                }
            }
        } catch (_: Exception) {
            null
        }

    /** Keeps [bytes] as fetched just now, with what the server said about them. */
    fun put(
        url: String,
        bytes: ByteArray,
        etag: String? = null,
        lastModified: String? = null,
        maxAgeSeconds: Long? = null,
    ) {
        try {
            if (!directory.isDirectory && !directory.mkdirs()) return
            // Write-then-rename, so a reader never sees half a tile.
            val temp = File.createTempFile("source", ".tmp", directory)
            temp.writeBytes(bytes)
            if (!temp.renameTo(bytesFile(url))) {
                temp.delete()
                return
            }
            writeMeta(url, etag, lastModified, maxAgeSeconds)
            if (writesSinceSweep.incrementAndGet() % SWEEP_EVERY == 0L) sweep()
        } catch (_: Exception) {
        }
    }

    /**
     * The server said "not modified": the kept bytes are current from now.
     * Validators it sent replace the kept ones; those it left out stay.
     */
    fun refresh(
        url: String,
        etag: String?,
        lastModified: String?,
        maxAgeSeconds: Long?,
    ) {
        try {
            if (!bytesFile(url).isFile) return
            val previous = readMeta(url)
            writeMeta(
                url,
                etag ?: previous?.getProperty(KEY_ETAG),
                lastModified ?: previous?.getProperty(KEY_LAST_MODIFIED),
                maxAgeSeconds ?: previous?.getProperty(KEY_MAX_AGE)?.toLongOrNull(),
            )
        } catch (_: Exception) {
        }
    }

    /**
     * Bumped whenever a kept tile turned out to have changed.
     *
     * Rendered tiles are cached by what they show, not by what they were
     * drawn from, so a source that changed underneath them would go on being
     * served drawn from its old self. Coarse on purpose -- one change retires
     * every rendered tile -- because changes are rare (a tile is asked about
     * at most once a [defaultMaxAgeMs]) and finding the rendered tiles one
     * source fed is not possible from a URL. Kept on disk so that a restart
     * does not bring the retired tiles back.
     */
    val epoch: Long
        get() =
            try {
                val file = File(directory, EPOCH_FILE)
                if (file.isFile) file.readText().trim().toLongOrNull() ?: 0 else 0
            } catch (_: Exception) {
                0
            }

    @Synchronized
    fun bumpEpoch(): Long {
        val next = epoch + 1
        try {
            if (directory.isDirectory || directory.mkdirs()) File(directory, EPOCH_FILE).writeText("$next")
        } catch (_: Exception) {
        }
        return next
    }

    private fun readMeta(url: String): Properties? {
        val file = metaFile(url)
        if (!file.isFile) return null
        return Properties().apply { file.inputStream().use { load(it) } }
    }

    private fun writeMeta(
        url: String,
        etag: String?,
        lastModified: String?,
        maxAgeSeconds: Long?,
    ) {
        val meta = Properties()
        meta.setProperty(KEY_FETCHED_AT, "${clock()}")
        etag?.let { meta.setProperty(KEY_ETAG, it) }
        lastModified?.let { meta.setProperty(KEY_LAST_MODIFIED, it) }
        maxAgeSeconds?.let { meta.setProperty(KEY_MAX_AGE, "$it") }
        val temp = File.createTempFile("meta", ".tmp", directory)
        temp.outputStream().use { meta.store(it, null) }
        if (!temp.renameTo(metaFile(url))) temp.delete()
    }

    /** Drops the least recently used tiles, with their metadata, until the budget is met. */
    private fun sweep() {
        try {
            val tiles = directory.listFiles()?.filter { it.isFile && it.name.endsWith(BYTES_SUFFIX) } ?: return
            var total = tiles.sumOf { it.length() }
            if (total <= budgetBytes) return
            for (file in tiles.sortedBy { it.lastModified() }) {
                if (total <= budgetBytes) break
                val length = file.length()
                if (file.delete()) {
                    total -= length
                    File(directory, file.name.removeSuffix(BYTES_SUFFIX) + META_SUFFIX).delete()
                }
            }
        } catch (_: Exception) {
        }
    }

    companion object {
        /**
         * How long a tile is current when its server does not say.
         *
         * A day: basemap tiles are rebuilt on the order of days to weeks, and
         * a tile is never withheld for being older than this -- it is drawn
         * and asked about, so the cost of a day is one conditional request.
         */
        const val DEFAULT_MAX_AGE_MS: Long = 24L * 60 * 60 * 1000
        private const val BYTES_SUFFIX = ".pbf"
        private const val META_SUFFIX = ".meta"
        private const val EPOCH_FILE = "epoch"
        private const val SWEEP_EVERY = 16L
        private const val KEY_FETCHED_AT = "fetchedAt"
        private const val KEY_ETAG = "etag"
        private const val KEY_LAST_MODIFIED = "lastModified"
        private const val KEY_MAX_AGE = "maxAge"
    }
}
