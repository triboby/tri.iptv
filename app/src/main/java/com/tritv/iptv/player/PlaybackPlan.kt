package com.tritv.iptv.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.tritv.iptv.data.LineStat
import com.tritv.iptv.data.PlaylistEntry

/** One way of trying to play a channel: a source line and the MIME type to force (null lets ExoPlayer sniff). */
internal data class Attempt(val sourceIndex: Int, val mimeType: String?)

/** Every attempt for a channel in order: each source's candidate formats, then the next source. */
internal fun playbackAttempts(sources: List<PlaylistEntry>): List<Attempt> =
    sources.flatMapIndexed { i, source -> mimeCandidates(source.url).map { Attempt(i, it) } }

private val progressiveExtensions = setOf("mp4", "flv", "ts", "mkv", "webm", "mov", "mp3", "aac")

/**
 * Picks formats to try from the URL. IPTV links often have no extension or point at
 * scripts (.php, .ctv) that redirect to an HLS playlist, so those try HLS first and
 * fall back to letting ExoPlayer detect the container.
 */
internal fun mimeCandidates(url: String): List<String?> {
    if (url.startsWith("rtsp://", ignoreCase = true)) return listOf(MimeTypes.APPLICATION_RTSP)
    val path = url.substringBefore('?').substringBefore('#').lowercase()
    val ext = path.substringAfterLast('/').substringAfterLast('.', "")
    return when (ext) {
        "m3u8", "m3u" -> listOf(MimeTypes.APPLICATION_M3U8)
        "mpd" -> listOf(MimeTypes.APPLICATION_MPD)
        in progressiveExtensions -> listOf(null)
        else -> listOf(MimeTypes.APPLICATION_M3U8, null)
    }
}

/**
 * The attempt to move to after [current] fails, or null to give up. [silentLine] is an
 * earlier attempt that showed video without decodable audio: once every other attempt
 * has failed, a silent picture beats an error screen.
 */
internal fun nextAttempt(current: Int, count: Int, silentLine: Int?): Int? = when {
    current + 1 < count -> current + 1
    silentLine != null && silentLine != current -> silentLine
    else -> null
}

/** How long a line may load before another line that probes have shown working takes over. */
internal const val SLOW_START_MS = 3_000L

/** A probe or playback success at most this old counts as the line working now. */
private const val FRESH_MS = 2 * 60_000L

/**
 * While attempt [current] (started at [startedAt]) is still loading, the attempt to jump to
 * because of what probes found since, or null to keep waiting: when its line has been found
 * dead, the fastest line known to work, else the next line not known dead; when it has loaded
 * for [SLOW_START_MS] without being known to work, the fastest line that is.
 */
internal fun probeSwitch(
    attempts: List<Attempt>,
    lineUrls: List<String>,
    stats: Map<String, LineStat>,
    current: Int,
    startedAt: Long,
    now: Long,
): Int? {
    val line = attempts[current].sourceIndex
    fun statOf(i: Int) = stats[lineUrls[attempts[i].sourceIndex]]
    fun working(s: LineStat?) = s != null && !s.failing && s.okAt > startedAt - FRESH_MS && !s.adRecently(now)
    val cur = statOf(current)
    val dead = cur != null && cur.failing && cur.failAt > startedAt
    if (!dead && (now - startedAt < SLOW_START_MS || working(cur))) return null
    val others = attempts.indices.filter { attempts[it].sourceIndex != line }
    others.filter { working(statOf(it)) }
        .minByOrNull { statOf(it)?.latencyMs ?: Int.MAX_VALUE }
        ?.let { return it }
    if (!dead) return null
    return others.firstOrNull { it > current && statOf(it)?.failing != true }
}

/**
 * The attempt to move to once attempt [current] has shown an advert: the first attempt of another
 * line, after it and then before it (the viewer may have stepped past them), preferring lines
 * neither dead nor seen with an advert lately, then ones that worked since their advert (the
 * player checks them as it loads), then any not last seen as an advert. Null when none is left.
 */
internal fun nextLineAfterAd(
    attempts: List<Attempt>,
    lineUrls: List<String>,
    stats: Map<String, LineStat>,
    current: Int,
    now: Long,
): Int? {
    val line = attempts[current].sourceIndex
    fun statOf(i: Int) = stats[lineUrls[attempts[i].sourceIndex]]
    fun firstOfLine(i: Int) = i == 0 || attempts[i - 1].sourceIndex != attempts[i].sourceIndex
    val candidates = ((current + 1 until attempts.size) + (0 until current))
        .filter { attempts[it].sourceIndex != line && firstOfLine(it) }
    fun ad(i: Int) = statOf(i)?.adRecently(now) == true
    fun failing(i: Int) = statOf(i)?.failing == true
    return candidates.firstOrNull { !ad(it) && !failing(it) }
        ?: candidates.firstOrNull { ad(it) && !failing(it) }
        ?: candidates.firstOrNull { !ad(it) }
}

/** A short Chinese reason for the error that ended the last line, for the player's info bar. */
internal fun playbackErrorReason(code: Int): String = when (code) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "连不上服务器或超时"
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "服务器拒绝或没有这个节目"
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "服务器上没有这个节目"
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "服务器不允许访问"
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "返回的不是能播放的视频"
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
    PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "电视解不了这个视频格式"
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "直播流中断"
    else -> "播放出错（$code）"
}
