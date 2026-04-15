package com.example.xirolite

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.os.SystemClock
import android.view.TextureView
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.delay

class RtspPlayerController {
    internal var captureFrameImpl: (() -> Bitmap?)? = null
    fun captureFrame(): Bitmap? = captureFrameImpl?.invoke()
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun RtspPlayer(
    url: String,
    reloadToken: Int = 0,
    streamProfile: StreamProfile = StreamProfile.AUTO,
    modifier: Modifier = Modifier,
    controller: RtspPlayerController = remember { RtspPlayerController() },
    onFirstFrameRendered: (() -> Unit)? = null,
    onPlaybackResumed: (() -> Unit)? = null,
    onRecoveryRequested: ((String) -> Unit)? = null,
    onLog: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var watchdogReloadToken by remember(url, streamProfile) { mutableIntStateOf(0) }
    var playerState by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableIntStateOf(Player.STATE_IDLE)
    }
    var hadLivePlayback by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableStateOf(false)
    }
    var hasRenderedFrame by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableStateOf(false)
    }
    var lastFrameAtMs by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var bufferingSinceAtMs by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var lastAutoRecoveryAtMs by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var sessionStartedAtMs by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var autoRecoveryRequested by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableStateOf(false)
    }
    var firstFrameDelivered by remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        mutableStateOf(false)
    }

    fun requestAutomaticRecovery(reason: String) {
        val now = SystemClock.elapsedRealtime()
        if (autoRecoveryRequested) return
        if (lastAutoRecoveryAtMs != 0L && now - lastAutoRecoveryAtMs < 12_000L) return
        autoRecoveryRequested = true
        lastAutoRecoveryAtMs = now
        onLog("RTSP auto-recovery: $reason")
        onRecoveryRequested?.invoke(reason)
    }

    val exoPlayer = remember(url, reloadToken, streamProfile, watchdogReloadToken) {
        val bufferTuple = when (streamProfile) {
            StreamProfile.QUALITY -> intArrayOf(250, 1000, 100, 150)
            StreamProfile.AUTO -> intArrayOf(500, 1500, 250, 300)
            StreamProfile.STABILITY -> intArrayOf(1200, 3500, 500, 800)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferTuple[0], bufferTuple[1], bufferTuple[2], bufferTuple[3])
            .setBackBuffer(0, false)
            .build()

        ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }

    DisposableEffect(exoPlayer, onLog) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                playerState = playbackState
                val now = SystemClock.elapsedRealtime()
                when (playbackState) {
                    Player.STATE_BUFFERING -> {
                        if (hadLivePlayback && bufferingSinceAtMs == 0L) {
                            bufferingSinceAtMs = now
                        }
                    }

                    Player.STATE_READY -> {
                        bufferingSinceAtMs = 0L
                    }

                    Player.STATE_ENDED -> {
                        if (hadLivePlayback) {
                            requestAutomaticRecovery("player reached ENDED after live frames")
                        }
                    }

                    Player.STATE_IDLE -> Unit
                }
                val state = when (playbackState) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "UNKNOWN"
                }
                onLog("Player state: $state")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onLog("isPlaying: $isPlaying")
                if (isPlaying) {
                    if (sessionStartedAtMs == 0L) {
                        sessionStartedAtMs = SystemClock.elapsedRealtime()
                    }
                    hadLivePlayback = true
                    onPlaybackResumed?.invoke()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onLog("Player error: ${error.message}")
                if (hadLivePlayback) {
                    requestAutomaticRecovery("player error after live frames: ${error.errorCodeName}")
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            controller.captureFrameImpl = null
            exoPlayer.removeListener(listener)
            onLog("Releasing player")
            exoPlayer.release()
        }
    }

    LaunchedEffect(url, reloadToken, streamProfile, watchdogReloadToken, onRecoveryRequested) {
        while (true) {
            delay(2_000)
            if (!hadLivePlayback || autoRecoveryRequested) continue

            val now = SystemClock.elapsedRealtime()
            if (sessionStartedAtMs != 0L) {
                val sessionAgeMs = now - sessionStartedAtMs
                if (sessionAgeMs >= 26_000L) {
                    requestAutomaticRecovery("preemptive RTSP session refresh before the camera's ~30s timeout")
                    continue
                }
            }

            if (playerState == Player.STATE_READY && hasRenderedFrame) {
                val staleForMs = now - lastFrameAtMs
                if (staleForMs >= 8_000L) {
                    requestAutomaticRecovery("no new frames for ${staleForMs / 1000}s while player stayed READY")
                    continue
                }
            }

            if (playerState == Player.STATE_BUFFERING && bufferingSinceAtMs != 0L) {
                val bufferingForMs = now - bufferingSinceAtMs
                if (bufferingForMs >= 6_000L) {
                    requestAutomaticRecovery("buffering for ${bufferingForMs / 1000}s after live frames")
                }
            }
        }
    }

    LaunchedEffect(url, reloadToken, watchdogReloadToken, exoPlayer) {
        try {
            onLog("Preparing RTSP stream: $url - profile=${streamProfile.label}")
            val mediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(MediaItem.fromUri(url))
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
        } catch (t: Throwable) {
            onLog("Player setup error: ${t.message}")
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                isOpaque = true
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                        exoPlayer.setVideoTextureView(this@apply)
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surface: SurfaceTexture,
                        width: Int,
                        height: Int
                    ) = Unit

                    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                        exoPlayer.clearVideoTextureView(this@apply)
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                        if (sessionStartedAtMs == 0L) {
                            sessionStartedAtMs = SystemClock.elapsedRealtime()
                        }
                        hadLivePlayback = true
                        hasRenderedFrame = true
                        lastFrameAtMs = SystemClock.elapsedRealtime()
                        bufferingSinceAtMs = 0L
                        autoRecoveryRequested = false
                        if (!firstFrameDelivered) {
                            firstFrameDelivered = true
                            onFirstFrameRendered?.invoke()
                        }
                    }
                }
            }
        },
        update = { textureView ->
            exoPlayer.setVideoTextureView(textureView)
            controller.captureFrameImpl = {
                try {
                    textureView.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
                } catch (_: Throwable) {
                    null
                }
            }
        }
    )
}
