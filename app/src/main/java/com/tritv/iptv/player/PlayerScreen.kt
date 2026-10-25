package com.tritv.iptv.player

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsManifest
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Text
import com.tritv.iptv.data.AdFilter
import com.tritv.iptv.data.Channel
import com.tritv.iptv.data.LineStat
import com.tritv.iptv.data.PlaylistEntry
import com.tritv.iptv.data.RedirectChain
import com.tritv.iptv.epg.Programme
import com.tritv.iptv.epg.nowAndNext
import com.tritv.iptv.ui.ChannelBadge
import com.tritv.iptv.ui.ClockText
import com.tritv.iptv.ui.Dot
import com.tritv.iptv.ui.DrawerWidth
import com.tritv.iptv.ui.EnterEasing
import com.tritv.iptv.ui.ExitEasing
import com.tritv.iptv.ui.GroupCaption
import com.tritv.iptv.ui.KeyCap
import com.tritv.iptv.ui.ProgressBar
import com.tritv.iptv.ui.Radius
import com.tritv.iptv.ui.ScreenMargin
import com.tritv.iptv.ui.Spinner
import com.tritv.iptv.ui.StarIcon
import com.tritv.iptv.ui.TriTvColors
import com.tritv.iptv.ui.TriTvType
import com.tritv.iptv.ui.digitOf
import com.tritv.iptv.ui.rememberNow
import com.tritv.iptv.ui.NumberEntry
import com.tritv.iptv.ui.tabular
import com.tritv.iptv.ui.timeRange
import kotlinx.coroutines.delay
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale

