package com.couchmpv.android

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import java.util.Locale

private fun trackLabel(f: Format, idx: Int): String {
    val lang = f.language?.takeIf { it != "und" }
        ?.let { Locale.forLanguageTag(it).displayLanguage.ifBlank { it } }
    return f.label ?: lang ?: "Faixa ${idx + 1}"
}

private fun groupsOf(p: Player, type: Int): List<Tracks.Group> =
    p.currentTracks.groups.filter { it.type == type && it.isSupported }

private fun selectedLabel(p: Player, type: Int): String {
    val gs = groupsOf(p, type)
    val i = gs.indexOfFirst { it.isSelected }
    return if (i < 0) "desligado" else trackLabel(gs[i].getTrackFormat(0), i)
}

/** Equivalente ao `cycle audio` / `cycle sub` do mpv. Devolve o texto para o OSD. */
private fun cycleTrack(p: Player, type: Int): String {
    val isText = type == C.TRACK_TYPE_TEXT
    val name = if (isText) "Legendas" else "Áudio"
    val gs = groupsOf(p, type)
    if (gs.isEmpty()) return "$name: nenhuma faixa"
    val cur = gs.indexOfFirst { it.isSelected }
    val next = cur + 1
    val params = p.trackSelectionParameters.buildUpon()
    return if (isText && next >= gs.size) {
        params.setTrackTypeDisabled(type, true)
        p.trackSelectionParameters = params.build()
        "$name: desligadas"
    } else {
        val idx = if (isText) next else next % gs.size
        params.setTrackTypeDisabled(type, false)
            .setOverrideForType(TrackSelectionOverride(gs[idx].mediaTrackGroup, 0))
        p.trackSelectionParameters = params.build()
        "$name: ${trackLabel(gs[idx].getTrackFormat(0), idx)}"
    }
}

@Composable
fun PlayerScreen(req: PlayRequest, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val key = remember(req) { req.video.uri.toString() }
    val title = remember(req) { Library.prettify(req.video.stem).ifBlank { req.video.name } }

    val player = remember(req) {
        ExoPlayer.Builder(ctx).build().also { p ->
            val subs = req.subs.mapIndexed { i, s ->
                MediaItem.SubtitleConfiguration.Builder(s.uri)
                    .setMimeType(Library.subMime(s.ext))
                    .setLabel(s.name)
                    .setSelectionFlags(if (i == 0) C.SELECTION_FLAG_DEFAULT else 0)
                    .build()
            }
            val item = MediaItem.Builder().setUri(req.video.uri).setSubtitleConfigurations(subs).build()
            p.setMediaItem(item, History.resumeMs(ctx, key)) // retoma de onde parou
            p.prepare()
            p.playWhenReady = true
        }
    }

    fun save() {
        val dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        History.update(ctx, key, player.currentPosition, dur)
    }

    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var showBar by remember { mutableStateOf(true) }
    var barUntil by remember { mutableLongStateOf(SystemClock.elapsedRealtime() + 4000) }
    var osd by remember { mutableStateOf<String?>(null) }
    var osdUntil by remember { mutableLongStateOf(0L) }
    var tech by remember { mutableStateOf("") }

    fun poke() { barUntil = SystemClock.elapsedRealtime() + 3500 }
    fun say(msg: String) { osd = msg; osdUntil = SystemClock.elapsedRealtime() + 2000; poke() }

    // Ciclo de vida: Home/minimizar pausa e salva; ao voltar o app continua de onde parou.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) {
                player.pause()
                save()
            }
        }
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) { save(); poke() }
            }
            override fun onPlayerError(error: PlaybackException) {
                say("Erro: ${error.errorCodeName}")
            }
        }
        lifecycle.addObserver(obs)
        player.addListener(listener)
        onDispose {
            lifecycle.removeObserver(obs)
            player.removeListener(listener)
            save()
            player.release()
        }
    }

    // Atualiza barra/OSD e salva progresso a cada ~5 s.
    LaunchedEffect(player) {
        var ticks = 0
        while (true) {
            pos = player.currentPosition
            dur = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val now = SystemClock.elapsedRealtime()
            showBar = !player.playWhenReady || now < barUntil
            if (osd != null && now > osdUntil) osd = null
            if (showBar) {
                val vs = player.videoSize
                tech = listOfNotNull(
                    if (vs.width > 0) "${vs.width}x${vs.height}" else null,
                    "Áudio: ${selectedLabel(player, C.TRACK_TYPE_AUDIO)}",
                    "Legenda: ${selectedLabel(player, C.TRACK_TYPE_TEXT)}",
                ).joinToString("  |  ")
            }
            if (++ticks % 10 == 0 && player.isPlaying) save()
            delay(500)
        }
    }

    fun seekBy(sec: Int) {
        val limit = if (dur > 0) dur else Long.MAX_VALUE
        val target = (player.currentPosition + sec * 1000L).coerceIn(0L, limit)
        player.seekTo(target)
        pos = target
        say(if (sec > 0) "⏩ +${sec}s" else "⏪ ${sec}s")
    }

    fun togglePause() {
        if (player.playbackState == Player.STATE_ENDED) {
            player.seekTo(0); player.playWhenReady = true
        } else {
            player.playWhenReady = !player.playWhenReady
        }
        say(if (player.playWhenReady) "▶ Reproduzindo" else "⏸ Pausado")
    }

    BackHandler { onExit() }

    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { fr.requestFocus() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fr)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (ev.key) {
                    Key.DirectionLeft, Key.MediaRewind -> { seekBy(-Library.SKIP_SECONDS); true }
                    Key.DirectionRight, Key.MediaFastForward -> { seekBy(Library.SKIP_SECONDS); true }
                    Key.DirectionUp -> { say(cycleTrack(player, C.TRACK_TYPE_TEXT)); true }
                    Key.DirectionDown -> { say(cycleTrack(player, C.TRACK_TYPE_AUDIO)); true }
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar,
                    Key.MediaPlayPause -> { togglePause(); true }
                    Key.MediaPlay -> { player.playWhenReady = true; poke(); true }
                    Key.MediaPause -> { player.playWhenReady = false; poke(); true }
                    Key.Escape, Key.Backspace -> { onExit(); true }
                    else -> false
                }
            }
            // Toque: 1 toque = pausar/retomar; 2 toques nas laterais = ±20 s (celular/tablet)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { togglePause() },
                    onDoubleTap = { o ->
                        if (o.x < size.width / 2) seekBy(-Library.SKIP_SECONDS) else seekBy(Library.SKIP_SECONDS)
                    },
                )
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                PlayerView(c).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    isFocusable = false
                    isFocusableInTouchMode = false
                    subtitleView?.setFractionalTextSize(0.055f)
                }
            },
        )

        osd?.let {
            Text(
                it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 36.dp)
                    .background(Color(0xB0000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        if (showBar) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(horizontal = 40.dp, vertical = 28.dp),
            ) {
                Text(
                    title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${Library.formatTime(pos)} / ${Library.formatTime(dur)}" +
                        if (!player.playWhenReady) "   ⏸" else "",
                    color = Color.White, fontSize = 16.sp,
                )
                if (tech.isNotEmpty()) Text(tech, color = Color(0xFFB9B0C8), fontSize = 13.sp)
                LinearProgressIndicator(
                    progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(5.dp),
                    color = ACCENT,
                    trackColor = Color(0x44FFFFFF),
                )
            }
        }
    }
}
