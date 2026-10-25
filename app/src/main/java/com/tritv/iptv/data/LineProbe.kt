package com.tritv.iptv.data

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks a line the way the player starts it: an HLS playlist is followed to a variant and
 * then to a media segment, so a line whose playlist answers but whose video does not still
 * counts as dead. Blocking; every connection is handed to [track] so a caller that gives up
 * can disconnect it.
 */
internal object LineProbe {

    /** What a probe found. */
    sealed interface Result {
        /** The line delivers media; [latencyMs] to the first byte of the first response. */
        data class Ok(val latencyMs: Int) : Result
        data object Dead : Result
        /** The line answers with an advert loop instead of the channel ([AdFilter]). */
        data object Ad : Result
    }

    fun run(entry: PlaylistEntry, timeoutMs: Int, track: (HttpURLConnection) -> Unit = {}): Result {
        val start = System.nanoTime()
        var latency: Int? = null
        var url = entry.url
        if (AdFilter.isAdUrl(url)) return Result.Ad
        // Set once [url] is a media segment, which only needs its first bytes.
        var segment = false
        repeat(MAX_STEPS) {
            val body = fetch(url, entry, timeoutMs, segment, track) ?: return Result.Dead
            if (body.ad) return Result.Ad
            if (latency == null) latency = ((System.nanoTime() - start) / 1_000_000).toInt()
            val ok = Result.Ok(latency!!)
            val text = body.playlist ?: return if (body.html) Result.Dead else ok
            val master = isMaster(text)
            if (!master && AdFilter.isLoopPlaylist(text)) return Result.Ad
            if (!master && AdFilter.isFrozen(text, System.currentTimeMillis())) return Result.Dead
            // An empty live playlist is still a working line; the next refresh will have segments.
            val next = nextUri(text) ?: return ok
            segment = !master
            url = URL(URL(body.finalUrl), next).toString()
            if (AdFilter.isAdUrl(url)) return Result.Ad
        }
        return latency?.let { Result.Ok(it) } ?: Result.Dead
    }

    /** Whether [playlist] lists variants rather than media segments. */
    fun isMaster(playlist: String): Boolean = playlist.contains("#EXT-X-STREAM-INF")

    /**
     * The URI the player would load next from an HLS playlist: the first variant of a master
     * playlist, or the newest segment of a media playlist. Null when it lists none.
     */
    fun nextUri(playlist: String): String? {
        val lines = playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val variant = lines.indexOfFirst { it.startsWith("#EXT-X-STREAM-INF") }
        if (variant >= 0) return lines.drop(variant + 1).firstOrNull { !it.startsWith("#") }
        return lines.lastOrNull { !it.startsWith("#") }
    }

    /** Whether [head], the start of a response, is an HLS playlist. */
    fun isPlaylist(head: String): Boolean = head.trimStart('﻿', ' ', '\t', '\r', '\n').startsWith("#EXTM3U")

    private class Body(val finalUrl: String, val playlist: String?, val html: Boolean, val ad: Boolean = false)

    private fun fetch(start: String, entry: PlaylistEntry, timeoutMs: Int, segment: Boolean, track: (HttpURLConnection) -> Unit): Body? {
        var url = URL(start)
        repeat(MAX_REDIRECTS) {
            val conn = url.openConnection() as HttpURLConnection
            track(conn)
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", entry.userAgent ?: USER_AGENT)
                entry.referrer?.let { conn.setRequestProperty("Referer", it) }
                // Only the first bytes are read; without a range the server pushes a TCP window's
                // worth of a multi-MB segment before the disconnect. Servers that ignore it send 200.
                if (segment) conn.setRequestProperty("Range", "bytes=0-${SEGMENT_BYTES - 1}")
                val code = conn.responseCode
                if (code in 300..399) {
                    url = URL(url, conn.getHeaderField("Location") ?: return null)
                    if (AdFilter.isAdUrl(url.toString())) return Body(url.toString(), null, html = false, ad = true)
                    return@repeat
                }
                if (code !in 200..299) return null
                return conn.inputStream.use { read(it, url.toString()) }
            } finally {
                conn.disconnect()
            }
        }
        return null
    }

    /** Reads just enough to tell media from a playlist or an error page; playlists are read whole. */
    private fun read(input: InputStream, url: String): Body? {
        val head = ByteArray(HEAD_BYTES)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
            // Media: one byte is proof enough, unless it could be the start of text.
            if (n > 0 && head[0] != '#'.code.toByte() && !head[0].isText()) return Body(url, null, html = false)
        }
        if (n == 0) return null
        val start = String(head, 0, n, Charsets.UTF_8)
        if (!isPlaylist(start)) return Body(url, null, html = start.trimStart().startsWith("<"))
        val rest = input.readUpTo(MAX_PLAYLIST_BYTES)
        return Body(url, start + String(rest, Charsets.UTF_8), html = false)
    }

    private fun Byte.isText() = this == '<'.code.toByte() || this == ' '.code.toByte() ||
        this == '\n'.code.toByte() || this == '\r'.code.toByte() || this == '\t'.code.toByte() || this == 0xEF.toByte()

    private fun InputStream.readUpTo(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < max) {
            val r = read(buf, 0, minOf(buf.size, max - out.size()))
            if (r < 0) break
            out.write(buf, 0, r)
        }
        return out.toByteArray()
    }

    /** Playlist, variant playlist, segment. */
    private const val MAX_STEPS = 3
    private const val MAX_REDIRECTS = 5
    private const val HEAD_BYTES = 16
    private const val SEGMENT_BYTES = 1024
    private const val MAX_PLAYLIST_BYTES = 256 * 1024
    private const val USER_AGENT = "tri.iptv/1.0 (Linux; Android) ExoPlayer"
}