private const val DEFAULT_USER_AGENT = "tri.iptv/1.0 (Linux; Android) ExoPlayer"

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    channel: Channel,
    /** [channel]'s lines in the order to try them. */
    lines: List<PlaylistEntry>,
    lineStats: Map<String, LineStat>,
    /** Called when a line starts playing (true) or fails (false). */
    onLineResult: (PlaylistEntry, Boolean) -> Unit,
    /** Called when a line the viewer switched to starts playing. */
    onLinePicked: (PlaylistEntry) -> Unit,
    /** Called when a line turns out to play an advert loop instead of the channel. */
    onLineAd: (PlaylistEntry) -> Unit,
    channelNumber: Int,
    programmes: List<Programme>,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onOpenGuide: () -> Unit,
    /** Index of the channel that many steps away in the current list, for the zap preview. */
    zapTarget: (Int) -> Int?,
    /** The channel with this list number (1-based), or null if there is none. */
    channelAt: (Int) -> Channel?,
    /** Guide programmes of a channel, for what the zap or number card lands on. */
    programmesOf: (Channel) -> List<Programme>,
    onZap: (Int) -> Unit,
    /** Digits typed for a channel number, shared with the drawer. */
    numberEntry: NumberEntry,
    onExit: () -> Unit,
    /**
     * Whether the channel drawer is open over the picture: the drawer then has the keys, and a card in
     * the bottom-right corner stands in for the info bar.
     */
    behindDrawer: Boolean = false,
) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context, TvRenderersFactory(context))
            .setLoadControl(liveLoadControl())
            .build()
            .apply { playWhenReady = true }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }

    // Leaving the app with Home keeps this screen alive in the background, so stop the stream
    // (sound and download) while the app is hidden and reconnect the same line on return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> player.stop()
                // Back to the live edge: the old position may have left the live window, which
                // would fail as an error and wrongly move on to the next line.
                Lifecycle.Event.ON_START -> if (player.playbackState == Player.STATE_IDLE && player.mediaItemCount > 0) {
                    player.seekToDefaultPosition()
                    player.prepare()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val attempts = remember(channel, lines) { playbackAttempts(lines) }
    var attemptIndex by remember(channel) { mutableIntStateOf(0) }
    var error by remember(channel) { mutableStateOf<String?>(null) }
    // First attempt that showed video without decodable audio, kept as a last resort.
    var silentLine by remember(channel) { mutableStateOf<Int?>(null) }
    // Set when the viewer picked the line, or every other line failed: stay on it even if silent.
    var stayOnLine by remember(channel) { mutableStateOf(false) }
    var noAudio by remember(channel) { mutableStateOf(false) }
    // Whether the current attempt has played yet; reset when it changes.
    var ready by remember(channel) { mutableStateOf(false) }
    // Set when the viewer switched lines, so the line is remembered once it plays.
    var pickedByViewer by remember(channel) { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    // Whether the current attempt has put a picture on screen. Some lines show their first frame
    // well before the player reports ready; the picture is answer enough that the line is up.
    var firstFrame by remember(channel) { mutableStateOf(false) }
    // Whether any line of this channel has shown a picture yet. After that, switching lines keeps
    // the last frame on screen (dimmed) instead of going black.
    var playedOnce by remember(channel) { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(true) }
    // Brief "已加入收藏 / 已取消收藏" note after the viewer toggles the favourite.
    var favoriteNote by remember { mutableStateOf<String?>(null) }
    // Brief note that a line playing an advert or a frozen picture was left for another.
    var lineNote by remember { mutableStateOf<String?>(null) }
    // Watches the current attempt's HLS playlists for an advert; replaced with each attempt.
    val adCheck = remember(channel) { arrayOf(AdCheck()) }
    // Set once a held OK has toggled the favourite, so its release does not also toggle the info bar.
    var okHeld by remember { mutableStateOf(false) }
    // Channel steps pressed in quick succession, applied together once the presses pause, so a
    // burst of Up/Down does not restart the player for every channel on the way.
    var pendingZap by remember { mutableIntStateOf(0) }
    // After Up/Down or a typed number the new channel shows in the corner card, not the info bar.
    var cardAfterJump by remember { mutableStateOf(false) }
    val jumpSeen = remember { intArrayOf(numberEntry.jumps, 0) } // number jumps seen; 1 while a zap is in flight
    val attempt = attempts[attemptIndex]
    val attemptStartedAt = remember(channel, attemptIndex) { System.currentTimeMillis() }

    val lineUrls = remember(lines) { lines.map { it.url } }
    // Read by the listener when it looks for the next line, so it sees the latest probes.
    val latestStats by rememberUpdatedState(lineStats)

    // Keyed on channel so the listener sees this channel's attempt state.
    DisposableEffect(player, channel, lines) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrame = true
            }

            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) {
                    error = null
                    if (!ready) {
                        ready = true
                        playedOnce = true
                        val line = lines[attempts[attemptIndex].sourceIndex]
                        onLineResult(line, true)
                        if (pickedByViewer) onLinePicked(line)
                    }
                }
            }

            override fun onPlayerError(e: PlaybackException) {
                if (generateSequence<Throwable>(e) { it.cause }.any { it is AdRedirectException }) {
                    leaveLine(adNote = true)
                    return
                }
                onLineResult(lines[attempts[attemptIndex].sourceIndex], false)
                val next = nextAttempt(attemptIndex, attempts.size, silentLine)
                if (next == null) {
                    error = "所有线路都无法播放：" + playbackErrorReason(e.errorCode)
                } else {
                    stayOnLine = next == silentLine
                    attemptIndex = next
                }
            }

            // Each load and live refresh of an HLS playlist lands here, so the advert check costs
            // no requests of its own.
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                val manifest = player.currentManifest as? HlsManifest ?: return
                val check = adCheck[0]
                if (check.tripped) return
                when (check.verdict(manifest)) {
                    AdCheck.Verdict.OK -> {}
                    AdCheck.Verdict.AD -> leaveLine(adNote = true)
                    AdCheck.Verdict.FROZEN -> leaveLine(adNote = false)
                }
            }

            /**
             * Leaves the current line for the next usable one because it plays an advert
             * ([adNote]) or a frozen recording. A line the viewer picked is left too: neither is
             * what they wanted.
             */
            fun leaveLine(adNote: Boolean) {
                adCheck[0].tripped = true
                val line = attempts[attemptIndex].sourceIndex
                if (adNote) onLineAd(lines[line]) else onLineResult(lines[line], false)
                if (silentLine?.let { attempts[it].sourceIndex } == line) silentLine = null
                stayOnLine = false
                pickedByViewer = false
                val next = nextLineAfterAd(attempts, lineUrls, latestStats, attemptIndex, System.currentTimeMillis())
                if (next == null) {
                    player.stop()
                    error = if (adNote) "这个台的线路都在播广告" else "这个台的线路都放不出直播"
                } else {
                    lineNote = "线路 ${line + 1} " + (if (adNote) "在播广告" else "不是直播画面") + "，已换线路"
                    attemptIndex = next
                }
            }

            // A line whose audio no renderer can decode plays silently without raising an
            // error, so treat it as a failure here and try the next line.
            override fun onTracksChanged(tracks: Tracks) {
                noAudio = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
                if (!noAudio || stayOnLine || attemptIndex + 1 >= attempts.size) return
                if (silentLine == null) silentLine = attemptIndex
                attemptIndex++
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Probes of this channel's lines start with it and answer well before the player gives up
    // on a dead line, so act on them: leave a line found dead, or one still loading after a
    // few seconds when another line is known to work.
    val lineStat = lineStats[lineUrls[attempt.sourceIndex]]
    val channelStats = lineUrls.map { lineStats[it] }
    LaunchedEffect(attemptIndex, channelStats) {
        fun check(): Boolean {
            if (ready || stayOnLine) return true
            val next = probeSwitch(attempts, lineUrls, lineStats, attemptIndex, attemptStartedAt, System.currentTimeMillis())
                ?: return false
            attemptIndex = next
            return true
        }
        if (check()) return@LaunchedEffect
        val wait = attemptStartedAt + SLOW_START_MS - System.currentTimeMillis()
        if (wait > 0) {
            delay(wait)
            check()
        }
    }

    LaunchedEffect(channel, attempt) {
        ready = false
        firstFrame = false
        adCheck[0] = AdCheck()
        val source = lines[attempt.sourceIndex]
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(source.userAgent ?: DEFAULT_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(6_000)
            .setReadTimeoutMs(10_000)
        source.referrer?.let { httpFactory.setDefaultRequestProperties(mapOf("Referer" to it)) }

        val item = MediaItem.Builder()
            .setUri(source.url)
            .apply { attempt.mimeType?.let { setMimeType(it) } }
            .build()
        // Until a line has played, give up on it after one retry so the next line gets its turn;
        // once playing, ride out short network hiccups instead of switching lines.
        val retries = object : DefaultLoadErrorHandlingPolicy() {
            override fun getMinimumLoadableRetryCount(dataType: Int) = if (ready) 3 else 1
            // The resolver has already tried the chain several times.
            override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo) =
                if (info.exception is AdRedirectException) C.TIME_UNSET else super.getRetryDelayMsFor(info)
        }
        val dataSource = ResolvingDataSource.Factory(httpFactory, AdRedirectResolver(source, source.userAgent ?: DEFAULT_USER_AGENT))
        player.setMediaSource(
            DefaultMediaSourceFactory(dataSource).setLoadErrorHandlingPolicy(retries).createMediaSource(item),
        )
        player.prepare()
    }

    // The loading ring stays up from the start of buffering until the line plays, whatever the info
    // bar does. It waits 0.4 s before appearing so a line that starts quickly does not flash it.
    // Before the line is ready the ring goes as soon as a picture shows; after that it marks rebuffering.
    val loading = error == null && (if (ready) buffering else !firstFrame)
    var showLoader by remember { mutableStateOf(false) }
    var loadingSince by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(loading, channel, attemptIndex) {
        if (!loading) {
            showLoader = false
            return@LaunchedEffect
        }
        loadingSince = System.currentTimeMillis()
        if (!showLoader) {
            delay(LOADER_DELAY_MS)
            showLoader = true
        }
    }
    val dim by animateFloatAsState(
        targetValue = if (playedOnce && !ready && !firstFrame && error == null) SWITCH_DIM else 0f,
        animationSpec = tween(200),
        label = "dim",
    )

    LaunchedEffect(channel, attempt.sourceIndex, behindDrawer) {
        val jumped = !behindDrawer && (jumpSeen[1] == 1 || numberEntry.jumps != jumpSeen[0])
        jumpSeen[0] = numberEntry.jumps
        jumpSeen[1] = 0
        if (jumped) {
            showInfo = false
            cardAfterJump = true
            delay(4_000)
            cardAfterJump = false
        } else {
            cardAfterJump = false
            showInfo = true
            delay(4_000)
            showInfo = false
        }
    }

    LaunchedEffect(lineNote) {
        if (lineNote == null) return@LaunchedEffect
        delay(3_000)
        lineNote = null
    }

    LaunchedEffect(favoriteNote) {
        if (favoriteNote == null) return@LaunchedEffect
        delay(2_000)
        favoriteNote = null
    }

    LaunchedEffect(pendingZap) {
        if (pendingZap == 0) return@LaunchedEffect
        delay(350)
        val delta = pendingZap
        pendingZap = 0
        jumpSeen[1] = 1
        onZap(delta)
    }

    fun toggleFavorite() {
        favoriteNote = if (isFavorite) "已取消收藏" else "已加入收藏"
        onToggleFavorite()
    }

    /** Jumps to the first attempt of the previous (-1) or next (+1) source line. */
    fun switchSource(delta: Int) {
        val count = lines.size
        if (count < 2) return
        val target = Math.floorMod(attempt.sourceIndex + delta, count)
        error = null
        stayOnLine = true
        pickedByViewer = true
        attemptIndex = attempts.indexOfFirst { it.sourceIndex == target }
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(behindDrawer) { if (!behindDrawer) runCatching { focusRequester.requestFocus() } }

    // Back drops a half-typed number first, then goes back to the drawer.
    BackHandler(enabled = !behindDrawer) { if (numberEntry.active) numberEntry.clear() else onExit() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .onKeyEvent { event ->
                // Digits type a channel number; consume both halves so the TV does not switch input.
                digitOf(event.key)?.let { digit ->
                    if (event.type == KeyEventType.KeyDown) numberEntry.type(digit)
                    return@onKeyEvent true
                }
                if ((event.key == Key.DirectionCenter || event.key == Key.Enter) && numberEntry.active) {
                    if (event.type == KeyEventType.KeyUp) numberEntry.commit()
                    return@onKeyEvent true
                }
                // OK toggles the info bar on release; held down, it toggles the favourite instead.
                if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                    if (event.type == KeyEventType.KeyDown) {
                        if (event.nativeKeyEvent.repeatCount > 0 && !okHeld) {
                            okHeld = true
                            toggleFavorite()
                        }
                    } else if (event.type == KeyEventType.KeyUp) {
                        if (!okHeld) showInfo = !showInfo
                        okHeld = false
                    }
                    return@onKeyEvent true
                }
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionUp, Key.ChannelUp -> { pendingZap--; true }
                    Key.DirectionDown, Key.ChannelDown -> { pendingZap++; true }
                    Key.DirectionLeft -> { switchSource(-1); true }
                    Key.DirectionRight -> { switchSource(1); true }
                    Key.Info -> { showInfo = !showInfo; true }
                    // ≡ is on every BRAVIA remote; few have a Guide key.
                    Key.Menu, Key.Guide -> { onOpenGuide(); true }
                    else -> false
                }
            }
            .focusable(enabled = !behindDrawer),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    useController = false
                    isFocusable = false
                    // Keys are handled in Compose; nothing inside the view may take focus from the drawer.
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    setKeepContentOnPlayerReset(false)
                    // Live TV gets no remote input for long stretches; without this the TV's
                    // screensaver starts mid-programme. Dropped when the player screen leaves.
                    keepScreenOn = true
                    this.player = player
                }
            },
            // A new channel starts on black; another line of the same channel keeps the last frame.
            update = { it.setKeepContentOnPlayerReset(playedOnce) },
            modifier = Modifier.fillMaxSize(),
        )

        if (dim > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = dim }
                    .background(Color.Black),
            )
        }

        AnimatedVisibility(
            visible = showLoader && loading,
            enter = fadeIn(tween(240, easing = EnterEasing)) + scaleIn(tween(240, easing = EnterEasing), initialScale = 0.96f),
            exit = fadeOut(tween(200, easing = ExitEasing)) + scaleOut(tween(200, easing = ExitEasing), targetScale = 0.98f),
            modifier = Modifier
                .align(Alignment.Center)
                .padding(start = if (behindDrawer) DrawerWidth else 0.dp),
        ) {
            // Moved off the first line, by the viewer or because a line failed.
            val switching = pickedByViewer || attemptIndex > 0
            val lineLabel = if (lines.size > 1) "线路 ${attempt.sourceIndex + 1}/${lines.size}" else null
            BufferingIndicator(
                caption = when {
                    ready -> "缓冲中"
                    switching && lineLabel != null -> "正在换到$lineLabel"
                    else -> "正在连接"
                },
                detail = listOfNotNull(channel.name, lineLabel.takeIf { !switching }).joinToString(" · "),
                since = loadingSince,
            )
        }

        // Where a burst of Up/Down or the typed digits will land, then the channel it landed on,
        // in the drawer's corner card. An error goes to the info bar instead.
        val jumpNumber = when {
            numberEntry.active -> numberEntry.digits
            behindDrawer -> null
            pendingZap != 0 -> zapTarget(pendingZap)?.let { "%03d".format(it + 1) }
            cardAfterJump && error == null && !showInfo -> "%03d".format(channelNumber)
            else -> null
        }
        if (jumpNumber != null) {
            val target = if (numberEntry.active || pendingZap != 0) channelAt(jumpNumber.toInt()) else channel
            JumpCard(
                number = jumpNumber,
                typing = numberEntry.active,
                channel = target,
                programmes = target?.let(programmesOf).orEmpty(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = ScreenMargin, bottom = 28.dp),
            )
        }

        AnimatedVisibility(
            visible = favoriteNote != null || lineNote != null,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(200)),
            // Centred: stations put their logo in a top corner.
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .padding(top = 28.dp)
                    .height(36.dp)
                    .background(TriTvColors.Ink.copy(alpha = 0.8f), Radius.Pill)
                    .padding(start = 12.dp, end = 16.dp),
            ) {
                if (favoriteNote != null) {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) { StarIcon(filled = isFavorite) }
                }
                Text(favoriteNote ?: lineNote.orEmpty(), style = TriTvType.Caption)
            }
        }

        AnimatedVisibility(
            visible = behindDrawer || showInfo || error != null,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = 28.dp, end = ScreenMargin)
                    .height(36.dp)
                    .background(TriTvColors.Ink.copy(alpha = 0.8f), Radius.Pill)
                    .padding(horizontal = 16.dp),
            ) {
                ClockText(TriTvType.Caption.copy(fontWeight = FontWeight.SemiBold))
            }
        }

        if (behindDrawer && jumpNumber == null) {
            DrawerCard(
                channel = channel,
                channelNumber = channelNumber,
                status = error ?: if (lines.size > 1) "线路 ${attempt.sourceIndex + 1}/${lines.size}" else null,
                isError = error != null,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = ScreenMargin, bottom = 28.dp),
            )
        }

        // Full-width frosted bar along the bottom edge.
        AnimatedVisibility(
            // Makes way for the jump card, which sits over its right end.
            visible = !behindDrawer && jumpNumber == null && (showInfo || error != null),
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 },
            exit = fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            val now = rememberNow()
            val (current, next) = programmes.nowAndNext(now)
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TriTvColors.Ink.copy(alpha = 0.85f))
                    .padding(start = ScreenMargin, end = ScreenMargin, top = 18.dp, bottom = 14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    // The guide makes way for an error, which then gets the full width.
                    val showGuide = (current != null || next != null) && error == null
                    // Number and logo (or name) exactly as in the corner card.
                    ChannelBadge("%03d".format(channelNumber), channel)
                    if (showGuide) NowAndNext(current, next, now, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                }
                // Status on the left (favourite, group, line, problems), key hints on the right.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        if (isFavorite) StarIcon(14.dp)
                        val err = error
                        if (err != null) {
                            Text(err, style = TriTvType.Caption, color = TriTvColors.Error, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        } else {
                            channel.group?.let { GroupCaption(it, TriTvType.Caption, TriTvColors.TextMuted) }
                            if (lines.size > 1) {
                                if (channel.group != null) Dot()
                                Text(
                                    "线路 ${attempt.sourceIndex + 1}/${lines.size}",
                                    style = TriTvType.Caption,
                                    color = TriTvColors.TextMuted,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                // How fast this line answered its last probes.
                                lineStat?.takeIf { !it.failing }?.latencyMs?.let { ms ->
                                    Dot()
                                    Text("$ms ms", style = TriTvType.Caption.tabular(), color = TriTvColors.TextMuted, maxLines = 1, softWrap = false)
                                }
                            }
                            // Buffering is shown by the ring in the middle of the picture, not here.
                            if (noAudio && !buffering) {
                                Dot()
                                Text(
                                    "无声音：音频格式不支持",
                                    style = TriTvType.Caption,
                                    color = TriTvColors.Error,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                            }
                        }
                    }
                    KeyHint("▲▼", "换台")
                    KeyHint("◀▶", "换线路")
                    KeyHint("OK", "信息")
                    KeyHint("长按 OK", if (isFavorite) "取消收藏" else "收藏")
                    if (programmes.isNotEmpty()) KeyHint("≡", "节目表")
                    KeyHint("返回", "列表")
                }
            }
        }
    }
}

