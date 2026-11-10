package dev.app8.engine.render.components

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.ContentFrame
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import coil3.compose.AsyncImage
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.core.model.VideoProperties
import dev.app8.engine.core.model.VideoProperties.EndBehavior
import dev.app8.engine.core.model.VideoProperties.PosterSource
import dev.app8.engine.core.model.style.ContentMode
import dev.app8.engine.core.model.style.VideoStyle
import dev.app8.engine.core.vars.VariableContext
import dev.app8.engine.render.ChildrenLayout
import dev.app8.engine.render.LocalDispatcher
import dev.app8.engine.render.RenderNode
import dev.app8.engine.render.controls.VideoMarkTracker
import dev.app8.engine.render.string
import dev.app8.engine.runtime.AssetRef
import dev.app8.engine.style.DslCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

/** What a `video` plays: a cache key and where its bytes come from. */
private data class VideoSource(val key: String, val id: String?, val name: String?, val url: String?)

/**
 * `video` → Media3 [ExoPlayer] in a [ContentFrame] (TextureView, so corners clip and alpha
 * fades). Autoplay, gapless loop (`REPEAT_MODE_ONE`), mute, `audioMix` (audio focus), rate,
 * `startTime`, `startDelay`, posters (image or video frame) before and after playback,
 * `endBehavior`, and the `onVideo*` / `onTimeMark` triggers with `$loopCount`, `$error`,
 * `$markId`. Plays only while on screen and the host is started; paused otherwise.
 */
