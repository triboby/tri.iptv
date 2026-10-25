package com.tritv.iptv.data

import java.net.HttpURLConnection
import java.net.URL

/**
 * Follows a line's redirects one hop at a time so every hop can be checked with [AdFilter]. The
 * advert servers give themselves away only in the middle of the chain: the stream it ends on
 * looks just like the channel's own.
 */
object RedirectChain {

    sealed interface Result {
        /** The address answers itself, without a redirect. */
        data object Direct : Result
        /** The redirects end at [url], which no hop marked as an advert. */
        data class Final(val url: String) : Result
        /** A hop went through an advert address. */
        data object Ad : Result
        /** The chain broke off (network error, bad status, too many hops). */
        data object Failed : Result
    }

    /** Blocking. Requests every hop, the last one included, but reads no body. */
    fun resolve(start: String, userAgent: String, referrer: String?, timeoutMs: Int): Result {
        if (AdFilter.isAdUrl(start)) return Result.Ad
        var url = URL(start)
        repeat(MAX_HOPS + 1) { hop ->
            val conn = runCatching { url.openConnection() as HttpURLConnection }.getOrNull() ?: return Result.Failed
            try {
                conn.connectTimeout = timeoutMs
                conn.readTimeout = timeoutMs
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", userAgent)
                referrer?.let { conn.setRequestProperty("Referer", it) }
                val code = runCatching { conn.responseCode }.getOrNull() ?: return Result.Failed
                if (code !in 300..399) {
                    return when {
                        code !in 200..299 -> Result.Failed
                        hop == 0 -> Result.Direct
                        else -> Result.Final(url.toString())
                    }
                }
                val location = conn.getHeaderField("Location") ?: return Result.Failed
                url = runCatching { URL(url, location) }.getOrNull() ?: return Result.Failed
                if (AdFilter.isAdUrl(url.toString())) return Result.Ad
            } finally {
                conn.disconnect()
            }
        }
        return Result.Failed
    }

    private const val MAX_HOPS = 5
}
