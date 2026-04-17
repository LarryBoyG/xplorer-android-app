package com.example.xirolite

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.xirolite.data.CommandResult
import com.example.xirolite.data.LegacyCompatibilityCatalog
import com.example.xirolite.data.LegacyDroneProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val host = intent.getStringExtra(EXTRA_HOST).orEmpty().ifBlank { "192.168.1.254" }
        val profile = LegacyCompatibilityCatalog.byId(intent.getStringExtra(EXTRA_PROFILE_ID))
        val selectedHudItems = intent.getStringArrayListExtra(EXTRA_HUD_ITEMS)?.toSet()
            ?: getSharedPreferences("xiro_lite_ui", MODE_PRIVATE)
                .getStringSet(LIVE_VIEW_HUD_PREF_KEY, DEFAULT_LIVE_VIEW_HUD_ITEMS)
                ?.toSet()
            ?: DEFAULT_LIVE_VIEW_HUD_ITEMS
        setContent {
            val xiroGreen = Color(0xFF6BD224)
            val scheme = darkColorScheme(
                primary = xiroGreen,
                secondary = xiroGreen,
                tertiary = xiroGreen,
                background = Color(0xFF2B2F39),
                surface = Color(0xFF3A3F4A),
                surfaceVariant = Color(0xFF252A31),
                onPrimary = Color.Black,
                onBackground = Color(0xFFF2F4F7),
                onSurface = Color(0xFFF2F4F7),
                onSurfaceVariant = Color(0xFFE9EDF2)
            )
            MaterialTheme(colorScheme = scheme) {
                CameraViewerScreen(
                    host = host,
                    profile = profile,
                    selectedHudItems = selectedHudItems,
                    onExit = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_HUD_ITEMS = "extra_hud_items"
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CameraViewerScreen(
    host: String,
    profile: LegacyDroneProfile,
    selectedHudItems: Set<String>,
    onExit: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val cameraMediaController = remember(profile) { CameraMediaController(profile) }
    val context = LocalContext.current
    val detector = remember { DroneNetworkDetector(context) }
    val localLibraryManager = remember { LocalLibraryManager(context) }
    val hjLogRecorder = remember { HjLogRecorder(localLibraryManager) }
    val exportHelper = remember { ExportHelper(context) }
    val captureFeedback = remember { CaptureFeedback(context) }
    val rtspController = remember { RtspPlayerController() }
    val relayProbe = remember { RelayProbe() }
    val rtspUrl = profile.primaryRtspUrl(host)
    val networkInfo = detector.detect()
    val relayHost = remember(networkInfo, profile) {
        profile.relayCandidates.firstOrNull() ?: "192.168.2.254"
    }
    val telemetryProbe = remember { TelemetryProbe() }
    val liveTelemetryPackets by LiveFlightTelemetryHub.recentPackets.collectAsState()
    val derivedFlightTelemetry by LiveFlightTelemetryHub.derivedTelemetry.collectAsState()
    var hjLogStatus by remember { mutableStateOf("Idle") }
    var relayProbeResults by remember { mutableStateOf(listOf<CommandResult>()) }
    var last3014Result by remember { mutableStateOf<TelemetryResult?>(null) }
    val viewerUiState = BetaInference.buildUiState(
        networkInfo = networkInfo,
        telemetryResults = last3014Result?.let { listOf(it) } ?: emptyList(),
        watch3014Summary = null,
        recentRemotePackets = liveTelemetryPackets,
        derivedFlightTelemetry = derivedFlightTelemetry,
        relayProbeResults = relayProbeResults,
        flightLogStatusText = hjLogStatus
    )
    val viewerHudItems = viewerUiState.preflight.filter { it.label in selectedHudItems }

    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartedAtMs by remember { mutableLongStateOf(0L) }
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    var streamProfile by remember(profile.id) { mutableStateOf(StreamProfile.AUTO) }
    var reloadToken by remember { mutableIntStateOf(0) }
    var playerActive by remember { mutableStateOf(true) }
    var recoveryMessage by remember { mutableStateOf<String?>(null) }
    var lastOperation by remember { mutableStateOf<CameraOperationResult?>(null) }
    var debugExpanded by remember { mutableStateOf(false) }
    var actionObservationInProgress by remember { mutableStateOf(false) }
    var actionExportStatus by remember { mutableStateOf("") }
    var exitInProgress by remember { mutableStateOf(false) }
    var lastAutoReloadAtMs by remember { mutableLongStateOf(0L) }
    var lastLoggedPacketTimestamp by remember { mutableLongStateOf(0L) }
    var recoveryFrameBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var autoRecoveryPending by remember { mutableStateOf(false) }
    var autoRecoveryMessageToken by remember { mutableIntStateOf(0) }
    var lastRecoveryWasPreemptive by remember { mutableStateOf(false) }

    val logs = remember { mutableStateListOf<String>() }
    val uiPrefs = remember { context.getSharedPreferences("xiro_lite_ui", android.content.Context.MODE_PRIVATE) }
    val debugModeEnabled = uiPrefs.getBoolean("debug_mode_enabled", false)
    val supportsStabilityToggle = profile.id == LegacyCompatibilityCatalog.xplorer.id
    val viewerWarnings = buildList {
        addAll(BetaInference.buildFlightWarnings(liveTelemetryPackets))
        if (autoRecoveryPending && !lastRecoveryWasPreemptive && recoveryMessage != null) {
            add(
                FlightWarning(
                    title = "Camera disconnected",
                    detail = "Retrying the live feed automatically until the camera responds.",
                    severity = FlightWarningSeverity.CAUTION
                )
            )
        }
    }

    fun startRecordingState() {
        isRecording = true
        recordingStartedAtMs = System.currentTimeMillis()
        recordingElapsedMs = 0L
    }

    fun clearRecordingState() {
        isRecording = false
        recordingStartedAtMs = 0L
        recordingElapsedMs = 0L
    }

    fun log(message: String) {
        logs.add(0, "${cameraTimestamp(System.currentTimeMillis())}  $message")
        while (logs.size > 80) logs.removeAt(logs.lastIndex)
    }

    fun clearAutoRecoveryOverlay(reason: String) {
        if (!autoRecoveryPending && recoveryFrameBitmap == null) return
        autoRecoveryPending = false
        recoveryFrameBitmap = null
        recoveryMessage = null
        lastRecoveryWasPreemptive = false
        log(reason)
    }

    fun handleAutomaticStreamRecovery(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastAutoReloadAtMs < 15_000L) return
        lastAutoReloadAtMs = now
        val isPreemptiveRefresh = reason.contains("preemptive", ignoreCase = true)
        lastRecoveryWasPreemptive = isPreemptiveRefresh
        recoveryFrameBitmap = rtspController.captureFrame()?.copy(Bitmap.Config.ARGB_8888, false)
        autoRecoveryPending = true
        autoRecoveryMessageToken += 1
        val messageToken = autoRecoveryMessageToken
        recoveryMessage = null
        log("RTSP session watcher: $reason")
        reloadToken += 1
        scope.launch {
            delay(2_000)
            if (!autoRecoveryPending || autoRecoveryMessageToken != messageToken) return@launch
            recoveryMessage = if (isPreemptiveRefresh) {
                "Refreshing live feed before the camera RTSP session expires..."
            } else {
                "Live feed stalled. Reloading stream..."
            }
        }
    }

    fun buildViewerActionExportText(
        actionLabel: String,
        startedAt: Long,
        finishedAt: Long,
        logWindow: List<String>,
        operation: CameraOperationResult?
    ): String = buildString {
        val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        appendLine("XIRO Lite Viewer Action Export")
        appendLine("========================================")
        appendLine("Action: $actionLabel")
        appendLine("Started: ${formatter.format(Date(startedAt))}")
        appendLine("Finished: ${formatter.format(Date(finishedAt))}")
        appendLine("Observed Window: ${String.format(Locale.US, "%.1f", (finishedAt - startedAt) / 1000.0)}s")
        appendLine("Host: $host")
        appendLine("Profile: ${profile.displayName}")
        appendLine("RTSP URL: $rtspUrl")
        appendLine("Mode At Export: ${captureMode.name}")
        appendLine("Recording At Export: $isRecording")
        appendLine("Storage: ${localLibraryManager.activeRootPath()}")
        appendLine("Flight Log: $hjLogStatus")
        appendLine()
        appendLine("LAST OPERATION")
        appendLine("----------------------------------------")
        if (operation == null) {
            appendLine("(none)")
        } else {
            appendLine("Title: ${operation.title}")
            appendLine("Summary: ${operation.summary}")
            operation.entries.forEachIndexed { index, entry ->
                appendLine("${index + 1}. ${entry.action}")
                appendLine("URL: ${entry.url}")
                appendLine("Status: ${entry.status}")
                if (entry.body.isNotBlank()) appendLine("Body: ${entry.body}")
                appendLine()
            }
        }
        appendLine("VIEWER LOG WINDOW")
        appendLine("----------------------------------------")
        if (logWindow.isEmpty()) appendLine("(none)") else logWindow.forEach { appendLine(it) }
    }

    suspend fun runObservedViewerAction(actionLabel: String, block: suspend () -> Unit) {
        if (actionObservationInProgress) {
            log(
                if (debugModeEnabled) {
                    "Viewer action skipped: another 20 second export window is still running"
                } else {
                    "Viewer action skipped: another camera action is still running"
                }
            )
            recoveryMessage = if (debugModeEnabled) {
                "Wait for the current 20 second export window to finish."
            } else {
                "Wait for the current camera action to finish."
            }
            return
        }
        actionObservationInProgress = true
        if (debugModeEnabled) {
            actionExportStatus = "Observing $actionLabel and preparing export..."
        } else {
            actionExportStatus = ""
        }
        log("Viewer action started: $actionLabel")
        try {
            if (!debugModeEnabled) {
                block()
                return
            }

            val startedAt = System.currentTimeMillis()
            val initialLogCount = logs.size
            block()
            val remainingMs = (20_000 - (System.currentTimeMillis() - startedAt)).coerceAtLeast(0)
            if (remainingMs > 0) {
                log("Viewer action watch window: keeping logs open for ${remainingMs / 1000}s more")
                delay(remainingMs)
            }
            val newLogCount = (logs.size - initialLogCount).coerceAtLeast(0)
            val logWindow = logs.take(newLogCount).reversed()
            val report = buildViewerActionExportText(
                actionLabel = actionLabel,
                startedAt = startedAt,
                finishedAt = System.currentTimeMillis(),
                logWindow = logWindow,
                operation = lastOperation
            )
            exportHelper.exportProbeResults(
                report,
                prefix = "xiro_viewer_${actionLabel.lowercase(Locale.US).replace(' ', '_')}"
            ).onSuccess { file ->
                actionExportStatus = "Viewer action export saved: ${file.absolutePath}"
                log(actionExportStatus)
            }.onFailure { error ->
                actionExportStatus = "Viewer action export failed: ${error.message}"
                log(actionExportStatus)
            }
        } finally {
            actionObservationInProgress = false
        }
    }

    suspend fun refreshLibrary(reason: String) {
        val state = cameraMediaController.loadLibrary(host)
        localLibraryManager.scanLocalItems(state.items)
        log("Library refresh ($reason): ${state.lastRefreshMessage}")
    }

    suspend fun cycleRtspSessionForCommand(actionLabel: String, commandBlock: suspend () -> Unit) {
        log("$actionLabel: stopping RTSP session before camera command")
        playerActive = false
        delay(450)
        commandBlock()
        delay(1400)
        reloadToken += 1
        playerActive = true
        log("$actionLabel: rebuilding RTSP session after camera command")
    }

    suspend fun waitForViewerHostRecovery(
        actionLabel: String,
        refreshLibraryAfterRecovery: Boolean = false
    ) {
        recoveryMessage = "$actionLabel: waiting for camera host recovery"
        log("$actionLabel: waiting for camera host recovery")
        val recovery = cameraMediaController.waitForCameraRecovery(host)
        lastOperation = recovery.operation
        log("${recovery.operation.title}: ${recovery.operation.summary}")
        if (recovery.recovered) {
            if (refreshLibraryAfterRecovery) {
                refreshLibrary(actionLabel.lowercase(Locale.US))
            }
            recoveryMessage = null
        } else {
            recoveryMessage = "Camera Wi-Fi is still recovering. Reconnect when the camera network returns."
        }
    }

    suspend fun restoreVideoModeAfterPhoto() {
        val restoreOp = cameraMediaController.setMode(host, CaptureMode.VIDEO)
        lastOperation = restoreOp
        log("Restore video mode after photo: ${restoreOp.summary}")
        val recovery = cameraMediaController.waitForCameraRecovery(
            host = host,
            maxWaitMs = 8_000,
            pollIntervalMs = 1_000
        )
        lastOperation = recovery.operation
        log("${recovery.operation.title}: ${recovery.operation.summary}")
    }

    suspend fun waitForCaptureRecovery(
        phase: String,
        refreshLibraryAfterRecovery: Boolean
    ) {
        log("$phase: waiting for camera host and Wi-Fi recovery")
        val recovery = cameraMediaController.waitForCameraRecovery(host)
        lastOperation = recovery.operation
        log("${recovery.operation.title}: ${recovery.operation.summary}")
        if (recovery.recovered) {
            if (refreshLibraryAfterRecovery) {
                refreshLibrary(phase)
            }
            reloadToken += 1
            delay(900)
            reloadToken += 1
            log("RTSP recovery reload after camera host returned")
            recoveryMessage = null
        } else {
            recoveryMessage = "Camera Wi-Fi is still recovering. Reconnect when the camera network returns."
        }
    }

    suspend fun showTransientViewerMessage(message: String, durationMs: Long = 1500) {
        recoveryMessage = message
        delay(durationMs)
        if (recoveryMessage == message) {
            recoveryMessage = null
        }
    }

    suspend fun runDebugCaptureCommand(
        command: DebugCaptureCommand,
        refreshLibraryAfterRecovery: Boolean
    ) {
        recoveryMessage = when (command) {
            DebugCaptureCommand.PHOTO_TRIGGER_ONLY ->
                "Debug photo trigger only... waiting for camera and Wi-Fi to return"
            DebugCaptureCommand.PHOTO_MODE_THEN_TRIGGER ->
                "Debug photo mode then trigger... waiting for camera and Wi-Fi to return"
            DebugCaptureCommand.VIDEO_START_ONLY ->
                "Debug video start only... waiting for camera and Wi-Fi to return"
            DebugCaptureCommand.VIDEO_STOP_ONLY ->
                "Debug video stop only... waiting for camera and Wi-Fi to return"
        }
        val operation = cameraMediaController.runDebugCaptureCommand(host, command)
        lastOperation = operation
        log("${operation.title}: ${operation.summary}")
        waitForCaptureRecovery(
            phase = command.title.lowercase(Locale.US),
            refreshLibraryAfterRecovery = refreshLibraryAfterRecovery
        )
    }

    suspend fun handlePrimaryCapturePress() {
        runObservedViewerAction(
            when {
                captureMode == CaptureMode.PHOTO -> "Shoot Photo"
                isRecording -> "Stop Video"
                else -> "Start Video"
            }
        ) {
            when {
                captureMode == CaptureMode.PHOTO -> {
                    cycleRtspSessionForCommand("Shoot Photo") {
                        val photoResult = cameraMediaController.capturePhotoPrimed(host)
                        lastOperation = photoResult.operation
                        log("${photoResult.operation.title}: ${photoResult.operation.summary}")
                        captureFeedback.playPhotoShutter()
                        log("Photo command sent after temporarily stopping RTSP with photo-mode priming")
                        waitForViewerHostRecovery(
                            actionLabel = "Shoot Photo",
                            refreshLibraryAfterRecovery = false
                        )
                        restoreVideoModeAfterPhoto()
                        showTransientViewerMessage("Photo command sent")
                    }
                }

                isRecording -> {
                    cycleRtspSessionForCommand("Stop Video") {
                        val op = cameraMediaController.triggerPrimaryAction(host, CaptureMode.VIDEO, true)
                        lastOperation = op
                        log("${op.title}: ${op.summary}")
                        captureFeedback.playRecordStopBeep()
                        if (op.success) clearRecordingState()
                        log("Stop video sent after temporarily stopping RTSP")
                        waitForViewerHostRecovery(
                            actionLabel = "Stop Video",
                            refreshLibraryAfterRecovery = true
                        )
                        showTransientViewerMessage("Stop video command sent")
                    }
                }

                else -> {
                    cycleRtspSessionForCommand("Start Video") {
                        val op = cameraMediaController.triggerPrimaryAction(host, CaptureMode.VIDEO, false)
                        lastOperation = op
                        log("${op.title}: ${op.summary}")
                        captureFeedback.playRecordBeep()
                        if (op.success) startRecordingState()
                        log("Start video sent after temporarily stopping RTSP")
                        showTransientViewerMessage("Start video command sent")
                    }
                }
            }
        }
    }

    fun exportDebugLog() {
        val report = buildString {
            appendLine("XIRO Lite Camera Viewer Debug Export")
            appendLine("Timestamp: ${cameraTimestamp(System.currentTimeMillis())}")
            appendLine("Host: $host")
            appendLine("Profile: ${profile.displayName}")
            appendLine("RTSP URL: $rtspUrl")
            appendLine("Mode: ${captureMode.name}")
            appendLine("Recording: $isRecording")
            appendLine("Storage: ${localLibraryManager.activeRootPath()}")
            appendLine()
            lastOperation?.let {
                appendLine("Last Operation: ${it.title}")
                appendLine("Summary: ${it.summary}")
                it.entries.forEach { entry ->
                    appendLine("- ${entry.action} | ${entry.status} | ${entry.url}")
                }
                appendLine()
            }
            appendLine("Viewer Logs:")
            logs.forEach { appendLine(it) }
        }
        exportHelper.exportProbeResults(report, prefix = "xiro_viewer_debug")
            .onSuccess { file -> log("Debug export saved: ${file.absolutePath}") }
            .onFailure { error -> log("Debug export failed: ${error.message}") }
    }

    suspend fun requestViewerExit() {
        if (exitInProgress) return
        if (actionObservationInProgress) {
            recoveryMessage = "Wait for the current camera action to finish."
            log("Viewer exit delayed: camera action still in progress")
            return
        }

        exitInProgress = true
        try {
            if (isRecording) {
                recoveryMessage = "Stopping video before exit..."
                log("Viewer exit requested while recording; stopping video first")
                playerActive = false
                delay(450)
                val stopOp = cameraMediaController.triggerPrimaryAction(host, CaptureMode.VIDEO, true)
                lastOperation = stopOp
                log("${stopOp.title}: ${stopOp.summary}")
                if (stopOp.success) {
                    captureFeedback.playRecordStopBeep()
                    clearRecordingState()
                    val recovery = cameraMediaController.waitForCameraRecovery(
                        host = host,
                        maxWaitMs = 12_000,
                        pollIntervalMs = 1_200
                    )
                    lastOperation = recovery.operation
                    log("${recovery.operation.title}: ${recovery.operation.summary}")
                    if (recovery.recovered) {
                        refreshLibrary("stop video before exit")
                    }
                } else {
                    log("Stop video before exit did not confirm success; exiting anyway")
                }
            }
            onExit()
        } finally {
            exitInProgress = false
        }
    }

    LaunchedEffect(Unit) {
        LiveFlightTelemetryHub.start()
        lastLoggedPacketTimestamp = liveTelemetryPackets.firstOrNull()?.timestampMs ?: 0L
        val hjState = hjLogRecorder.startSession("LIVEVIEW")
        hjLogStatus = hjState.statusText
        log("Flight log started: ${hjState.activeFile?.absolutePath ?: hjState.statusText}")
        log("Camera viewer opened")
        captureMode = CaptureMode.PHOTO
        clearRecordingState()
        log("Viewer opened with PHOTO selected by default")
        if (profile.id == LegacyCompatibilityCatalog.xplorer.id) {
            delay(250)
            log("Syncing XIRO Xplorer camera clock with local device time")
            val syncResult = cameraMediaController.syncClock(host)
            lastOperation = syncResult
            log("${syncResult.title}: ${syncResult.summary}")
        }
    }

    LaunchedEffect(isRecording, recordingStartedAtMs) {
        if (!isRecording || recordingStartedAtMs <= 0L) {
            recordingElapsedMs = 0L
            return@LaunchedEffect
        }
        while (isRecording) {
            recordingElapsedMs = (System.currentTimeMillis() - recordingStartedAtMs).coerceAtLeast(0L)
            delay(250)
        }
    }

    LaunchedEffect(liveTelemetryPackets.firstOrNull()?.timestampMs) {
        val latestPacket = liveTelemetryPackets.firstOrNull() ?: return@LaunchedEffect
        if (!hjLogRecorder.currentState().isActive) return@LaunchedEffect
        if (latestPacket.timestampMs == lastLoggedPacketTimestamp) return@LaunchedEffect
        lastLoggedPacketTimestamp = latestPacket.timestampMs
        hjLogStatus = hjLogRecorder.appendPacket(latestPacket).statusText
    }

    LaunchedEffect(networkInfo.localIp, relayHost) {
        if (networkInfo.localIp?.startsWith("192.168.2.") != true) return@LaunchedEffect
        while (true) {
            relayProbeResults = relayProbe.probeSettingsRoot(relayHost)
            delay(8_000)
        }
    }

    LaunchedEffect(networkInfo.localIp, host) {
        if (!networkInfo.localIp.isNullOrBlank()) {
            while (true) {
                last3014Result = telemetryProbe.httpGet(host, "cmd=3014", "CMD 3014")
                delay(5_000)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            relayProbe.closeSession()
        }
    }

    LaunchedEffect(autoRecoveryPending, playerActive, reloadToken) {
        if (!autoRecoveryPending || !playerActive) return@LaunchedEffect
        while (autoRecoveryPending && playerActive) {
            delay(8_000)
            if (!autoRecoveryPending || !playerActive) break
            if (!lastRecoveryWasPreemptive) {
                recoveryMessage = "Camera link interrupted. Retrying live feed..."
            }
            log("Automatic RTSP recovery still pending; retrying the live feed again")
            reloadToken += 1
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            val state = hjLogRecorder.stopSession()
            state.lastSavedFile?.let { file ->
                Log.d("CameraViewerActivity", "Flight log saved: ${file.absolutePath}")
            }
        }
    }

    BackHandler(enabled = !exitInProgress) {
        scope.launch { requestViewerExit() }
    }

    CameraFullScreenSystemBars(enabled = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (playerActive) {
            RtspPlayer(
                url = rtspUrl,
                reloadToken = reloadToken,
                streamProfile = streamProfile,
                modifier = Modifier.fillMaxSize(),
                controller = rtspController,
                onFirstFrameRendered = {
                    if (autoRecoveryPending) {
                        clearAutoRecoveryOverlay("RTSP stream resumed after automatic reload")
                    }
                },
                onPlaybackResumed = {
                    if (autoRecoveryPending) {
                        clearAutoRecoveryOverlay("RTSP playback resumed after automatic reload")
                    }
                },
                onRecoveryRequested = { reason -> handleAutomaticStreamRecovery(reason) },
                onLog = ::log
            )
        }

        recoveryFrameBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            IconButton(
                onClick = { scope.launch { requestViewerExit() } },
                modifier = Modifier
                    .background(Color(0xB3252A31), RoundedCornerShape(16.dp))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        viewerHudItems.forEach { item ->
                            ViewerHudPill(item = item)
                        }
                        if (supportsStabilityToggle) {
                            Button(
                                onClick = {
                                    streamProfile = if (streamProfile == StreamProfile.STABILITY) {
                                        StreamProfile.AUTO
                                    } else {
                                        StreamProfile.STABILITY
                                    }
                                    log("Live preview stream profile set to ${streamProfile.label}")
                                },
                                shape = RoundedCornerShape(18.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E242C), contentColor = Color.White)
                            ) {
                                Text("Stream: ${streamProfile.label}")
                            }
                        }
                    }
                }
                if (debugModeEnabled) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Button(
                            onClick = { exportDebugLog() },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E242C), contentColor = Color.White)
                        ) { Text("Export Debug Log") }
                        Button(
                            onClick = { debugExpanded = !debugExpanded },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xB3252A31), contentColor = Color.White)
                        ) { Text(if (debugExpanded) "Debug ^" else "Debug v") }
                    }
                }
            }
        }

        if (viewerWarnings.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 74.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                viewerWarnings.forEach { warning ->
                    ViewerWarningBanner(warning)
                }
            }
        }

        if (debugModeEnabled && actionExportStatus.isNotBlank()) {
            Card(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = if (viewerWarnings.isEmpty()) 72.dp else 150.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC1E242C))
            ) {
                Text(
                    text = actionExportStatus,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    color = Color.White
                )
            }
        }

        recoveryMessage?.let {
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xCC1E242C))
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    color = Color.White
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xA012151B)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "PHOTO",
                        color = if (captureMode == CaptureMode.PHOTO) Color.White else Color(0x88FFFFFF),
                        fontWeight = if (captureMode == CaptureMode.PHOTO) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        "VIDEO",
                        color = if (captureMode == CaptureMode.VIDEO) Color.White else Color(0x88FFFFFF),
                        fontWeight = if (captureMode == CaptureMode.VIDEO) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            ViewerCaptureShutterButton(
                captureMode = captureMode,
                isRecording = isRecording,
                recordingElapsedMs = recordingElapsedMs,
                enabled = !actionObservationInProgress,
                onClick = {
                    scope.launch { handlePrimaryCapturePress() }
                }
            )

            Button(
                onClick = {
                    scope.launch {
                        runObservedViewerAction(
                            if (captureMode == CaptureMode.PHOTO) "Switch To Video" else "Switch To Photo"
                        ) {
                            val targetMode = if (captureMode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO
                            if (targetMode == CaptureMode.VIDEO) {
                                lastOperation = cameraMediaController.setMode(host, CaptureMode.VIDEO)
                            } else {
                                log("Switch to photo keeps the UI in PHOTO mode without sending 3001 par=0")
                            }
                            captureMode = targetMode
                            clearRecordingState()
                            log("Mode changed to ${captureMode.name}")
                            showTransientViewerMessage("Mode command sent")
                        }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                enabled = !actionObservationInProgress && !isRecording,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC1E242C), contentColor = Color.White)
            ) {
                Text(if (captureMode == CaptureMode.PHOTO) "Switch to Video" else "Switch to Photo")
            }
        }

        AnimatedVisibility(
            visible = debugModeEnabled && debugExpanded,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 18.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(0.62f),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(14.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("XIRO Camera", color = Color(0xFFF2F4F7))
                    Text("Storage: ${localLibraryManager.activeRootPath()}", color = Color(0xFFD7DCE3))
                    Text("Flight Log: $hjLogStatus", color = Color(0xFFD7DCE3))
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Debug Capture Tests", color = Color(0xFFF2F4F7))
                    Text("Use these to isolate which direct camera command drops the link.", color = Color(0xFFD7DCE3))
                    Button(
                        onClick = {
                            scope.launch {
                                runDebugCaptureCommand(
                                    command = DebugCaptureCommand.PHOTO_TRIGGER_ONLY,
                                    refreshLibraryAfterRecovery = false
                                )
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC3A404B), contentColor = Color.White)
                    ) { Text("Photo Trigger Only (1001)") }
                    Text("Sends only the direct take-photo trigger without switching mode first.", color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            scope.launch {
                                runDebugCaptureCommand(
                                    command = DebugCaptureCommand.PHOTO_MODE_THEN_TRIGGER,
                                    refreshLibraryAfterRecovery = false
                                )
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC3A404B), contentColor = Color.White)
                    ) { Text("Photo Mode Then Trigger (3001 -> 1001)") }
                    Text("Switches to photo mode first, waits briefly, then sends the photo trigger.", color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            scope.launch {
                                runDebugCaptureCommand(
                                    command = DebugCaptureCommand.VIDEO_START_ONLY,
                                    refreshLibraryAfterRecovery = false
                                )
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC3A404B), contentColor = Color.White)
                    ) { Text("Video Start Only (2001 par=1)") }
                    Text("Sends only the direct video-start command with no extra prep sequence.", color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                    Button(
                        onClick = {
                            scope.launch {
                                runDebugCaptureCommand(
                                    command = DebugCaptureCommand.VIDEO_STOP_ONLY,
                                    refreshLibraryAfterRecovery = false
                                )
                            }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xCC3A404B), contentColor = Color.White)
                    ) { Text("Video Stop Only (2001 par=0)") }
                    Text("Sends only the direct video-stop command to test stop behavior in isolation.", color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                    lastOperation?.let {
                        Text(it.title, color = Color(0xFFF2F4F7))
                        Text(it.summary, color = Color(0xFFD7DCE3))
                    }
                    if (logs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        logs.take(8).forEach { line ->
                            Text(line, color = Color(0xFFD7DCE3))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerCaptureShutterButton(
    captureMode: CaptureMode,
    isRecording: Boolean,
    recordingElapsedMs: Long,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (enabled && isPressed) 0.92f else 1f,
        label = "viewerCaptureShutterScale"
    )
    val outerRingColor = Color.White.copy(alpha = if (enabled) 0.96f else 0.42f)
    val outerShellColor = if (captureMode == CaptureMode.PHOTO) {
        Color.White.copy(alpha = if (enabled) 0.14f else 0.08f)
    } else {
        Color.White.copy(alpha = if (enabled) 0.18f else 0.08f)
    }
    val buttonSize = 96.dp
    val innerSize = if (captureMode == CaptureMode.PHOTO) 80.dp else 82.dp
    val innerColor = if (captureMode == CaptureMode.PHOTO) {
        Color.White.copy(alpha = if (enabled) 1f else 0.5f)
    } else {
        Color(0xFFE43232).copy(alpha = if (enabled) 1f else 0.5f)
    }

    Box(
        modifier = Modifier
            .size(buttonSize)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(3.dp, outerRingColor, CircleShape)
                .background(outerShellColor, CircleShape)
        )

        Box(
            modifier = Modifier
                .size(innerSize)
                .border(
                    width = if (captureMode == CaptureMode.PHOTO) 1.25.dp else 0.dp,
                    color = Color.White.copy(alpha = if (enabled) 0.55f else 0.28f),
                    shape = CircleShape
                )
                .background(innerColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (captureMode == CaptureMode.VIDEO && isRecording) {
                Text(
                    text = formatRecordingDuration(recordingElapsedMs),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }
    }
}

private fun formatRecordingDuration(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}

@Composable
private fun ViewerHudPill(item: PreflightItem) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x9912151B)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.label == "Wi-Fi Signal") {
                Text("Wi-Fi", color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                SignalStrengthBars(level = parseSignalStrengthVisual(item.value)?.level ?: 0)
            } else {
                StatusDot(level = resolveStatusIndicatorLevel(item.label, item.value, item.ok), size = 8.dp)
                Text(item.label, color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                Text(item.value, color = Color.White, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ViewerWarningBanner(warning: FlightWarning) {
    val containerColor = when (warning.severity) {
        FlightWarningSeverity.INFO -> Color(0xCC274152)
        FlightWarningSeverity.CAUTION -> Color(0xCC5C4300)
        FlightWarningSeverity.CRITICAL -> Color(0xCC5C1F1F)
    }
    val accentColor = when (warning.severity) {
        FlightWarningSeverity.INFO -> Color(0xFF64B5F6)
        FlightWarningSeverity.CAUTION -> Color(0xFFFFC107)
        FlightWarningSeverity.CRITICAL -> Color(0xFFFF6E6E)
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(accentColor, CircleShape)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(warning.title, color = Color.White)
                Text(warning.detail, color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CameraFullScreenSystemBars(enabled: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view) ?: return@SideEffect
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.BLACK
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.BLACK
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = false
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (enabled) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }
}

private fun cameraTimestamp(timestampMs: Long): String =
    SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestampMs))
