package com.tritv.iptv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.tritv.iptv.player.PlayerScreen
import com.tritv.iptv.ui.ChannelListScreen
import com.tritv.iptv.ui.rememberNumberEntry
import com.tritv.iptv.ui.GuideScreen
import com.tritv.iptv.ui.SettingsScreen
import com.tritv.iptv.ui.TriTvTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Background work (line sweep, guide refresh) pauses while the app is off screen.
        lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> viewModel.onForeground(true)
                    Lifecycle.Event.ON_STOP -> viewModel.onForeground(false)
                    else -> {}
                }
            },
        )
        setContent {
            TriTvTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                if (state.showGuide && state.channels.isNotEmpty()) {
                    GuideScreen(state = state, onPlay = viewModel::play, onBack = viewModel::closeGuide)
                } else if (state.showSettings) {
                    BackHandler(enabled = state.channels.isNotEmpty()) { viewModel.closeSettings() }
                    SettingsScreen(
                        state = state,
                        onUrlChange = viewModel::onUrlChange,
                        onGuideUrlChange = viewModel::onGuideUrlChange,
                        onLoad = { viewModel.loadPlaylist() },
                        onCancel = { viewModel.closeSettings() },
                        onRefreshGuide = { viewModel.refreshGuide(force = true) },
                    )
                } else {
                    // One player for the drawer and full screen, so OK on the playing channel goes
                    // full screen without loading it again.
                    Box(Modifier.fillMaxSize()) {
                        // Shared: digits typed in the drawer show in the player's corner card.
                        val numberEntry = rememberNumberEntry(viewModel::playNumber)
                        val playing = state.playingIndex
                        if (playing != null && playing in state.channels.indices) {
                            val channel = state.channels[playing]
                            val lines = state.playingLines.ifEmpty { channel.sources }
                            // Only this channel's lines: a sweep records ~200 results, and each would
                            // otherwise recompose the whole player.
                            val lineStatsFlow = remember(lines) {
                                val urls = lines.map { it.url }
                                viewModel.lineStats.map { all -> urls.mapNotNull { u -> all[u]?.let { u to it } }.toMap() }
                                    .distinctUntilChanged()
                            }
                            val lineStats by lineStatsFlow.collectAsStateWithLifecycle(
                                initialValue = remember(lines) { lines.mapNotNull { l -> viewModel.lineStats.value[l.url]?.let { l.url to it } }.toMap() },
                            )
                            PlayerScreen(
                                channel = channel,
                                lines = lines,
                                lineStats = lineStats,
                                onLineResult = viewModel::reportLine,
                                onLinePicked = viewModel::pickLine,
                                onLineAd = viewModel::reportAd,
                                channelNumber = playing + 1,
                                programmes = state.programmesOf(channel),
                                isFavorite = state.isFavorite(channel),
                                onToggleFavorite = { viewModel.toggleFavorite(channel.stableKey) },
                                onOpenGuide = viewModel::openGuide,
                                zapTarget = viewModel::zapTarget,
                                channelAt = { n -> state.channels.getOrNull(n - 1) },
                                programmesOf = state::programmesOf,
                                onZap = viewModel::zap,
                                numberEntry = numberEntry,
                                onExit = viewModel::openList,
                                behindDrawer = state.listOpen,
                            )
                        }
                        if (state.listOpen || playing == null) {
                            ChannelListScreen(
                                state = state,
                                onPlay = viewModel::play,
                                onPreview = viewModel::preview,
                                onClose = viewModel::closeList,
                                onSelectGroup = viewModel::selectGroup,
                                onSelectFavorites = viewModel::selectFavorites,
                                onOpenSettings = viewModel::openSettings,
                                onOpenGuide = viewModel::openGuide,
                                onToggleFavorite = viewModel::toggleFavorite,
                                onStartMove = viewModel::startMovingFavorite,
                                onMove = viewModel::moveFavorite,
                                onFinishMove = viewModel::finishMovingFavorite,
                                numberEntry = numberEntry,
                            )
                        }
                    }
                }
            }
        }
    }
}
