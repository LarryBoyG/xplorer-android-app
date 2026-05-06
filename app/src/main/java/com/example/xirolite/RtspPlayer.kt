package com.example.xirolite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.ConnectivityManager
import android.net.Uri
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.rtsp.RtspDebugStats
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

class RtspPlayerController {
    internal var captureFrameImpl: (() -> Bitmap?)? = null
    fun captureFrame(): Bitmap? = captureFrameImpl?.invoke()
}

private const val LEGACY_RTSP_USER_AGENT = "xiroRTSP (Rtsp Client/1.0.0)"
private const val UNSET_LIVE_OFFSET_SNAPBACK_THRESHOLD_MS = 950L
private const val LIVE_SNAPBACK_COOLDOWN_MS = 1_800L
private const val LIVE_CATCHUP_CLEAR_BUFFER_MS = 90L
private const val LIVE_CATCHUP_STEP_ONE_MS = 140L
private const val LIVE_CATCHUP_STEP_TWO_MS = 220L
private const val LIVE_CATCHUP_STEP_THREE_MS = 320L
private const val LIVE_CATCHUP_STEP_FOUR_MS = 450L
private const val LIVE_CATCHUP_STEP_FIVE_MS = 650L
private const val LIVE_CATCHUP_STEP_SIX_MS = 850L

private fun xiroH264DecoderRank(decoderInfo: MediaCodecInfo): Int =
    when {
        decoderInfo.softwareOnly -> 3
        !decoderInfo.hardwareAccelerated -> 2
        decoderInfo.name.startsWith("c2.android.", ignoreCase = true) -> 1
        decoderInfo.name.startsWith("omx.google.", ignoreCase = true) -> 1
        else -> 0
    }

private fun MutableList<MediaCodecInfo>.sortForXiroH264Preference() {
    sortByDescending(::xiroH264DecoderRank)
}

private fun describeXiroH264DecoderCandidates(): String =
    runCatching {
        MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
            .toMutableList()
            .apply { sortForXiroH264Preference() }
            .joinToString(separator = ", ") { decoderInfo ->
                buildString {
                    append(decoderInfo.name)
                    append(
                        when {
                            decoderInfo.softwareOnly -> " [software]"
                            !decoderInfo.hardwareAccelerated -> " [non-hw]"
                            else -> " [hardware]"
                        }
                    )
                }
            }
    }.getOrElse { error ->
        "unavailable (${error::class.java.simpleName}: ${error.message ?: "no message"})"
    }

private val XIRO_H264_PREFERRED_CODEC_SELECTOR = MediaCodecSelector { mimeType, secure, tunneling ->
    val decoders = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling).toMutableList()
    if (mimeType == MimeTypes.VIDEO_H264) {
        decoders.sortForXiroH264Preference()
    }
    decoders
}

private fun PlaybackException.describeForLog(): String {
    val directCause = cause
    val rootCause = generateSequence(directCause) { it.cause }.lastOrNull()
    val messageText = message ?: "no message"
    val causeText = directCause?.let { "${it::class.java.simpleName}: ${it.message ?: "no message"}" }
        ?: "none"
    val rootText = if (rootCause != null && rootCause !== directCause) {
        " root=${rootCause::class.java.simpleName}: ${rootCause.message ?: "no message"}"
    } else {
        ""
    }
    return "$errorCodeName - $messageText - cause=$causeText$rootText"
}

private fun playerStateLabel(playbackState: Int): String =
    when (playbackState) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

private fun formatLiveOffsetForLog(currentLiveOffsetMs: Long): String =
    if (currentLiveOffsetMs == C.TIME_UNSET) "unset" else "${currentLiveOffsetMs}ms"

