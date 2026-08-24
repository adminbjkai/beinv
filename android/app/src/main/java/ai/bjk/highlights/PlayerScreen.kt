package ai.bjk.highlights

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(playlist: Playlist, inPip: Boolean, onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember { context.findActivity() }
    val clips = playlist.clips
    var current by remember { mutableIntStateOf(playlist.start) }
    val positions = remember { HashMap<Int, Long>() } // best-effort resume per clip
    var playerError by remember { mutableStateOf<String?>(null) }

    // --- fullscreen: manual toggle OR device rotated to landscape ---
    val deviceLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    var fsRequested by remember { mutableStateOf(false) }
    // Set when the user leaves fullscreen while the device is still landscape, so we stay windowed
    // instead of immediately re-entering. Rotating back to portrait re-arms auto-fullscreen.
    var fsDismissed by remember { mutableStateOf(false) }
    val fullscreen = (fsRequested || (deviceLandscape && !fsDismissed)) && !inPip
    LaunchedEffect(deviceLandscape) { if (!deviceLandscape) fsDismissed = false }
    fun enterFullscreen() {
        fsRequested = true
        fsDismissed = false
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
    fun exitFullscreen() {
        fsRequested = false
        fsDismissed = deviceLandscape
        // Hand orientation back to the sensor. Forcing PORTRAIT here would hard-lock the activity,
        // so the user could not rotate into landscape again for the rest of the player session.
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    BackHandler(enabled = fullscreen) { exitFullscreen() }

    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(Api.USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
            .apply {
                setMediaItems(clips.map { MediaItem.fromUri(it.url) }, playlist.start, 0L)
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val i = player.currentMediaItemIndex
                if (i in clips.indices) current = i
            }
            override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                // Only a genuine mid-clip position is worth remembering. On an auto-transition
                // `old.positionMs` is the clip's end, so storing it would make re-selecting that clip
                // seek to its last frame — it would end instantly and skip on (or close the player).
                if (old.mediaItemIndex == new.mediaItemIndex) return
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    // The clip finished: drop any earlier mid-clip position so re-selecting it
                    // restarts from the beginning instead of resuming near its last frame.
                    positions.remove(old.mediaItemIndex)
                } else {
                    positions[old.mediaItemIndex] = old.positionMs
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) playerError = null
                if (state == Player.STATE_ENDED) { // last clip ended (no loop): back to the list
                    if (fsRequested) exitFullscreen()
                    onBack()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                playerError = error.errorCodeName
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    fun select(i: Int) {
        player.seekTo(i, positions[i] ?: 0L)
        player.play()
    }

    // PiP eligibility, orientation reset, pause when backgrounded (but not while in PiP)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(Unit) {
        (activity as? MainActivity)?.pipAllowed = true
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && activity?.isInPictureInPictureMode != true) player.pause()
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            (activity as? MainActivity)?.pipAllowed = false
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // Immersive system bars while fullscreen
    DisposableEffect(fullscreen) {
        val window = activity?.window
        val ctl = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        if (fullscreen) {
            ctl?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl?.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl?.show(WindowInsetsCompat.Type.systemBars())
        onDispose { if (fullscreen) ctl?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // Grouped list: week headers + rows. Index of the row for clip i (for auto-scroll) is computed by the list builder.
    val listState = rememberLazyListState()
    val rowIndex = remember(clips) { clipRowIndices(clips) }
    LaunchedEffect(current) { if (current in clips.indices) listState.animateScrollToItem((rowIndex[current] - 1).coerceAtLeast(0)) }
    var drawer by remember { mutableStateOf(false) } // landscape side drawer
    LaunchedEffect(fullscreen) { if (!fullscreen) drawer = false }
    BackHandler(enabled = drawer) { drawer = false } // declared after the fullscreen handler → wins first
    val upNext = clips.getOrNull(current + 1)?.title
    val previous = clips.getOrNull(current - 1)?.title

    Column(Modifier.fillMaxSize().background(if (fullscreen || inPip) Color.Black else Background)
        .then(if (fullscreen || inPip) Modifier else Modifier.statusBarsPadding())) {
        if (!fullscreen && !inPip) {
            Surface(color = ai.bjk.highlights.Surface, contentColor = OnDark) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(end = 4.dp),
                    ) {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                        Column(Modifier.weight(1f)) {
                            Text(
                                "${current + 1} of ${clips.size}", color = Emerald,
                                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                clips.getOrNull(current)?.title ?: playlist.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if ((activity as? MainActivity) != null) {
                            TextButton(onClick = { (activity as MainActivity).enterPip() }) { Text("PiP", color = Emerald) }
                        }
                    }
                    NextPrevBar(upNext, previous)
                }
            }
        }
        Box(if (fullscreen || inPip) Modifier.fillMaxSize() else Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    keepScreenOn = true
                    setShowNextButton(true)
                    setShowPreviousButton(true)
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    setFullscreenButtonClickListener { fs -> if (fs) enterFullscreen() else exitFullscreen() }
                }
            },
            update = { it.useController = !inPip },
            modifier = Modifier.fillMaxSize(),
        )
        val error = playerError
        if (error != null && !inPip) {
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(16.dp))
                    .background(Color.Black.copy(alpha = 0.88f)).padding(horizontal = 22.dp, vertical = 18.dp)
                    .semantics { stateDescription = "Playback error" },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Couldn't play this video", color = OnDark, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(error, color = TextGray, style = MaterialTheme.typography.labelSmall)
                Spacer(Modifier.height(6.dp))
                TextButton(onClick = { playerError = null; player.prepare(); player.play() }) {
                    Text("Retry", color = Emerald)
                }
            }
        }
        if (fullscreen) {
            // Landscape: "Clips" button in the chrome toggles a right side drawer (≤ 35 % width); video keeps playing.
            if (drawer) Box(
                Modifier.fillMaxSize().clickable { drawer = false }
                    .semantics { contentDescription = "Close clips" }
            ) // tap outside closes
            TextButton(
                onClick = { drawer = !drawer },
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                    .clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.45f)),
            ) {
                Icon(if (drawer) Icons.Default.Close else Icons.AutoMirrored.Filled.List, null, tint = Emerald)
                Spacer(Modifier.width(4.dp)); Text("Clips", color = Emerald)
            }
            if (drawer) Column(
                Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(0.35f)
                    .background(Brush.horizontalGradient(listOf(Color(0xCC0B0F0E), Color(0xEE0B0F0E))))
                    .clickable(enabled = false) {},
            ) {
                Text(
                    "Clips · ${current + 1} of ${clips.size}", color = Emerald, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp, top = 44.dp, bottom = 2.dp),
                )
                NextPrevBar(upNext, previous, compact = true)
                val drawerState = rememberLazyListState()
                LaunchedEffect(current) {
                    if (current in clips.indices) drawerState.animateScrollToItem((rowIndex[current] - 1).coerceAtLeast(0))
                }
                LazyColumn(state = drawerState, contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    clipRows(clips, current, compact = true) { select(it) }
                }
            }
        }
        }
        if (!fullscreen && !inPip) {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f).navigationBarsPadding(),
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                clipRows(clips, current, compact = false) { select(it) }
            }
        }
    }
}


