package com.tritv.iptv

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.tritv.iptv.data.Channel
import com.tritv.iptv.data.FavoriteList
import com.tritv.iptv.data.LineRepository
import com.tritv.iptv.data.LineStat
import com.tritv.iptv.data.Playlist
import com.tritv.iptv.data.PlaylistEntry
import com.tritv.iptv.data.PlaylistRepository
import com.tritv.iptv.data.guideErrorMessage
import com.tritv.iptv.data.loadErrorMessage
import com.tritv.iptv.epg.GuideData
import com.tritv.iptv.epg.GuideRepository
import com.tritv.iptv.epg.Programme
import com.tritv.iptv.epg.resolveGuideUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** State of the programme guide for the source page's status card. */
data class GuideStatus(
    val loading: Boolean = false,
    /** When the guide file in use was downloaded. */
    val downloadedAt: Long? = null,
    /** Channels with programmes, out of [total]. */
    val matched: Int = 0,
    val total: Int = 0,
    val error: String? = null,
    /** True when the address came from the playlist header rather than the viewer. */
    val fromPlaylist: Boolean = false,
    /** The address actually fetched, after moving a loopback host to the playlist's host. */
    val url: String? = null,
)

data class UiState(
    val playlistUrl: String = "",
    /** Guide address the viewer typed; empty to use the playlist's own. */
    val guideUrl: String = "",
    val channels: List<Channel> = emptyList(),
    /** Distinct group titles in playlist order. */
    val groups: List<String> = emptyList(),
    /** Group shown in the list, or null for all channels. Ignored while [favoritesSelected]. */
    val selectedGroup: String? = null,
    /** Whether the list shows the favourites instead of a group. */
    val favoritesSelected: Boolean = false,
    /** Favourite [Channel.stableKey]s in the viewer's order. */
    val favorites: List<String> = emptyList(),
    /** Favourite being moved with Up/Down, or null. */
    val movingFavorite: String? = null,
    val loading: Boolean = false,
    /** Set for a moment after an import from the source page succeeds, before going back to the list. */
    val imported: Boolean = false,
    val error: String? = null,
    /** Index into [channels] of the channel playing, full screen or behind the channel drawer. */
    val playingIndex: Int? = null,
    /** Whether the channel drawer is open over the playing channel; closed means full screen. */
    val listOpen: Boolean = true,
    /** The playing channel's lines in the order to try them, fixed when it started. */
    val playingLines: List<PlaylistEntry> = emptyList(),
    /** Index into [channels] the list should focus, e.g. after leaving the player. */
    val focusIndex: Int = 0,
    /** Whether the playlist source page is shown instead of the channel list. */
    val showSettings: Boolean = false,
    /** Whether the programme guide grid is shown. */
    val showGuide: Boolean = false,
    /** When the current channel list was last loaded, for the source page's status card. */
    val loadedAt: Long? = null,
    /** Guide programmes per [Channel.stableKey], sorted by start time. */
    val programmes: Map<String, List<Programme>> = emptyMap(),
    val guideStatus: GuideStatus? = null,
) {
    /** Indices into [channels] shown in the list; channel switching stays within them. */
    val visibleIndices: List<Int>
        get() = when {
            favoritesSelected -> favorites.mapNotNull { key ->
                channels.indexOfFirst { it.stableKey == key }.takeIf { it >= 0 }
            }
            selectedGroup == null -> channels.indices.toList()
            else -> channels.indices.filter { channels[it].group == selectedGroup }
        }

    /** Favourites whose channel is no longer in the playlist, kept so they come back when it returns. */
    val missingFavorites: List<String>
        get() = favorites.filter { key -> channels.none { it.stableKey == key } }

    fun isFavorite(channel: Channel) = channel.stableKey in favorites

    fun programmesOf(channel: Channel): List<Programme> = programmes[channel.stableKey].orEmpty()
}

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = PlaylistRepository(app)
    private val guideRepo = GuideRepository(app)
    private val lines = LineRepository(app)

    /** Probe results and playback outcomes per stream URL. */
    val lineStats: StateFlow<Map<String, LineStat>> = lines.stats

    /** Guide address from the last loaded playlist's header. */
    private var playlistGuideUrl: String? = null
    private var guideJob: Job? = null
    private var sweepJob: Job? = null

    /** Favourites as they were when moving started, restored if the move is cancelled. */
    private var favoritesBeforeMove: List<String>? = null

    /** Counts [loadPlaylist] calls, so a refresh can tell it has been overtaken. */
    private var loadSeq = 0

    /** Whether the app is on screen; background work waits while it is not. */
    private val foreground = MutableStateFlow(false)

    /** Set once a line has started playing, after which start-up is over for background work. */
    private val playedOnce = MutableStateFlow(false)

    private val _state = MutableStateFlow(
        UiState(
            playlistUrl = repo.savedUrl.orEmpty(),
            guideUrl = repo.guideUrl.orEmpty(),
            favorites = repo.favorites,
            showSettings = repo.savedUrl.isNullOrBlank(),
            // The skeleton, not "no channels", until the cached or downloaded list is in.
            loading = !repo.savedUrl.isNullOrBlank(),
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val url = _state.value.playlistUrl.trim()
        if (url.isNotBlank()) {
            viewModelScope.launch {
                // The last copy plays at once; the fresh one replaces it quietly if it differs.
                val cached = withContext(Dispatchers.IO) { repo.cached(url) }?.takeIf { it.channels.isNotEmpty() }
                if (cached != null) {
                    playlistGuideUrl = cached.guideUrl
                    showPlaylist(cached.channels, guideChanged = false, loadedAt = cached.cachedAt ?: System.currentTimeMillis())
                }
                loadPlaylist(url, quiet = cached != null)
            }
        }
    }

    /** Called as the app comes on screen (true) and leaves it (false), e.g. with Home or standby. */
    fun onForeground(visible: Boolean) {
        foreground.value = visible
        if (!visible) lines.save()
    }

    override fun onCleared() {
        lines.save()
    }

    /**
     * Returns once the first channel has started playing, or after [STARTUP_GRACE_MS], so the
     * guide and the line sweep do not take CPU and network from it while it connects.
     */
    private suspend fun startupSettled() {
        withTimeoutOrNull(STARTUP_GRACE_MS) { playedOnce.first { it } }
    }

    fun onUrlChange(url: String) = _state.update { it.copy(playlistUrl = url) }

    fun onGuideUrlChange(url: String) = _state.update { it.copy(guideUrl = url) }

    /**
     * Downloads the playlist and shows it. [quiet] is the refresh behind a cached copy already on
     * screen: no loading state, failures are ignored, and the list is only touched if it changed.
     */
    fun loadPlaylist(url: String = _state.value.playlistUrl, quiet: Boolean = false) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        val guideChanged = _state.value.guideUrl.trim() != repo.guideUrl.orEmpty()
        repo.guideUrl = _state.value.guideUrl.trim().ifEmpty { null }
        if (!quiet) _state.update { it.copy(loading = true, imported = false, error = null) }
        val seq = ++loadSeq
        viewModelScope.launch {
            runCatching { repo.load(trimmed) }
                .onSuccess { playlist ->
                    // A quiet refresh that finishes after the viewer imported something else is stale.
                    if (quiet && seq != loadSeq) return@onSuccess
                    repo.savedUrl = trimmed
                    playlistGuideUrl = playlist.guideUrl
                    if (quiet) refreshPlaylist(playlist) else showPlaylist(playlist.channels, guideChanged, System.currentTimeMillis())
                }
                .onFailure { e ->
                    if (quiet) return@onFailure
                    _state.update {
                        it.copy(
                            loading = false,
                            error = loadErrorMessage(e),
                            // Nothing to browse, so show the source page where the address can be fixed.
                            showSettings = it.showSettings || it.channels.isEmpty(),
                        )
                    }
                }
        }
    }

    /** Shows [channels] afresh: all channels, the last one watched playing behind the open drawer. */
    private fun showPlaylist(channels: List<Channel>, guideChanged: Boolean, loadedAt: Long) {
        val last = channels.indexOfFirst { it.stableKey == repo.lastChannelKey }.coerceAtLeast(0)
        // The last channel plays behind the drawer from the start.
        val playing = last.takeIf { channels.isNotEmpty() }
        val fromSourcePage = _state.value.showSettings && channels.isNotEmpty()
        _state.update {
            it.copy(
                loading = false,
                channels = channels,
                groups = channels.mapNotNull { c -> c.group }.distinct(),
                selectedGroup = null,
                favoritesSelected = false,
                movingFavorite = null,
                focusIndex = last,
                playingIndex = playing,
                playingLines = playing?.let { i -> lines.ordered(channels[i]) }.orEmpty(),
                listOpen = true,
                error = if (channels.isEmpty()) "播放列表中没有频道" else null,
                loadedAt = loadedAt,
                // Back to the list once there is something to show, after "已导入" has been seen.
                showSettings = it.showSettings,
                imported = fromSourcePage,
            )
        }
        if (fromSourcePage) {
            viewModelScope.launch {
                delay(IMPORTED_NOTE_MS)
                _state.update { if (it.imported) it.copy(showSettings = false, imported = false) else it }
            }
        }
        refreshGuide(force = guideChanged)
        lines.prune(channels)
        startLineSweep()
    }

    /**
     * Swaps in a freshly downloaded [playlist] behind the cached one on screen, keeping the channel
     * playing and the one focused where they still exist. Nothing happens when it is the same.
     */
    private fun refreshPlaylist(playlist: Playlist) {
        val s = _state.value
        val channels = playlist.channels
        if (channels.isEmpty() || channels == s.channels) {
            if (playlist.cachedAt == null) _state.update { it.copy(loadedAt = System.currentTimeMillis()) }
            return
        }
        fun indexOf(key: String?) = key?.let { k -> channels.indexOfFirst { it.stableKey == k }.takeIf { it >= 0 } }
        val oldPlaying = s.playingIndex?.let { s.channels.getOrNull(it) }
        val playing = indexOf(oldPlaying?.stableKey) ?: 0
        val focus = indexOf(s.channels.getOrNull(s.focusIndex)?.stableKey) ?: playing
        _state.update {
            it.copy(
                channels = channels,
                groups = channels.mapNotNull { c -> c.group }.distinct(),
                selectedGroup = it.selectedGroup?.takeIf { g -> channels.any { c -> c.group == g } },
                movingFavorite = null,
                focusIndex = focus,
                playingIndex = playing,
                // The same channel keeps its lines, so the player does not restart.
                playingLines = if (channels[playing] == oldPlaying) it.playingLines else lines.ordered(channels[playing]),
                loadedAt = playlist.cachedAt ?: System.currentTimeMillis(),
            )
        }
        refreshGuide()
        lines.prune(channels)
        startLineSweep()
    }

    /**
     * Loads the guide for the current channels (from cache when it is recent enough), then
     * keeps it fresh: it is fetched again every [GuideRepository.MAX_AGE_MS] while the app runs.
     */
    fun refreshGuide(force: Boolean = false) {
        val channels = _state.value.channels
        val own = repo.guideUrl
        val url = (own ?: playlistGuideUrl)?.let { resolveGuideUrl(it, repo.savedUrl.orEmpty()) }
        guideJob?.cancel()
        if (url == null || channels.isEmpty()) {
            _state.update {
                it.copy(
                    programmes = emptyMap(),
                    guideStatus = if (channels.isEmpty()) null else GuideStatus(error = "播放列表没有提供节目单地址，可以在上面填写"),
                )
            }
            return
        }
        _state.update {
            it.copy(guideStatus = (it.guideStatus ?: GuideStatus()).copy(loading = true, error = null, fromPlaylist = own == null, url = url))
        }
        guideJob = viewModelScope.launch {
            fun show(data: GuideData) = _state.update {
                it.copy(
                    programmes = data.programmes,
                    guideStatus = GuideStatus(
                        downloadedAt = data.downloadedAt,
                        matched = channels.count { c -> c.stableKey in data.programmes },
                        total = channels.size,
                        fromPlaylist = own == null,
                        url = url,
                    ),
                )
            }
            var forceNext = force
            // When what is shown should be fetched again; null until the first load.
            var dueAt: Long? = null
            // What the last start parsed shows at once; only fetching and parsing anew waits.
            if (!force) {
                guideRepo.cached(url, channels)?.let { data ->
                    show(data)
                    dueAt = data.downloadedAt + GuideRepository.MAX_AGE_MS
                }
            }
            startupSettled()
            // Waits while the app is off screen; a refresh missed then runs on return.
            foreground.collectLatest { visible ->
                if (!visible) return@collectLatest
                while (true) {
                    dueAt?.let { delay(it - System.currentTimeMillis()) }
                    val attemptAt = System.currentTimeMillis()
                    val result = runCatching { guideRepo.load(url, channels, forceNext) }
                    forceNext = false
                    result
                        .onSuccess { data -> show(data) }
                        .onFailure { e ->
                            _state.update {
                                it.copy(guideStatus = GuideStatus(error = guideErrorMessage(e), fromPlaylist = own == null, url = url))
                            }
                        }
                    // Due when the file turns MAX_AGE old; after a failed download (an older copy, or
                    // nothing), MAX_AGE from this attempt.
                    val fileDue = result.getOrNull()?.downloadedAt?.plus(GuideRepository.MAX_AGE_MS)
                    dueAt = fileDue?.takeIf { it > attemptAt } ?: (attemptAt + GuideRepository.MAX_AGE_MS)
                }
            }
        }
    }

    /**
     * Probes the lines of every channel that has backups in the background, and again every
     * [LineRepository.SWEEP_MAX_AGE_MS], so opening a channel tries its fastest working line
     * first. The last channel and the favourites go first.
     */
    private fun startLineSweep() {
        sweepJob?.cancel()
        sweepJob = viewModelScope.launch {
            startupSettled()
            // Stops while the app is off screen. Back on screen it starts over, which only probes
            // the lines whose numbers have gone stale meanwhile.
            foreground.collectLatest { visible ->
                if (!visible) return@collectLatest
                while (true) {
                    val s = _state.value
                    val first = (listOfNotNull(repo.lastChannelKey) + s.favorites).toSet()
                    val (likely, rest) = s.channels.partition { it.stableKey in first }
                    lines.sweep(likely + rest, LineRepository.SWEEP_MAX_AGE_MS)
                    lines.save()
                    delay(LineRepository.SWEEP_MAX_AGE_MS)
                }
            }
        }
    }

    /** Records how playing [entry] of the playing channel went. */
    fun reportLine(entry: PlaylistEntry, ok: Boolean) {
        if (ok) playedOnce.value = true
        lines.report(entry, ok)
    }

    /** Records that [entry] of the playing channel showed an advert instead of the channel. */
    fun reportAd(entry: PlaylistEntry) = lines.reportAd(entry)

    /** Remembers [entry] as the line the viewer chose for the playing channel. */
    fun pickLine(entry: PlaylistEntry) {
        val s = _state.value
        val channel = s.playingIndex?.let { s.channels.getOrNull(it) } ?: return
        lines.pick(channel, entry)
    }

    // Opening the guide or the source page puts focus back on the playing channel, so coming back
    // to the drawer lands on it instead of on a row from earlier, which would then start playing.
    fun openSettings() = _state.update {
        it.copy(showSettings = true, showGuide = false, error = null, focusIndex = it.playingIndex ?: it.focusIndex)
    }

    /** Leaves the source page; stays on it while there are no channels to go back to. */
    fun closeSettings(): Boolean {
        if (_state.value.channels.isEmpty()) return false
        _state.update {
            it.copy(
                showSettings = false,
                imported = false,
                playlistUrl = repo.savedUrl ?: it.playlistUrl,
                guideUrl = repo.guideUrl.orEmpty(),
                error = null,
            )
        }
        return true
    }

    fun openGuide() = _state.update { it.copy(showGuide = true, focusIndex = it.playingIndex ?: it.focusIndex) }

    fun closeGuide() = _state.update { it.copy(showGuide = false) }

    fun selectGroup(group: String?) = _state.update { s ->
        if (!s.favoritesSelected && s.selectedGroup == group) return@update s
        val next = s.copy(selectedGroup = group, favoritesSelected = false, movingFavorite = null)
        next.copy(focusIndex = next.visibleIndices.firstOrNull() ?: 0)
    }

    fun selectFavorites() = _state.update { s ->
        if (s.favoritesSelected) return@update s
        val next = s.copy(favoritesSelected = true, movingFavorite = null)
        next.copy(focusIndex = next.visibleIndices.firstOrNull() ?: 0)
    }

    fun toggleFavorite(key: String) {
        val list = FavoriteList.toggle(_state.value.favorites, key)
        repo.favorites = list
        _state.update { it.copy(favorites = list) }
    }

    fun startMovingFavorite(key: String) {
        if (key !in _state.value.favorites) return
        favoritesBeforeMove = _state.value.favorites
        _state.update { it.copy(movingFavorite = key) }
    }

    /** Moves the favourite being moved one place up (-1) or down (+1). */
    fun moveFavorite(delta: Int) {
        val key = _state.value.movingFavorite ?: return
        _state.update { it.copy(favorites = FavoriteList.move(it.favorites, key, delta)) }
    }

    /** Ends moving: keeps the new order, or with [cancel] puts the old one back. */
    fun finishMovingFavorite(cancel: Boolean = false) {
        val before = favoritesBeforeMove
        favoritesBeforeMove = null
        _state.update { it.copy(movingFavorite = null, favorites = if (cancel && before != null) before else it.favorites) }
        repo.favorites = _state.value.favorites
    }

    /** Plays channel [index] full screen; the one already playing behind the drawer just goes full screen. */
    fun play(index: Int) {
        if (index == _state.value.playingIndex) {
            _state.update { it.copy(listOpen = false, focusIndex = index, showGuide = false) }
            return
        }
        start(index, listOpen = false)
    }

    /** Switches the channel playing behind the open drawer, keeping the drawer open. */
    fun preview(index: Int) {
        if (index == _state.value.playingIndex) return
        start(index, listOpen = true)
    }

    private fun start(index: Int, listOpen: Boolean) {
        val channels = _state.value.channels
        if (index !in channels.indices) return
        val channel = channels[index]
        repo.lastChannelKey = channel.stableKey
        _state.update {
            it.copy(
                playingIndex = index,
                playingLines = lines.ordered(channel),
                focusIndex = if (listOpen) it.focusIndex else index,
                listOpen = listOpen,
                showGuide = false,
            )
        }
        // Fresh numbers let the player skip a line that turns out dead before its own timeout.
        if (channel.sources.size > 1) {
            viewModelScope.launch { lines.probeChannel(channel, LineRepository.PLAY_MAX_AGE_MS) }
        }
    }

    /** Moves to the next (+1) or previous (-1) channel in the current group, wrapping around. */
    fun zap(delta: Int) {
        zapTarget(delta)?.let(::play)
    }

    /** Index of the channel [delta] steps from the one playing within the current list, wrapping around. */
    fun zapTarget(delta: Int): Int? {
        val s = _state.value
        val current = s.playingIndex ?: return null
        val visible = s.visibleIndices
        if (visible.isEmpty()) return null
        val pos = visible.indexOf(current).takeIf { it >= 0 } ?: 0
        return visible[Math.floorMod(pos + delta, visible.size)]
    }

    /**
     * Plays the channel with list number [number] (1-based, as shown), if there is one. Numbers
     * count over all channels, so when the channel is outside the current list the list becomes
     * all channels and channel up/down carries on from there.
     */
    fun playNumber(number: Int) {
        val index = number - 1
        if (index !in _state.value.channels.indices) return
        _state.update { s ->
            if (index in s.visibleIndices) s
            else s.copy(selectedGroup = null, favoritesSelected = false, movingFavorite = null)
        }
        play(index)
    }

    /** Opens the channel drawer over the playing channel, with focus on it. */
    fun openList() = _state.update { it.copy(listOpen = true, focusIndex = it.playingIndex ?: it.focusIndex) }

    /** Closes the drawer so the playing channel is full screen; stays open when nothing plays. */
    fun closeList() = _state.update { if (it.playingIndex != null) it.copy(listOpen = false) else it }
}

/** How long "已导入" stays on the source page before the list comes back. */
private const val IMPORTED_NOTE_MS = 800L

/** Most that background work waits at start-up for the first channel to start playing. */
private const val STARTUP_GRACE_MS = 5_000L
