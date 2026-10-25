package com.tritv.iptv.data

/**
 * What is known about one stream URL (a channel's line): how fast it answered the last
 * probes and when it last worked or failed, by probe or by actual playback.
 */
data class LineStat(
    /** Time to the first byte of the response, smoothed over probes; null if never measured. */
    val latencyMs: Int? = null,
    val okAt: Long = 0L,
    val failAt: Long = 0L,
    /** When the line last turned out to be an advert loop ([AdFilter]); 0 if never. */
    val adAt: Long = 0L,
) {
    /** The latest result was a failure. */
    val failing: Boolean get() = failAt > okAt

    /**
     * Whether the line showed an advert within [AD_HOLD_MS] before [now]. The redirect servers
     * behind such lines send only some requests to the advert, so a line that worked on a later
     * probe is still ranked after lines that never showed one.
     */
    fun adRecently(now: Long): Boolean = adAt > 0L && now - adAt < AD_HOLD_MS

    /** When the line was last checked either way. */
    val checkedAt: Long get() = maxOf(okAt, failAt)

    fun succeeded(at: Long, latency: Int? = null) = copy(
        okAt = at,
        // Halfway towards the new reading, so one slow answer does not reorder the lines.
        latencyMs = when {
            latency == null -> latencyMs
            latencyMs == null -> latency
            else -> (latencyMs + latency) / 2
        },
    )

    fun failed(at: Long) = copy(failAt = at)

    fun showedAd(at: Long) = copy(failAt = at, adAt = at)

    companion object {
        /** How long a line that showed an advert stays at the back of its channel's lines. */
        const val AD_HOLD_MS = 6 * 60 * 60_000L
    }
}

object LineRanker {

    /**
     * Indices into [urls] in the order to try them: the line the viewer picked (unless it has
     * since failed), then working lines fastest first, then lines not checked yet, then lines
     * whose last check failed, those that worked most recently first. A line that showed an
     * advert lately ranks after the clean working lines once it has worked again (the player
     * checks it on every load), and last of all while its latest check was the advert; it stays
     * first only when picked and working since. Ties keep the playlist order.
     */
    fun order(urls: List<String>, stats: Map<String, LineStat>, picked: String?, now: Long = System.currentTimeMillis()): List<Int> {
        fun tier(i: Int): Int {
            val s = stats[urls[i]]
            return when {
                urls[i] == picked && s?.failing != true -> 0
                s == null || s.checkedAt == 0L -> 3
                s.failing && s.adRecently(now) -> 5
                s.failing -> 4
                s.adRecently(now) -> 2
                else -> 1
            }
        }
        return urls.indices.sortedWith(
            compareBy<Int>(
                { tier(it) },
                { stats[urls[it]]?.takeIf { s -> !s.failing }?.latencyMs ?: Int.MAX_VALUE },
                // Among failed lines; zero for the rest.
                { -(stats[urls[it]]?.takeIf { s -> s.failing }?.okAt ?: 0L) },
                { it },
            ),
        )
    }

    /** Whether [url] can be probed with a plain HTTP request. */
    fun probeable(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** One stat per line, "latency|okAt|failAt|adAt" with an empty latency when unknown. */
    fun encode(stat: LineStat): String = "${stat.latencyMs ?: ""}|${stat.okAt}|${stat.failAt}|${stat.adAt}"

    /** Also reads the older "latency|okAt|failAt" form. */
    fun decode(text: String?): LineStat? {
        val parts = text?.split('|') ?: return null
        if (parts.size !in 3..4) return null
        return LineStat(
            latencyMs = parts[0].toIntOrNull(),
            okAt = parts[1].toLongOrNull() ?: return null,
            failAt = parts[2].toLongOrNull() ?: return null,
            adAt = if (parts.size == 4) parts[3].toLongOrNull() ?: return null else 0L,
        )
    }
}