/**
 * The playing channel while the drawer is open, in the bottom-right corner: number and logo (or
 * name until one loads) on the first line, then the line and the keys that leave the drawer; an
 * error takes its own line above the keys.
 * A fixed 320dp wide, so it does not jump about as the channel changes.
 */
@Composable
private fun DrawerCard(channel: Channel, channelNumber: Int, status: String?, isError: Boolean, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .width(320.dp)
            .background(TriTvColors.Ink.copy(alpha = 0.8f), Radius.Card)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ChannelBadge("%03d".format(channelNumber), channel)
        // An error is too long to share a line with the keys, so it gets up to two lines of its own.
        if (isError && status != null) {
            Text(status, style = TriTvType.Caption, color = TriTvColors.Error, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (!isError && status != null) {
                Text(status, style = TriTvType.Caption.tabular(), color = TriTvColors.TextMuted, maxLines = 1, softWrap = false)
            }
            KeyHint("OK", "全屏")
            KeyHint("返回", "收起")
        }
    }
}

/**
 * Where Up/Down or a typed number is about to go, in place of the drawer's card: number and logo,
 * then what that channel is showing, so the viewer knows before it switches. While a number is
 * typed the keys to switch now or give up follow.
 */
@Composable
private fun JumpCard(number: String, typing: Boolean, channel: Channel?, programmes: List<Programme>, modifier: Modifier = Modifier) {
    val now = rememberNow()
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier
            .width(320.dp)
            .background(TriTvColors.Ink.copy(alpha = 0.8f), Radius.Card)
            .padding(horizontal = 20.dp, vertical = 16.dp),
    ) {
        ChannelBadge(number, channel)
        programmes.nowAndNext(now).first?.let { current ->
            Text(
                "正在播 · ${current.title}",
                style = TriTvType.Caption,
                color = TriTvColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (typing) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                KeyHint("OK", "立即换台")
                KeyHint("返回", "取消")
            }
        }
    }
}


