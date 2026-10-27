package com.mapconductor.vectortile

/**
 * What a source request came back with, and what it said about keeping it.
 *
 * The validators are what let a tile kept on disk be asked about later
 * instead of downloaded again: a server that recognises them answers "not
 * modified" with no body, which is the whole saving.
 */
class SourceResponse(
    /** The tile, or null for "there is no such tile". Unused when [notModified]. */
    val bytes: ByteArray?,
    /** The server answered 304: what is kept is still current. */
    val notModified: Boolean = false,
    val etag: String? = null,
    val lastModified: String? = null,
    /** `Cache-Control: max-age`, when the server gave one; 0 for `no-cache`. */
    val maxAgeSeconds: Long? = null,
)

/**
 * A source fetch that can ask "has this changed?".
 *
 * [etag] and [lastModified] are what the kept copy was served with, or null
 * when there is no kept copy (or it came without them). Throw for "could not
 * ask" -- no network, a failed connection, a fetch the app does not allow
 * right now -- which leaves the kept copy as it is.
 */
fun interface ConditionalFetch {
    fun fetch(
        url: String,
        etag: String?,
        lastModified: String?,
    ): SourceResponse
}

/**
 * The built-in [ConditionalFetch]: plain HTTP with [headers], validators sent
 * when there are any.
 *
 * A 404 or 204 is "no such tile", not a failure; 5xx and transport errors
 * throw, so they are never mistaken for an empty tile.
 */
internal fun httpSourceFetch(headers: Map<String, String>): ConditionalFetch =
    ConditionalFetch { url, etag, lastModified ->
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        var reuse = false
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            headers.forEach(connection::setRequestProperty)
            etag?.let { connection.setRequestProperty("If-None-Match", it) }
            lastModified?.let { connection.setRequestProperty("If-Modified-Since", it) }
            val status = connection.responseCode
            val maxAge = parseMaxAge(connection.getHeaderField("Cache-Control"))
            val responseEtag = connection.getHeaderField("ETag")
            val responseLastModified = connection.getHeaderField("Last-Modified")
            when (status) {
                java.net.HttpURLConnection.HTTP_NOT_MODIFIED -> {
                    reuse = true
                    SourceResponse(null, notModified = true, responseEtag, responseLastModified, maxAge)
                }
                java.net.HttpURLConnection.HTTP_NOT_FOUND, java.net.HttpURLConnection.HTTP_NO_CONTENT -> {
                    connection.errorStream?.use { it.readBytes() }
                    reuse = true
                    SourceResponse(null)
                }
                in 200..299 -> {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    // Draining the body is what lets the socket go back to the pool.
                    reuse = true
                    SourceResponse(bytes.takeIf { it.isNotEmpty() }, false, responseEtag, responseLastModified, maxAge)
                }
                else -> {
                    connection.errorStream?.use { it.readBytes() }
                    reuse = true
                    throw java.io.IOException("tile fetch failed: $status $url")
                }
            }
        } finally {
            // See `VectorTileProvider.httpGet`: disconnecting would cost a
            // fresh TLS handshake per tile.
            if (!reuse) connection.disconnect()
        }
    }

/** `max-age` in seconds; `no-cache` and `no-store` count as 0. Null when the header says neither. */
internal fun parseMaxAge(cacheControl: String?): Long? {
    if (cacheControl == null) return null
    var maxAge: Long? = null
    for (directive in cacheControl.split(',')) {
        val part = directive.trim().lowercase()
        when {
            part == "no-cache" || part == "no-store" -> return 0
            part.startsWith("max-age=") -> maxAge = part.removePrefix("max-age=").trim('"').toLongOrNull()
        }
    }
    return maxAge
}