/** "Up next: …" / "Previous: …" under the title so next/prev are never blind. */
@Composable
private fun NextPrevBar(upNext: String?, previous: String?, compact: Boolean = false) {
    val pad = if (compact) 12.dp else 16.dp
    Column(Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 2.dp)) {
        if (upNext != null) Text("Up next: $upNext", color = Emerald.copy(alpha = 0.9f),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (previous != null) Text("Previous: $previous", color = TextGray,
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (upNext == null && previous == null) Spacer(Modifier.height(0.dp))
    }
}

/** Lazy-list row index of each clip when rendered by [clipRows] (week headers take a slot). */
private fun clipRowIndices(clips: List<Clip>): IntArray {
    val out = IntArray(clips.size); var row = 0; var lastWeek: String? = null
    clips.forEachIndexed { i, c ->
        if (c.week != null && c.week != lastWeek) { row++; lastWeek = c.week }
        out[i] = row++
    }
    return out
}

/**
 * Ordered playlist grouped by week headers; row = minute · team logo · scorer · running score (match on 2nd line).
 * Current row emerald-highlighted. Shared by the portrait list (below the video) and the landscape drawer.
 */
private fun LazyListScope.clipRows(clips: List<Clip>, current: Int, compact: Boolean, onPick: (Int) -> Unit) {
    var lastWeek: String? = null
    clips.forEachIndexed { i, c ->
        if (c.week != null && c.week != lastWeek) {
            lastWeek = c.week
            item(key = "w$i") {
                Text(c.week, color = Emerald, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 4.dp, top = if (i == 0) 0.dp else 8.dp, bottom = 2.dp))
            }
        }
        item(key = "c$i") {
            val sel = i == current
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Emerald.copy(alpha = 0.2f) else if (compact) Color.White.copy(alpha = 0.06f) else Surface)
                    .selectable(selected = sel, role = Role.Button) { onPick(i) }
                    .semantics { stateDescription = if (sel) "Now playing" else "Clip ${i + 1} of ${clips.size}" }
                    .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = if (compact) 8.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (c.goal) {
                    Text("${c.minute ?: "?"}'", color = if (sel) Emerald else TextGray, fontSize = if (compact) 14.sp else 13.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.width(if (compact) 30.dp else 36.dp))
                    if (c.logo != null) AsyncImage(c.logo, null, Modifier.size(if (compact) 20.dp else 26.dp))
                    else Box(Modifier.size(if (compact) 20.dp else 26.dp).clip(CircleShape).background(Background))
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(c.scorer ?: c.title, color = if (sel) Emerald else OnDark, fontSize = if (compact) 14.sp else 15.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal)
                    val second = if (c.goal) c.match ?: c.subtitle else c.subtitle
                    if (!compact && second != null)
                        Text(second, color = TextGray, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (c.score != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(c.score, color = if (sel) Emerald else OnDark, fontSize = if (compact) 14.sp else 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