internal object VideoKind : ComponentKind {
    @OptIn(UnstableApi::class)
    @Composable
    override fun Render(node: RenderNode, modifier: Modifier) {
        val p = node.content.properties as? VideoProperties ?: return Box(modifier)
        val style = node.content.style as? VideoStyle
        val context = LocalContext.current
        val dispatcher = LocalDispatcher.current
        val source = resolveSource(node, p)
        val scale = when (style?.contentMode) {
            ContentMode.scaleAspectFit -> ContentScale.Fit
            ContentMode.scaleToFill -> ContentScale.FillBounds
            else -> ContentScale.Crop
        }

        var file by remember(source) { mutableStateOf<File?>(null) }
        var playbackStarted by remember(source) { mutableStateOf(false) }
        var completed by remember(source) { mutableStateOf(false) }
        var ready by remember(source) { mutableStateOf(false) }
        var visible by remember { mutableStateOf(false) }
        var hostStarted by remember { mutableStateOf(true) }

        fun fire(trigger: ActionTrigger, overlays: Map<String, Any?> = emptyMap()) {
            var ctx: VariableContext = node.scope.untracked
            for ((k, v) in overlays) ctx = ctx.overlaying(k, v)
            dispatcher.dispatchTrigger(node, trigger, ctx)
        }

        // Bytes → a temp file the player and frame grabber can read (iOS spills to NSTemporaryDirectory too).
        LaunchedEffect(source) {
            file = source?.let { materialize(context, node, it) }
            if (source != null && file == null) {
                node.runtime.diagnostics.warn("video.asset", "video '${source.name ?: source.id ?: source.url}' could not be resolved (${node.id})")
            }
        }

        val player = remember(file) {
            file?.let { f ->
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(f)))
                    repeatMode = if (p.loops) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
                    volume = if (p.muted) 0f else 1f
                    val handleFocus = when (p.audioMix) {
                        VideoProperties.AudioMix.auto -> !p.muted
                        VideoProperties.AudioMix.mix -> false
                        VideoProperties.AudioMix.duck, VideoProperties.AudioMix.interrupt -> true
                    }
                    setAudioAttributes(
                        AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                        handleFocus,
                    )
                    p.rate?.takeIf { it > 0f }?.let { setPlaybackSpeed(it) }
                    p.startTime?.takeIf { it > 0 }?.let { seekTo((it * 1000).toLong()) }
                    playWhenReady = false
                    prepare()
                }
            }
        }
        val marks = remember(file) { VideoMarkTracker(p.marks.orEmpty().map { it.id to it.time }) }

        DisposableEffect(player) {
            val pl = player ?: return@DisposableEffect onDispose { }
            var loops = 0
            var started = false
            val listener = object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_READY -> if (!ready) {
                            ready = true
                            fire(ActionTrigger.onVideoReady)
                        }
                        Player.STATE_BUFFERING -> if (ready && pl.playWhenReady) fire(ActionTrigger.onVideoStall)
                        Player.STATE_ENDED -> if (!p.loops && !completed) {
                            completed = true
                            fire(ActionTrigger.onVideoComplete)
                        }
                        else -> Unit
                    }
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) {
                        playbackStarted = true
                        if (!started) {
                            started = true
                            fire(ActionTrigger.onVideoStart)
                        }
                    } else if (!pl.playWhenReady && pl.playbackState != Player.STATE_ENDED) {
                        fire(ActionTrigger.onVideoPause)
                    }
                }

                override fun onPositionDiscontinuity(old: Player.PositionInfo, new: Player.PositionInfo, reason: Int) {
                    if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION && p.loops) {
                        loops++
                        marks.loop()
                        fire(ActionTrigger.onVideoLoop, mapOf("\$loopCount" to loops.toLong()))
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    fire(ActionTrigger.onVideoError, mapOf("\$error" to (error.message ?: error.errorCodeName)))
                }
            }
            pl.addListener(listener)
            onDispose {
                pl.removeListener(listener)
                pl.release()
            }
        }

        // Pause off screen and while the host is stopped (iOS didMoveToWindow / background).
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(lifecycle) {
            val obs = LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_STOP) hostStarted = false
                if (e == Lifecycle.Event.ON_START) hostStarted = true
            }
            lifecycle.addObserver(obs)
            onDispose { lifecycle.removeObserver(obs) }
        }
        val shouldPlay = p.autoplay && visible && hostStarted && ready && !completed
        LaunchedEffect(player, shouldPlay) {
            val pl = player ?: return@LaunchedEffect
            if (!shouldPlay) {
                pl.playWhenReady = false
                return@LaunchedEffect
            }
            if (!playbackStarted) p.startDelay?.takeIf { it > 0 }?.let { delay((it * 1000).toLong()) }
            pl.playWhenReady = true
            // Forward-only marks, sampled while playing.
            if (!p.marks.isNullOrEmpty()) {
                while (isActive) {
                    for (id in marks.advance(pl.currentPosition / 1000.0)) fire(ActionTrigger.onTimeMark, mapOf("\$markId" to id))
                    delay(50)
                }
            }
        }

        // Posters: before the first frame, and after completion for showPoster.
        val endShown = completed && p.endBehavior == EndBehavior.showPoster
        val posterSource = if (endShown) p.endPoster ?: p.poster else p.poster
        val posterVisible = posterSource != null && (endShown || (!playbackStarted && !completed))
        val posterAlpha by animateFloatAsState(if (posterVisible) 1f else 0f, tween(250), label = "poster")
        val playerHidden = completed && p.endBehavior == EndBehavior.hidePoster

        var m = modifier.clipToBounds().onGloballyPositioned { c ->
            val b = c.boundsInWindow()
            visible = b.width > 0f && b.height > 0f
        }
        style?.corner?.let { m = m.clip(DslCornerShape(it)) }
        val currentFile by rememberUpdatedState(file)
        Box(m) {
            if (player != null && !playerHidden) {
                ContentFrame(
                    player = player,
                    modifier = Modifier.fillMaxSize(),
                    surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                    contentScale = scale,
                    keepContentOnReset = true,
                    shutter = {},
                )
            }
            if (posterSource != null && posterAlpha > 0f && !(playerHidden && !endShown)) {
                Poster(node, posterSource, currentFile, scale, Modifier.fillMaxSize().graphicsLayer { alpha = posterAlpha })
            }
            ChildrenLayout(node, Modifier.fillMaxSize())
        }
    }

    @Composable
    private fun Poster(node: RenderNode, source: PosterSource, file: File?, scale: ContentScale, modifier: Modifier) {
        when (source.kind) {
            PosterSource.Kind.firstFrame, PosterSource.Kind.frameAtTime -> {
                val at = if (source.kind == PosterSource.Kind.firstFrame) 0.0 else source.time ?: 0.0
                var frame by remember(file, at) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(file, at) { frame = file?.let { grabFrame(it, at) } }
                frame?.let { Image(it, null, modifier, contentScale = scale) }
            }
            else -> {
                val name = source.name?.let { node.string(it) }?.takeIf { it.isNotEmpty() }
                val url = source.url?.let { node.string(it) }?.takeIf { it.isNotEmpty() }
                    ?: name?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                AsyncImage(model = AssetRef(source.id, name, url), contentDescription = null, imageLoader = node.runtime.imageLoader, contentScale = scale, modifier = modifier)
            }
        }
    }

    private fun resolveSource(node: RenderNode, p: VideoProperties): VideoSource? = when (val m = p.model) {
        is VideoProperties.Model.LocalAsset -> node.string(m.name)?.takeIf { it.isNotEmpty() }?.let { VideoSource(it, null, it, null) }
        is VideoProperties.Model.RemoteAsset -> {
            val name = m.asset.name?.let { node.string(it) }?.takeIf { it.isNotEmpty() }
            val url = m.asset.url?.let { node.string(it) }?.takeIf { it.isNotEmpty() }
                ?: name?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            VideoSource(url ?: name ?: m.asset.id ?: "", m.asset.id, name, url)
        }
        VideoProperties.Model.None -> null
    }

    /** iOS `loadRemoteVideo`: data source bytes spilled to a cache file, else the URL streamed to a file. */
    private suspend fun materialize(context: Context, node: RenderNode, s: VideoSource): File? = withContext(Dispatchers.IO) {
        val ext = (s.name ?: s.url)?.substringAfterLast('.', "")?.lowercase()?.takeIf { it in setOf("mp4", "mov", "m4v", "webm") } ?: "mp4"
        val safe = s.key.filter { it.isLetterOrDigit() }.takeLast(96).ifEmpty { "video" }
        val out = File(VideoTempFiles.directory(context, node.runtime.dataSource), "$safe.$ext")
        if (out.exists() && out.length() > 0) return@withContext out
        val names = listOfNotNull(s.name) + (s.name?.takeIf { '.' !in it }?.let { n -> listOf("$n.mp4", "$n.mov", "$n.m4v") } ?: emptyList())
        val bytes = runCatching { node.runtime.dataSource.getAsset(s.id, s.name) }.getOrNull()
            ?: names.drop(1).firstNotNullOfOrNull { n -> runCatching { node.runtime.dataSource.getAsset(null, n) }.getOrNull() }
            ?: s.url?.let { u -> runCatching { java.net.URL(u).openStream().use { it.readBytes() } }.getOrNull() }
            ?: return@withContext null
        val tmp = File(out.path + ".part")
        tmp.writeBytes(bytes)
        tmp.renameTo(out)
        out
    }

    private suspend fun grabFrame(file: File, seconds: Double): ImageBitmap? = withContext(Dispatchers.IO) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.path)
            r.getFrameAtTime((seconds.coerceAtLeast(0.0) * 1_000_000).toLong(), MediaMetadataRetriever.OPTION_CLOSEST)?.asImageBitmap()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }
}