/**
 * Ring with a caption under it, centred on the picture. From 5 s on the second line says how long
 * it has been waiting, so a slow line does not look frozen.
 */
@Composable
private fun BufferingIndicator(caption: String, detail: String, since: Long) {
    var waited by remember(since) { mutableIntStateOf(0) }
    LaunchedEffect(since) {
        while (true) {
            delay(1_000)
            waited = ((System.currentTimeMillis() - since) / 1_000).toInt()
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spinner()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(caption, style = TriTvType.Body)
            Text(
                if (waited >= 5) "仍在连接 · 已等待 $waited 秒" else detail,
                style = TriTvType.Caption.tabular(),
                color = TriTvColors.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Buffers sized for live TV on a 1–2 GB box. The default keeps up to 50 s, bounded at ~128 MB for
 * video, in the Java heap; a live stream rarely has more than ~30 s behind its edge anyway. Play
 * starts after 1.5 s of media instead of 2.5 s.
 */
@OptIn(UnstableApi::class)
private fun liveLoadControl() = DefaultLoadControl.Builder()
    .setBufferDurationsMs(
        /* minBufferMs = */ 15_000,
        /* maxBufferMs = */ 30_000,
        /* bufferForPlaybackMs = */ 1_500,
        /* bufferForPlaybackAfterRebufferMs = */ 3_000,
    )
    .setTargetBufferBytes(32 * 1024 * 1024)
    .setPrioritizeTimeOverSizeThresholds(false)
    .build()

/** Buffering shorter than this shows no loading ring. */
private const val LOADER_DELAY_MS = 400L

/** How dark the last frame goes while another line of the same channel loads. */
private const val SWITCH_DIM = 0.65f

/** "正在播" with title, time and progress, and "下一个" with its start time. */
@Composable
private fun NowAndNext(current: Programme?, next: Programme?, now: Long, modifier: Modifier = Modifier) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        if (current != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("正在播", style = TriTvType.LabelStrong, color = TriTvColors.SkyBlue)
                Text(current.title, style = TriTvType.Label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                val left = ((current.stop - now) / 60_000).coerceAtLeast(0)
                Text(
                    timeRange(current) + " · 剩 " + "%d:%02d".format(left / 60, left % 60),
                    style = TriTvType.Caption.tabular(),
                    color = TriTvColors.TextMuted,
                )
            }
            ProgressBar(current.progress(now), Modifier.fillMaxWidth())
        }
        if (next != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("下一个", style = TriTvType.LabelStrong, color = TriTvColors.TextMuted)
                Text(hm(next.start), style = TriTvType.Caption.tabular(), color = TriTvColors.TextMuted)
                Text(next.title, style = TriTvType.Caption, color = TriTvColors.TextMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun hm(t: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(t)

@Composable
private fun KeyHint(key: String, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        KeyCap(key)
        Text(label, style = TriTvType.Small, color = TriTvColors.TextMuted)
    }
}

/** The advert checks of [AdFilter] applied to one attempt's HLS playlists as the player loads them. */
private class AdCheck {
    private val loop = AdFilter.LoopWatch()
    /** Set once the attempt has been found to be an advert, so it is left only once. */
    var tripped = false

    enum class Verdict { OK, AD, FROZEN }

    @OptIn(UnstableApi::class)
    fun verdict(manifest: HlsManifest): Verdict {
        // After redirects, so the address the stream really came from.
        if (AdFilter.isAdUrl(manifest.multivariantPlaylist.baseUri)) return Verdict.AD
        val media = manifest.mediaPlaylist
        if (AdFilter.isAdUrl(media.baseUri)) return Verdict.AD
        val uris = media.segments.map { it.url }
        if (uris.any(AdFilter::isAdUrl)) return Verdict.AD
        if (media.hasEndTag && uris.isNotEmpty() && media.durationUs < AdFilter.MAX_CLIP_SECONDS * 1_000_000L) return Verdict.AD
        if (loop.check(media.mediaSequence, uris)) return Verdict.AD
        val lag = System.currentTimeMillis() - media.startTimeUs / 1_000
        if (media.hasProgramDateTime && lag > AdFilter.MAX_LAG_MS) return Verdict.FROZEN
        return Verdict.OK
    }
}

/** Thrown when every try at a line's redirect chain passed through an advert address. */
private class AdRedirectException(url: String) : IOException("advert redirect: $url")

/**
 * Walks the redirects of [source]'s address before each load of it (the first playlist and
 * every live refresh, which go through the redirect server again) and hands the player the
 * address they end at, so a hop through an advert server is seen: the player's own redirect
 * handling shows only where the chain ends, which looks like the channel. A hop to an advert is
 * tried again, as the server sends only some requests there; when every try hits one the load
 * fails with [AdRedirectException]. A line that answers without a redirect is left alone after
 * the first load, so only redirected lines pay for the extra request.
 */
@OptIn(UnstableApi::class)
private class AdRedirectResolver(private val source: PlaylistEntry, private val userAgent: String) : ResolvingDataSource.Resolver {
    @Volatile private var redirects = true

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (!redirects || dataSpec.uri.toString() != source.url) return dataSpec
        repeat(AdFilter.AD_RESOLVE_TRIES) {
            when (val r = RedirectChain.resolve(source.url, userAgent, source.referrer, RESOLVE_TIMEOUT_MS)) {
                RedirectChain.Result.Direct -> {
                    redirects = false
                    return dataSpec
                }
                is RedirectChain.Result.Final -> return dataSpec.withUri(android.net.Uri.parse(r.url))
                // Let the player load it and report its own error.
                RedirectChain.Result.Failed -> return dataSpec
                RedirectChain.Result.Ad -> {}
            }
        }
        throw AdRedirectException(source.url)
    }

    private companion object {
        const val RESOLVE_TIMEOUT_MS = 6_000
    }
}
