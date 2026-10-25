package com.tritv.iptv.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.net.HttpURLConnection
import java.util.Collections

/**
 * Measures how fast each channel line answers, remembers it with the outcome of real
 * playback, and orders a channel's lines best first. Stats are kept per stream URL;
 * the line the viewer picked by hand is kept per [Channel.stableKey].
 */
class LineRepository(context: Context) {

    private val statPrefs = context.getSharedPreferences("line_stats", Context.MODE_PRIVATE)
    private val pickPrefs = context.getSharedPreferences("line_picks", Context.MODE_PRIVATE)
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _stats = MutableStateFlow(
        statPrefs.all.mapNotNull { (url, v) -> LineRanker.decode(v as? String)?.let { url to it } }.toMap(),
    )
    val stats: StateFlow<Map<String, LineStat>> = _stats.asStateFlow()

    private val inFlight: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val sweepLimit = Semaphore(SWEEP_CONCURRENCY)
    // Probes run here so one stuck on a socket can be abandoned without blocking its caller.
    private val probeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Lines whose stat changed since the last write. A sweep records ~200 results, and every
    // SharedPreferences write rewrites the whole file, so they are saved together a moment later.
    private val unsaved: MutableSet<String> = Collections.synchronizedSet(HashSet())
    private val saveLock = Any()
    private var saveJob: Job? = null

    /** [channel]'s lines in the order to try them. */
    fun ordered(channel: Channel): List<PlaylistEntry> {
        if (channel.sources.size < 2) return channel.sources
        val urls = channel.sources.map { it.url }
        val stats = _stats.value
        val order = LineRanker.order(urls, stats, pickPrefs.getString(channel.stableKey, null))
        Log.i(TAG, "order ${channel.name}: " + order.joinToString { i -> "${i + 1}=" + describe(stats[urls[i]]) })
        return order.map { channel.sources[it] }
    }

    private fun describe(stat: LineStat?): String = when {
        stat == null || stat.checkedAt == 0L -> "?"
        stat.adRecently(System.currentTimeMillis()) -> "ad"
        stat.failing -> "dead"
        else -> "${stat.latencyMs ?: "ok"}"
    }

    /** Remembers [entry] as the line the viewer chose for [channel]; it is tried first while it works. */
    fun pick(channel: Channel, entry: PlaylistEntry) {
        pickPrefs.edit().putString(channel.stableKey, entry.url).apply()
    }

    /** Records how real playback of [entry] went. */
    fun report(entry: PlaylistEntry, ok: Boolean) {
        Log.i(TAG, (if (ok) "played " else "play failed ") + entry.url)
        if (ok || online()) record(entry.url, ok, latency = null)
    }

    /**
     * Records that playing [entry] showed an advert instead of the channel. The line goes to the
     * back of its channel for a while, and stops being the viewer's pick.
     */
    fun reportAd(entry: PlaylistEntry) {
        Log.i(TAG, "play showed ad " + entry.url)
        recordAd(entry.url, dropPick = true)
    }

    /**
     * Only the player drops the viewer's pick: a probe's advert keeps the line from going first
     * until it works again, and the player checks it on every load anyway.
     */
    private fun recordAd(url: String, dropPick: Boolean) {
        val now = System.currentTimeMillis()
        _stats.update { all -> all + (url to (all[url] ?: LineStat()).showedAd(now)) }
        unsaved += url
        saveSoon()
        if (!dropPick) return
        val picks = pickPrefs.all.filterValues { it == url }.keys
        if (picks.isNotEmpty()) pickPrefs.edit().apply { picks.forEach { remove(it) } }.apply()
    }

    /** Forgets lines that are no longer in the playlist. */
    fun prune(channels: List<Channel>) {
        val keep = channels.flatMapTo(HashSet()) { c -> c.sources.map { it.url } }
        val gone = _stats.value.keys - keep
        if (gone.isEmpty()) return
        _stats.update { it - gone }
        unsaved += gone
        save()
    }

    /** Writes the stats changed since the last write. */
    fun save() {
        synchronized(saveLock) {
            saveJob?.cancel()
            saveJob = null
        }
        val urls = synchronized(unsaved) { unsaved.toList().also { unsaved.clear() } }
        if (urls.isEmpty()) return
        val stats = _stats.value
        val editor = statPrefs.edit()
        for (url in urls) {
            val stat = stats[url]
            if (stat == null) editor.remove(url) else editor.putString(url, LineRanker.encode(stat))
        }
        editor.apply()
    }

    private fun saveSoon() = synchronized(saveLock) {
        if (saveJob?.isActive == true) return
        saveJob = probeScope.launch {
            delay(SAVE_DELAY_MS)
            save()
        }
    }