private fun desiredCatchupSpeed(
    streamProfile: StreamProfile,
    currentLiveOffsetMs: Long,
    bufferedAheadMs: Long,
    currentSpeed: Float
): Float {
    if (streamProfile.forceRtpTcp) {
        return 1.0f
    }
    val latencySignalMs = if (currentLiveOffsetMs != C.TIME_UNSET) currentLiveOffsetMs else bufferedAheadMs
    val targetSpeed = when {
        latencySignalMs >= LIVE_CATCHUP_STEP_SIX_MS -> 1.45f
        latencySignalMs >= LIVE_CATCHUP_STEP_FIVE_MS -> 1.34f
        latencySignalMs >= LIVE_CATCHUP_STEP_FOUR_MS -> 1.24f
        latencySignalMs >= LIVE_CATCHUP_STEP_THREE_MS -> 1.16f
        latencySignalMs >= LIVE_CATCHUP_STEP_TWO_MS -> 1.10f
        latencySignalMs >= LIVE_CATCHUP_STEP_ONE_MS -> 1.05f
        latencySignalMs <= LIVE_CATCHUP_CLEAR_BUFFER_MS -> 1.0f
        else -> currentSpeed
    }
    return targetSpeed.coerceIn(streamProfile.minPlaybackSpeed, streamProfile.maxPlaybackSpeed)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun RtspPlayer(
    url: String,
    reloadToken: Int = 0,
    streamProfile: StreamProfile = StreamProfile.AUTO,
    debugRtspMessages: Boolean = false,
    modifier: Modifier = Modifier,
    controller: RtspPlayerController = remember { RtspPlayerController() },
    onFirstFrameRendered: (() -> Unit)? = null,
    onPlaybackResumed: (() -> Unit)? = null,
    onRecoveryRequested: ((String) -> Unit)? = null,
    onLog: (String) -> Unit = {}
) {
    val context = LocalContext.current
    var watchdogReloadToken by remember(url, streamProfile, debugRtspMessages) { mutableIntStateOf(0) }
    var playerState by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableIntStateOf(Player.STATE_IDLE)
    }
    var hadLivePlayback by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableStateOf(false)
    }
    var hasRenderedFrame by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableStateOf(false)
    }
    var lastFrameAtMs by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var bufferingSinceAtMs by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var lastAutoRecoveryAtMs by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var sessionStartedAtMs by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var prepareStartedAtMs by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var autoRecoveryRequested by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableStateOf(false)
    }
    var firstFrameDelivered by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableStateOf(false)
    }
    var lastLiveSnapbackAtMs by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableLongStateOf(0L)
    }
    var snapbackTriggerCount by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableIntStateOf(0)
    }
    var appliedPlaybackSpeed by remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        mutableStateOf(1.0f)
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

    val exoPlayer = remember(url, reloadToken, streamProfile, debugRtspMessages, watchdogReloadToken) {
        val bufferTuple = when (streamProfile) {
            StreamProfile.QUALITY -> intArrayOf(25, 110, 5, 10)
            StreamProfile.AUTO -> intArrayOf(25, 110, 5, 10)
            StreamProfile.STABILITY -> intArrayOf(800, 2200, 300, 500)
        }
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(bufferTuple[0], bufferTuple[1], bufferTuple[2], bufferTuple[3])
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
        val renderersFactory = DefaultRenderersFactory(context)
            .forceDisableMediaCodecAsynchronousQueueing()
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(XIRO_H264_PREFERRED_CODEC_SELECTOR)

        ExoPlayer.Builder(context, renderersFactory)
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
                val state = playerStateLabel(playbackState)
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
                onLog("Player error: ${error.describeForLog()}")
                if (hadLivePlayback) {
                    requestAutomaticRecovery("player error after live frames: ${error.errorCodeName}")
                }
            }
        }
        val analyticsListener = object : AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: AnalyticsListener.EventTime,
                decoderName: String,
                initializedTimestampMs: Long,
                initializationDurationMs: Long
            ) {
                onLog("Video decoder initialized: $decoderName (${initializationDurationMs}ms)")
            }
        }
        exoPlayer.addListener(listener)
        exoPlayer.addAnalyticsListener(analyticsListener)
        onDispose {
            controller.captureFrameImpl = null
            exoPlayer.removeListener(listener)
            exoPlayer.removeAnalyticsListener(analyticsListener)
            onLog("Releasing player")
            exoPlayer.release()
        }
    }

    LaunchedEffect(url, reloadToken, streamProfile, watchdogReloadToken, onRecoveryRequested) {
        while (true) {
            delay(2_000)
            if (autoRecoveryRequested) continue

            val now = SystemClock.elapsedRealtime()
            val bufferedAheadMs = maxOf(0L, exoPlayer.bufferedPosition - exoPlayer.currentPosition)
            val currentLiveOffsetMs = exoPlayer.currentLiveOffset
            val snapshot = RtspDebugStats.snapshot()
            if (playerState == Player.STATE_READY) {
                val desiredPlaybackSpeed =
                    desiredCatchupSpeed(
                        streamProfile = streamProfile,
                        currentLiveOffsetMs = currentLiveOffsetMs,
                        bufferedAheadMs = bufferedAheadMs,
                        currentSpeed = appliedPlaybackSpeed
                    )
                if (desiredPlaybackSpeed != appliedPlaybackSpeed) {
                    appliedPlaybackSpeed = desiredPlaybackSpeed
                    exoPlayer.setPlaybackParameters(PlaybackParameters(desiredPlaybackSpeed))
                    if (debugRtspMessages) {
                        onLog(
                            "Live catch-up speed -> ${"%.2f".format(desiredPlaybackSpeed)}x " +
                                "(liveOffset=${formatLiveOffsetForLog(currentLiveOffsetMs)}, " +
                            "bufferAhead=${bufferedAheadMs}ms)"
                        )
                    }
                }
                val liveSnapbackOffsetMs = streamProfile.liveSnapbackOffsetMs
                val effectiveSnapbackOffsetMs = when {
                    liveSnapbackOffsetMs == null -> null
                    currentLiveOffsetMs == C.TIME_UNSET ->
                        maxOf(liveSnapbackOffsetMs, UNSET_LIVE_OFFSET_SNAPBACK_THRESHOLD_MS)
                    else -> liveSnapbackOffsetMs
                }
                if (
                    effectiveSnapbackOffsetMs != null &&
                    (
                        (currentLiveOffsetMs != C.TIME_UNSET && currentLiveOffsetMs >= effectiveSnapbackOffsetMs) ||
                            bufferedAheadMs >= effectiveSnapbackOffsetMs
                    ) &&
                    now - lastLiveSnapbackAtMs >= LIVE_SNAPBACK_COOLDOWN_MS
                ) {
                    lastLiveSnapbackAtMs = now
                    snapbackTriggerCount += 1
                    if (currentLiveOffsetMs == C.TIME_UNSET) {
                        onLog(
                            "Live latency exceeded ${effectiveSnapbackOffsetMs}ms " +
                                "(liveOffset=${formatLiveOffsetForLog(currentLiveOffsetMs)}, " +
                                "bufferAhead=${bufferedAheadMs}ms, renderedFrame=$hasRenderedFrame, " +
                                "livePlayback=$hadLivePlayback); keeping the UDP session alive and " +
                                "letting catch-up speed handle backlog because latency-triggered rebuilds " +
                                "have been causing visible flight freezes"
                        )
                    } else {
                        onLog(
                            "Live latency exceeded ${effectiveSnapbackOffsetMs}ms " +
                                "(liveOffset=${formatLiveOffsetForLog(currentLiveOffsetMs)}, " +
                                "bufferAhead=${bufferedAheadMs}ms, renderedFrame=$hasRenderedFrame, " +
                                "livePlayback=$hadLivePlayback); snapping playback back toward the live edge"
                        )
                        exoPlayer.seekToDefaultPosition()
                    }
                    continue
                }
                val staleForMs = if (lastFrameAtMs != 0L) now - lastFrameAtMs else 0L
                if (lastFrameAtMs != 0L && staleForMs >= streamProfile.staleFrameRecoveryMs) {
                    requestAutomaticRecovery("no new frames for ${staleForMs / 1000}s while player stayed READY")
                    continue
                }
            } else if (
                appliedPlaybackSpeed != 1.0f &&
                (
                    playerState == Player.STATE_IDLE ||
                        playerState == Player.STATE_ENDED ||
                        bufferedAheadMs <= LIVE_CATCHUP_CLEAR_BUFFER_MS
                    )
            ) {
                appliedPlaybackSpeed = 1.0f
                exoPlayer.setPlaybackParameters(PlaybackParameters(1.0f))
                if (debugRtspMessages) {
                    onLog(
                        "Live catch-up speed -> 1.00x " +
                            "(playerState=${playerStateLabel(playerState)}, bufferAhead=${bufferedAheadMs}ms)"
                    )
                }
            }

            if (debugRtspMessages) {
                onLog(
                    "Live metrics: state=${playerStateLabel(playerState)} " +
                        "liveOffset=${formatLiveOffsetForLog(currentLiveOffsetMs)} " +
                        "bufferAhead=${bufferedAheadMs}ms speed=${"%.2f".format(appliedPlaybackSpeed)} " +
                        "snapbacks=$snapbackTriggerCount " +
                        "rtpPackets=${snapshot.packetCount} rtpBytes=${snapshot.packetBytes} " +
                        "rtpLastGap=${snapshot.lastGapMs}ms rtpMaxGap=${snapshot.maxGapMs}ms " +
                        "rtpIdle=${snapshot.idleForMs}ms " +
                        "rtpBurstGaps(100/250/500/1000ms)=" +
                        "${snapshot.gapCount100Ms}/${snapshot.gapCount250Ms}/" +
                        "${snapshot.gapCount500Ms}/${snapshot.gapCount1000Ms}"
                )
            }
            if (!hadLivePlayback && !hasRenderedFrame) {
                if (playerState == Player.STATE_BUFFERING && prepareStartedAtMs != 0L) {
                    val startupForMs = now - prepareStartedAtMs
                    if (startupForMs >= streamProfile.startupRecoveryMs) {
                        requestAutomaticRecovery("no live frames after ${startupForMs / 1000}s during RTSP startup")
                    }
                }
                continue
            }

            val preemptiveRefreshMs = streamProfile.preemptiveRefreshMs
            if (preemptiveRefreshMs != null && sessionStartedAtMs != 0L) {
                val sessionAgeMs = now - sessionStartedAtMs
                if (sessionAgeMs >= preemptiveRefreshMs) {
                    requestAutomaticRecovery("preemptive RTSP session refresh before the camera's ~30s timeout")
                    continue
                }
            }

            if (playerState == Player.STATE_BUFFERING && bufferingSinceAtMs != 0L) {
                val bufferingForMs = now - bufferingSinceAtMs
                if (bufferingForMs >= streamProfile.bufferingRecoveryMs) {
                    requestAutomaticRecovery("buffering for ${bufferingForMs / 1000}s after live frames")
                }
            }
        }
    }

    LaunchedEffect(url, reloadToken, watchdogReloadToken, debugRtspMessages, exoPlayer) {
        try {
            RtspDebugStats.reset("${streamProfile.label}/${streamProfile.transportLabel}")
            prepareStartedAtMs = SystemClock.elapsedRealtime()
            onLog(
                "Preparing RTSP stream: $url - profile=${streamProfile.label} " +
                    "transport=${streamProfile.transportLabel} timeout=${streamProfile.timeoutMs}ms"
            )
            val targetHost = Uri.parse(url).host
            val rtspFactory = RtspMediaSource.Factory()
                .setUserAgent(LEGACY_RTSP_USER_AGENT)
                .setTimeoutMs(streamProfile.timeoutMs)

            if (debugRtspMessages) {
                rtspFactory.setDebugLoggingEnabled(true)
                onLog("RTSP Media3 message logging enabled through Debug Mode")
            }

            onLog("H264 decoder candidates: ${describeXiroH264DecoderCandidates()}")

            if (targetHost == "192.168.1.254") {
                onLog("RTSP using XIRO legacy active-session GET_PARAMETER keepalive every 3s")
            }

            if (streamProfile.forceRtpTcp) {
                rtspFactory.setForceUseRtpTcp(true)
            }

            val wifiRoute = WifiRouteSelector.selectWifiNetwork(context, targetHost)
            if (wifiRoute != null) {
                onLog("Binding process to ${wifiRoute.summary}")
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                cm.bindProcessToNetwork(wifiRoute.network)
            } else {
                onLog("RTSP route forcing disabled: no suitable XIRO Wi-Fi found; using default network")
            }

            val liveMediaItem = MediaItem.Builder()
                .setUri(url)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(streamProfile.liveTargetOffsetMs)
                        .setMinPlaybackSpeed(streamProfile.minPlaybackSpeed)
                        .setMaxPlaybackSpeed(streamProfile.maxPlaybackSpeed)
                        .build()
                )
                .build()
            val mediaSource = rtspFactory.createMediaSource(liveMediaItem)
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            awaitCancellation()
        } catch (cancelled: CancellationException) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
            onLog("Unbinding process from network due to session cancellation")
            throw cancelled
        } catch (t: Throwable) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.bindProcessToNetwork(null)
            onLog("Unbinding process from network due to error: ${t.message}")
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