    /** Probes [channel]'s lines checked longer than [maxAgeMs] ago, all at once. */
    suspend fun probeChannel(channel: Channel, maxAgeMs: Long) = coroutineScope {
        staleLines(channel, maxAgeMs).map { async { probeAndRecord(it) } }.awaitAll()
    }

    /**
     * Probes the stale lines of every channel that has more than one, [SWEEP_CONCURRENCY]
     * at a time, in the order given, so put the channels most likely to be watched first.
     */
    suspend fun sweep(channels: List<Channel>, maxAgeMs: Long) = coroutineScope {
        channels.filter { it.sources.size > 1 }
            .flatMap { staleLines(it, maxAgeMs) }
            .distinctBy { it.url }
            .map { async { sweepLimit.withPermit { probeAndRecord(it) } } }
            .awaitAll()
    }

    private fun staleLines(channel: Channel, maxAgeMs: Long): List<PlaylistEntry> {
        val now = System.currentTimeMillis()
        return channel.sources.filter {
            LineRanker.probeable(it.url) && now - (_stats.value[it.url]?.checkedAt ?: 0L) > maxAgeMs
        }
    }

    private suspend fun probeAndRecord(entry: PlaylistEntry) {
        if (!inFlight.add(entry.url)) return
        try {
            when (val result = probe(entry)) {
                is LineProbe.Result.Ok -> record(entry.url, ok = true, latency = result.latencyMs)
                LineProbe.Result.Ad -> recordAd(entry.url, dropPick = false)
                // With the TV itself offline (e.g. just after standby) every line fails; that says
                // nothing about the lines, so it must not push working ones to the back.
                LineProbe.Result.Dead -> if (online()) record(entry.url, ok = false, latency = null)
            }
        } finally {
            inFlight.remove(entry.url)
        }
    }

    private fun online(): Boolean {
        val cm = connectivity ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun record(url: String, ok: Boolean, latency: Int?) {
        val now = System.currentTimeMillis()
        var updated: LineStat? = null
        _stats.update { all ->
            val old = all[url] ?: LineStat()
            val next = if (ok) old.succeeded(now, latency) else old.failed(now)
            updated = next
            all + (url to next)
        }
        if (updated != null) {
            unsaved += url
            saveSoon()
        }
    }

    /**
     * Probes [entry] with [LineProbe], giving up after [PROBE_DEADLINE_MS] whatever the socket
     * timeouts do: a host that resolves to several dead addresses, or a slow DNS answer, can
     * otherwise hold a probe far longer than the player takes to give up on its own.
     */
    private suspend fun probe(entry: PlaylistEntry): LineProbe.Result {
        val conns = Collections.synchronizedList(mutableListOf<HttpURLConnection>())
        val job = probeScope.async {
            // The redirect servers send only some requests to the advert; like the player, take
            // the line for one only when every try hits it. Advert hits are a redirect or two, quick.
            var result: LineProbe.Result = LineProbe.Result.Dead
            for (i in 1..AdFilter.AD_RESOLVE_TRIES) {
                result = runCatching { LineProbe.run(entry, PROBE_TIMEOUT_MS) { conns += it } }.getOrDefault(LineProbe.Result.Dead)
                if (result != LineProbe.Result.Ad) break
            }
            result
        }
        val result = withTimeoutOrNull(PROBE_DEADLINE_MS) { job.await() } ?: LineProbe.Result.Dead
        if (job.isActive) {
            job.cancel()
            synchronized(conns) { conns.forEach { runCatching { it.disconnect() } } }
        }
        Log.i(TAG, when (result) {
            is LineProbe.Result.Ok -> "ok ${result.latencyMs}ms "
            LineProbe.Result.Ad -> "ad "
            LineProbe.Result.Dead -> "dead "
        } + entry.url)
        return result
    }

    companion object {
        /** Probe stats older than this are refreshed by the background sweep. */
        const val SWEEP_MAX_AGE_MS = 30 * 60_000L
        /** Lines of the channel being opened are re-probed when older than this. */
        const val PLAY_MAX_AGE_MS = 2 * 60_000L
        private const val SWEEP_CONCURRENCY = 3
        /** How long changed stats wait to be written together. */
        private const val SAVE_DELAY_MS = 5_000L
        /** Socket connect and read timeout for each request of a probe. */
        private const val PROBE_TIMEOUT_MS = 3_000
        /** Most a whole probe (playlist, variant, segment, redirects) may take. */
        private const val PROBE_DEADLINE_MS = 6_000L
        private const val TAG = "tri.iptv-Lines"
    }
}
