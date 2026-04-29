package com.example.xirolite

import android.Manifest
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Settings
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
        val initialStreamProfile = intent.getStringExtra(EXTRA_INITIAL_STREAM_PROFILE)
            ?.let { raw -> StreamProfile.entries.firstOrNull { it.name == raw } }
            ?: StreamProfile.AUTO
        val autoDebugCapture = intent.getBooleanExtra(EXTRA_AUTO_DEBUG_CAPTURE, false)
        val selectedHudItems = intent.getStringArrayListExtra(EXTRA_HUD_ITEMS)?.toSet()
            ?: getSharedPreferences(UI_PREFS_NAME, MODE_PRIVATE)
                .getStringSet(LIVE_VIEW_HUD_PREF_KEY, DEFAULT_LIVE_VIEW_HUD_ITEMS)
                ?.toSet()
            ?: DEFAULT_LIVE_VIEW_HUD_ITEMS
        setContent {
            MaterialTheme(colorScheme = xiroColorScheme()) {
                CameraViewerScreen(
                    host = host,
                    profile = profile,
                    initialStreamProfile = initialStreamProfile,
                    autoDebugCapture = autoDebugCapture,
                    selectedHudItems = normalizeHudSelection(selectedHudItems),
                    onExit = { finish() }
                )
            }
        }
    }

    companion object {
        const val EXTRA_HOST = "extra_host"
        const val EXTRA_PROFILE_ID = "extra_profile_id"
        const val EXTRA_HUD_ITEMS = "extra_hud_items"
        const val EXTRA_INITIAL_STREAM_PROFILE = "extra_initial_stream_profile"
        const val EXTRA_AUTO_DEBUG_CAPTURE = "extra_auto_debug_capture"
    }
}

private enum class ViewerCameraSettingsTab(val title: String) {
    CAMERA_PARAMETER("Camera Parameter"),
    PICTURE_PARAMETER("Picture Parameter"),
    PHOTOGRAPH_SETTING("Photograph Setting"),
    SYSTEM_SETTINGS("System settings")
}

private enum class ViewerCameraSettingEvidence(val label: String) {
    VERIFIED("Capture verified"),
    OBSERVED("Capture observed"),
    PENDING("Command pending")
}

private enum class ViewerInteractiveSettingKey {
    PREVIEW_RESOLUTION,
    IMAGE_RESOLUTION,
    ANTI_BLINK
}

private data class ViewerCameraSettingRow(
    val label: String,
    val currentValue: String,
    val options: List<String> = emptyList(),
    val selectedOption: String = currentValue,
    val evidence: ViewerCameraSettingEvidence = ViewerCameraSettingEvidence.PENDING,
    val note: String = "",
    val actionKey: ViewerInteractiveSettingKey? = null
)

private fun viewerCameraSettingsFor(
    tab: ViewerCameraSettingsTab,
    previewResolution: String = "240p",
    imageResolution: String = "4320x3240",
    antiBlink: String = "50HZ"
): List<ViewerCameraSettingRow> =
    when (tab) {
        ViewerCameraSettingsTab.CAMERA_PARAMETER -> listOf(
            ViewerCameraSettingRow(
                label = "ISO",
                currentValue = "Auto",
                options = listOf("Auto", "100", "200", "400", "800", "1600"),
                note = "Legacy app default observed in Live View. On-wire command mapping is still pending."
            ),
            ViewerCameraSettingRow(
                label = "Exposure",
                currentValue = "+0",
                options = listOf("+2.0", "+1.7", "+1.3", "+1.0", "+0.7", "+0.3", "+0", "-0.3", "-0.7", "-1.0", "-1.3", "-1.7", "-2.0"),
                note = "Legacy app default observed in Live View. On-wire command mapping is still pending."
            ),
            ViewerCameraSettingRow(
                label = "White Balance",
                currentValue = "Auto",
                options = listOf("Auto", "Cloudy", "Daylight", "Flourescent", "Tungsten"),
                note = "Legacy app default observed in Live View. On-wire command mapping is still pending."
            ),
            ViewerCameraSettingRow(
                label = "Contrast",
                currentValue = "Standard",
                options = listOf("High", "Standard", "Low"),
                note = "Legacy app default observed in Live View. On-wire command mapping is still pending."
            ),
            ViewerCameraSettingRow(
                label = "Metering Mode",
                currentValue = "Average Metering",
                options = listOf("Center-Weighted", "Multi-Spots", "Spot-Metering", "Average Metering"),
                note = "Legacy app default observed in Live View. On-wire command mapping is still pending."
            ),
            ViewerCameraSettingRow(
                label = "Anti-blink",
                currentValue = antiBlink,
                options = listOf("50HZ", "60HZ"),
                selectedOption = antiBlink,
                evidence = ViewerCameraSettingEvidence.VERIFIED,
                note = "Capture verified. Legacy sends cmd 3080 with par 0 = 50HZ and par 1 = 60HZ. The live RTSP stream stays on the same SDP while Anti-blink changes.",
                actionKey = ViewerInteractiveSettingKey.ANTI_BLINK
            )
        )

        ViewerCameraSettingsTab.PICTURE_PARAMETER -> listOf(
            ViewerCameraSettingRow(
                label = "Preview Resolution",
                currentValue = previewResolution,
                options = listOf("480p", "360p", "240p"),
                selectedOption = previewResolution,
                evidence = ViewerCameraSettingEvidence.VERIFIED,
                note = "Capture verified. Legacy wraps cmd 2010 with cmd 2015 apply steps and fully rebuilds RTSP. Captured mapping: 480p = par 2, 360p = par 3, 240p = par 4. SDP sizes: 240p = 320x240, 360p = 640x360, 480p = 640x480.",
                actionKey = ViewerInteractiveSettingKey.PREVIEW_RESOLUTION
            ),
            ViewerCameraSettingRow(
                label = "Image Resolution",
                currentValue = imageResolution,
                options = listOf("4320x3240", "4032x3024", "3648x2736"),
                selectedOption = imageResolution,
                evidence = ViewerCameraSettingEvidence.OBSERVED,
                note = "Capture observed. Legacy sends cmd 1002 when this changes and still-photo resolution updates, but the live RTSP stream does not restart. Captured mapping: 4320x3240 = par 0, 4032x3024 = par 1, 3648x2736 = par 2.",
                actionKey = ViewerInteractiveSettingKey.IMAGE_RESOLUTION
            ),
            ViewerCameraSettingRow(
                label = "Image Format",
                currentValue = "JPEG",
                options = listOf("JPEG", "RAW", "JPEG+RAW"),
                note = "Legacy app default observed in Live View. Command mapping is still pending."
            ),
            ViewerCameraSettingRow(
                label = "Movie Resolution",
                currentValue = "1920x1080",
                options = listOf("1920x1080", "1280x720", "848x480", "640x480"),
                note = "Legacy UI exposes this separately from Preview Resolution. Recorded-movie command mapping is still pending."
            )
        )

        ViewerCameraSettingsTab.PHOTOGRAPH_SETTING -> listOf(
            ViewerCameraSettingRow(
                label = "Set interval timer",
                currentValue = "OFF",
                options = listOf("OFF", "1 sec", "2 sec", "5 sec", "10 sec", "30 sec", "60 sec"),
                note = "Legacy app default observed in Live View. Command mapping is still pending."
            ),
            ViewerCameraSettingRow(
                label = "Continuous shooting",
                currentValue = "OFF",
                options = listOf("OFF", "3", "4", "5"),
                note = "Legacy app default observed in Live View. Command mapping is still pending."
            ),
            ViewerCameraSettingRow(
                label = "Loop",
                currentValue = "OFF",
                options = listOf("OFF", "3 min", "5 min", "10 min"),
                note = "Legacy app default observed in Live View. Command mapping is still pending."
            )
        )

        ViewerCameraSettingsTab.SYSTEM_SETTINGS -> listOf(
            ViewerCameraSettingRow(
                label = "Camera Factory Reset",
                currentValue = "Reset Button",
                options = listOf("Reset"),
                evidence = ViewerCameraSettingEvidence.OBSERVED,
                note = "Capture observed. The legacy app issues cmd 3081 here, then follows with 3014/3012 polling and an observed cmd 2015 par=0 apply step. This stays disabled in XIRO Lite until the destructive flow is proven safely."
            ),
            ViewerCameraSettingRow(
                label = "Format SD card",
                currentValue = "Format Button",
                options = listOf("Format"),
                note = "Dangerous legacy action. Command mapping is intentionally left pending until it is proven safely."
            )
        )
    }

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CameraViewerScreen(
    host: String,
    profile: LegacyDroneProfile,
    initialStreamProfile: StreamProfile,
    autoDebugCapture: Boolean,
    selectedHudItems: Set<String>,
    onExit: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val cameraMediaController = remember(profile) { CameraMediaController(profile) }
    val cameraInitProbe = remember { CameraInitProbe() }
    val context = LocalContext.current
    val detector = remember { DroneNetworkDetector(context) }
    val localLibraryManager = remember { LocalLibraryManager(context) }
    val offlineMapManager = remember { OfflineMapManager(context, localLibraryManager) }
    val hjLogRecorder = remember { HjLogRecorder(localLibraryManager) }
    val exportHelper = remember { ExportHelper(context) }
    val captureFeedback = remember { CaptureFeedback(context) }
    val rootedLiveViewCaptureManager = remember { RootedLiveViewCaptureManager(context, localLibraryManager) }
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
    val remoteBatteryReading by RemoteBatteryHub.latestReading.collectAsState()
    val uiPrefs = remember { context.getSharedPreferences(UI_PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    val measurementUnit = remember {
        MeasurementUnit.fromStored(uiPrefs.getString(MEASUREMENT_UNIT_PREF_KEY, null))
    }
    var activeOfflineMap by remember { mutableStateOf(offlineMapManager.activeMapFile()) }
    var mapExpanded by remember { mutableStateOf(false) }
    var phoneLocationPermissionGranted by remember { mutableStateOf(hasPhoneLocationPermission(context)) }
    val requestPhoneLocationPermission = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        phoneLocationPermissionGranted = result.values.any { it }
    }
    val phoneFlightCoordinate by rememberPhoneFlightCoordinate(phoneLocationPermissionGranted)
    var hjLogStatus by remember { mutableStateOf("Idle") }
    var relayProbeResults by remember { mutableStateOf(listOf<CommandResult>()) }
    var cameraStorageResults by remember { mutableStateOf(listOf<TelemetryResult>()) }
    var telemetryNowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val viewerUiState = BetaInference.buildUiState(
        networkInfo = networkInfo,
        telemetryResults = cameraStorageResults,
        watch3014Summary = null,
        recentRemotePackets = liveTelemetryPackets,
        derivedFlightTelemetry = derivedFlightTelemetry,
        remoteBatteryReading = remoteBatteryReading,
        relayProbeResults = relayProbeResults,
        flightLogStatusText = hjLogStatus,
        measurementUnit = measurementUnit,
        nowMs = telemetryNowMs
    )
    val viewerHudItems = viewerUiState.preflight.filter { it.label in selectedHudItems }
    val usesLegacyXplorerLiveViewPatch = remember(profile.id) {
        profile.id == LegacyCompatibilityCatalog.xplorer.id ||
            profile.id == LegacyCompatibilityCatalog.xplorer4k.id
    }

    var captureMode by remember { mutableStateOf(CaptureMode.PHOTO) }
    var isRecording by remember { mutableStateOf(false) }
    var recordingStartedAtMs by remember { mutableLongStateOf(0L) }
    var recordingElapsedMs by remember { mutableLongStateOf(0L) }
    var streamProfile by remember(profile.id, initialStreamProfile) { mutableStateOf(initialStreamProfile) }
    var viewerSettingsPanelVisible by remember { mutableStateOf(false) }
    var viewerCameraSettingsTab by remember { mutableStateOf(ViewerCameraSettingsTab.CAMERA_PARAMETER) }
    var viewerPreviewResolution by remember { mutableStateOf("240p") }
    var viewerImageResolution by remember { mutableStateOf("4320x3240") }
    var viewerAntiBlink by remember { mutableStateOf("50HZ") }
    var reloadToken by remember { mutableIntStateOf(0) }
    var playerActive by remember(profile.id) {
        mutableStateOf(!usesLegacyXplorerLiveViewPatch)
    }
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
    var commandTransitionOverlayActive by remember { mutableStateOf(false) }
    var autoRecoveryMessageToken by remember { mutableIntStateOf(0) }
    var hardRtspRestartToken by remember { mutableIntStateOf(0) }
    var lastRecoveryWasPreemptive by remember { mutableStateOf(false) }
    var liveFrameRendered by remember { mutableStateOf(false) }
    var clockSyncAttempted by remember { mutableStateOf(false) }
    var automaticTcpFallbackUsed by remember { mutableStateOf(false) }
    var previewKickToken by remember { mutableIntStateOf(0) }
    var debugCaptureStatus by remember { mutableStateOf("") }

    val logs = remember { mutableStateListOf<String>() }
    val debugModeEnabled = uiPrefs.getBoolean("debug_mode_enabled", false)
    val supportsStabilityToggle = usesLegacyXplorerLiveViewPatch
    val viewerWarnings = buildList {
        addAll(
            BetaInference.buildFlightWarnings(
                recentRemotePackets = liveTelemetryPackets,
                measurementUnit = measurementUnit,
                nowMs = telemetryNowMs
            )
        )
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
        val rendered = "${cameraTimestamp(System.currentTimeMillis())}  $message"
        logs.add(0, rendered)
        while (logs.size > 80) logs.removeAt(logs.lastIndex)
        Log.d("XiroViewer", message)
    }

    suspend fun primeLegacyLiveView(reason: String) {
        if (!usesLegacyXplorerLiveViewPatch) return
        log("$reason: running XIRO Xplorer live-view init sequence")
        val initResults = cameraInitProbe.run(host)
        val initEntries = initResults.map { result ->
            CameraActionResult(
                action = result.label,
                url = result.url,
                status = result.status,
                body = result.preview
            )
        }
        val initSuccess = initResults.any { it.status.startsWith("HTTP 2") }
        val initSummary = if (initSuccess) {
            "Legacy live-view init completed with ${initResults.count { it.status.startsWith("HTTP") }} camera command response(s)."
        } else {
            "Legacy live-view init did not receive an HTTP response from the camera."
        }
        lastOperation = CameraOperationResult(
            title = "Live View Init",
            success = initSuccess,
            entries = initEntries,
            summary = initSummary
        )
        initResults.forEach { result ->
            log("${result.label}: ${result.status}")
        }
        delay(250)
    }

    fun scheduleLegacyPreviewKick(reason: String) {
        if (!usesLegacyXplorerLiveViewPatch) return
        previewKickToken += 1
        val scheduledToken = previewKickToken
        scope.launch {
            delay(550)
            if (
                scheduledToken != previewKickToken ||
                !playerActive ||
                isRecording
            ) {
                return@launch
            }
            clockSyncAttempted = true
            log("$reason: sending XIRO Xplorer post-PLAY live-view sequence")
            val previewPrep = cameraMediaController.runLegacyPostPlayPreviewSequence(host)
            lastOperation = previewPrep
            log("${previewPrep.title}: ${previewPrep.summary}")
        }
    }

    DisposableEffect(Unit) {
        LiveViewSessionRegistry.setViewerActive(true)
        onDispose {
            LiveViewSessionRegistry.setViewerActive(false)
        }
    }

    fun clearAutoRecoveryOverlay(reason: String) {
        if (!autoRecoveryPending && recoveryFrameBitmap == null) return
        autoRecoveryPending = false
        recoveryFrameBitmap = null
        recoveryMessage = null
        lastRecoveryWasPreemptive = false
        log(reason)
    }

    fun scheduleHardRtspRestart(reason: String, messageToken: Int) {
        hardRtspRestartToken += 1
        val restartToken = hardRtspRestartToken
        scope.launch {
            log("RTSP hard restart: $reason")
            playerActive = false
            delay(1_500)
            if (
                !autoRecoveryPending ||
                autoRecoveryMessageToken != messageToken ||
                hardRtspRestartToken != restartToken
            ) {
                return@launch
            }
            primeLegacyLiveView("RTSP hard restart")
            reloadToken += 1
            playerActive = true
            scheduleLegacyPreviewKick("RTSP hard restart")
            log("RTSP hard restart: player recreated")
        }
    }

    fun clearCommandTransitionOverlay(reason: String) {
        if (!commandTransitionOverlayActive) return
        commandTransitionOverlayActive = false
        if (!autoRecoveryPending) {
            recoveryFrameBitmap = null
        }
        log(reason)
    }

    fun handleAutomaticStreamRecovery(reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastAutoReloadAtMs < 15_000L) return
        lastAutoReloadAtMs = now
        val isPreemptiveRefresh = reason.contains("preemptive", ignoreCase = true)
        if (
            usesLegacyXplorerLiveViewPatch &&
            !liveFrameRendered &&
            !streamProfile.forceRtpTcp &&
            !automaticTcpFallbackUsed
        ) {
            automaticTcpFallbackUsed = true
            streamProfile = StreamProfile.STABILITY
            log("RTSP startup recovery: switching XIRO Xplorer to Stability (TCP interleaved) after no first frame arrived over legacy UDP")
        }
        lastRecoveryWasPreemptive = isPreemptiveRefresh
        recoveryFrameBitmap = rtspController.captureFrame()?.copy(Bitmap.Config.ARGB_8888, false)
        autoRecoveryPending = true
        autoRecoveryMessageToken += 1
        val messageToken = autoRecoveryMessageToken
        recoveryMessage = null
        log("RTSP session watcher: $reason")
        scheduleHardRtspRestart(reason, messageToken)
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

    LaunchedEffect(Unit) {
        while (true) {
            telemetryNowMs = System.currentTimeMillis()
            delay(1_000)
        }
    }

    fun ageText(timestampMs: Long): String {
        if (timestampMs <= 0L) return "--"
        val ageSeconds = (System.currentTimeMillis() - timestampMs).coerceAtLeast(0L) / 1000.0
        return String.format(Locale.US, "%.1fs ago", ageSeconds)
    }

    fun formatExportDouble(value: Double?, decimals: Int = 1, suffix: String = ""): String {
        value ?: return "--"
        val pattern = "%.${decimals}f"
        return String.format(Locale.US, pattern, value) + suffix
    }

    fun formatExportPercent(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f%%", it) } ?: "--"

    fun StringBuilder.appendViewerDiagnostics() {
        val snapshot = LegacyTelemetryDecoder.decode(liveTelemetryPackets)
        val latestPacket = liveTelemetryPackets.firstOrNull()
        val latestPacketAgeMs = latestPacket?.let { System.currentTimeMillis() - it.timestampMs }
        val telemetryFresh = latestPacketAgeMs != null && latestPacketAgeMs <= 5_000L
        appendLine("STREAM / NETWORK")
        appendLine("----------------------------------------")
        appendLine("Stream Profile: ${streamProfile.label}")
        appendLine("RTSP Transport: ${streamProfile.transportLabel}")
        appendLine("RTSP Timeout: ${streamProfile.timeoutMs} ms")
        appendLine("Network Local IP: ${networkInfo.localIp ?: "--"}")
        appendLine("Network Gateway: ${networkInfo.gatewayIp ?: "--"}")
        appendLine("Guessed Drone IP: ${networkInfo.guessedDroneIp ?: "--"}")
        appendLine("Phone Wi-Fi Signal: ${networkInfo.wifiSignalText}")
        if (networkInfo.notes.isNotEmpty()) appendLine("Network Notes: ${networkInfo.notes.joinToString("; ")}")
        appendLine()

        appendLine("LIVE TELEMETRY SNAPSHOT")
        appendLine("----------------------------------------")
        appendLine("Recent UDP Packets: ${liveTelemetryPackets.size}")
        appendLine("Latest UDP Age: ${latestPacket?.timestampMs?.let(::ageText) ?: "--"}")
        appendLine("Telemetry Fresh: ${if (telemetryFresh) "yes" else "no"}")
        appendLine("GPS Sat: ${viewerUiState.droneState.satelliteText}")
        appendLine("Flight Mode: ${viewerUiState.droneState.flightModeText}")
        appendLine("Aircraft Power: ${viewerUiState.droneState.droneBatteryText}")
        appendLine("Aircraft Voltage: ${if (telemetryFresh) formatExportDouble(snapshot?.droneVoltageVolts, 2, " V") else "Stale"}")
        appendLine("Gear: ${viewerUiState.droneState.gearText}")
        appendLine("Baro HGT / Elevation: ${viewerUiState.droneState.baroHeightText}")
        appendLine("Target HGT / Target Elevation: ${viewerUiState.droneState.targetHeightText}")
        appendLine("Distance From Home: ${viewerUiState.droneState.distanceText}")
        appendLine("Speed: ${viewerUiState.droneState.speedText}")
        appendLine("Remote Control Alarm UDP[77]: ${if (telemetryFresh) snapshot?.remoteControlAlarm?.let { "0x%02X".format(it) } ?: "--" else "Stale"}")
        appendLine("Relay-to-Camera Signal: ${viewerUiState.droneState.relaySignalText}")
        appendLine("SD Card: ${viewerUiState.droneState.sdCardText}")
        appendLine(
            "Remote Battery: " + (remoteBatteryReading?.let {
                "${it.percent}% raw=${it.raw} age=${ageText(it.timestampMs)} response=${it.responseHex}"
            } ?: "--")
        )
        appendLine("Flight Log: $hjLogStatus")
        appendLine()

        appendLine("RECENT UDP 6800 PACKETS")
        appendLine("----------------------------------------")
        if (liveTelemetryPackets.isEmpty()) {
            appendLine("(none captured)")
        } else {
            liveTelemetryPackets.take(12).forEachIndexed { index, packet ->
                val packetSnapshot = LegacyTelemetryDecoder.decode(packet)
                val changedText = packet.changedByteIndexes.take(16).joinToString(", ")
                    .ifBlank { "(none / first packet)" }
                appendLine(
                    "#${index + 1} ${cameraTimestamp(packet.timestampMs)} age=${ageText(packet.timestampMs)} " +
                        "src=${packet.sourceIp}:${packet.sourcePort} len=${packet.length} " +
                        "state=${packet.stateGuess?.label ?: "Unknown"}"
                )
                appendLine(
                    "  sats=${packetSnapshot?.satellites ?: "--"} " +
                        "power=${packetSnapshot?.droneBatteryPercentExact?.let(::formatExportPercent) ?: "--"} " +
                        "voltage=${formatExportDouble(packetSnapshot?.droneVoltageVolts, 2, " V")} " +
                        "baro=${formatExportDouble(packetSnapshot?.baroHeightMeters, 1, " m")} " +
                        "target=${formatExportDouble(packetSnapshot?.targetHeightMeters, 1, " m")} " +
                        "gear=${packetSnapshot?.gearSelection ?: "--"} " +
                        "alarm=${packetSnapshot?.remoteControlAlarm?.let { "0x%02X".format(it) } ?: "--"}"
                )
                appendLine("  changed=$changedText")
                appendLine("  hex=${packet.hexPreview}")
            }
        }
        appendLine()

        appendLine("STATUS HUD SNAPSHOT")
        appendLine("----------------------------------------")
        viewerUiState.preflight.forEach { item ->
            appendLine("- ${item.label}: ${item.value} (${if (item.ok) "ok" else "attention"}) ${item.detail}")
        }
        appendLine()
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
        appendViewerDiagnostics()
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
        recoveryFrameBitmap = rtspController.captureFrame()?.copy(Bitmap.Config.ARGB_8888, false)
        commandTransitionOverlayActive = recoveryFrameBitmap != null
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

    fun CameraOperationResult.acceptedByCamera(): Boolean =
        entries.any { it.status.startsWith("HTTP 200") }

    suspend fun applyPreviewResolution(option: String) {
        if (viewerPreviewResolution == option) return
        runObservedViewerAction("Preview Resolution $option") {
            cycleRtspSessionForCommand("Preview Resolution") {
                val operation = cameraMediaController.setPreviewResolution(host, option)
                lastOperation = operation
                log("${operation.title}: ${operation.summary}")
                if (operation.acceptedByCamera()) {
                    viewerPreviewResolution = option
                    log("Preview resolution updated to $option")
                    showTransientViewerMessage(
                        if (debugModeEnabled) {
                            "Preview resolution command sent"
                        } else {
                            "Preview Resolution $option"
                        }
                    )
                } else {
                    showTransientViewerMessage("Preview resolution command failed")
                }
            }
        }
    }

    suspend fun applyImageResolution(option: String) {
        if (viewerImageResolution == option) return
        runObservedViewerAction("Image Resolution $option") {
            val operation = cameraMediaController.setImageResolution(host, option)
            lastOperation = operation
            log("${operation.title}: ${operation.summary}")
            if (operation.acceptedByCamera()) {
                viewerImageResolution = option
                log("Image resolution updated to $option")
                showTransientViewerMessage(
                    if (debugModeEnabled) {
                        "Image resolution command sent"
                    } else {
                        "Image Resolution $option"
                    }
                )
            } else {
                showTransientViewerMessage("Image resolution command failed")
            }
        }
    }

    suspend fun applyAntiBlink(option: String) {
        if (viewerAntiBlink == option) return
        runObservedViewerAction("Anti-blink $option") {
            val operation = cameraMediaController.setAntiBlink(host, option)
            lastOperation = operation
            log("${operation.title}: ${operation.summary}")
            if (operation.acceptedByCamera()) {
                viewerAntiBlink = option
                log("Anti-blink updated to $option")
                showTransientViewerMessage(
                    if (debugModeEnabled) {
                        "Anti-blink command sent"
                    } else {
                        "Anti-blink $option"
                    }
                )
            } else {
                showTransientViewerMessage("Anti-blink command failed")
            }
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
                        showTransientViewerMessage(
                            if (debugModeEnabled) "Photo command sent" else "Taking Picture"
                        )
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
                        showTransientViewerMessage(
                            if (debugModeEnabled) "Stop video command sent" else "Stopping Record"
                        )
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
                        showTransientViewerMessage(
                            if (debugModeEnabled) "Start video command sent" else "Starting Record"
                        )
                    }
                }
            }
        }
    }

    suspend fun switchViewerCaptureMode(targetMode: CaptureMode) {
        if (captureMode == targetMode) return
        if (actionObservationInProgress || isRecording) return
        runObservedViewerAction(
            if (targetMode == CaptureMode.VIDEO) "Switch To Video" else "Switch To Photo"
        ) {
            if (targetMode == CaptureMode.VIDEO) {
                lastOperation = cameraMediaController.setMode(host, CaptureMode.VIDEO)
            } else {
                log("Switch to photo keeps the UI in PHOTO mode without sending 3001 par=0")
            }
            captureMode = targetMode
            clearRecordingState()
            log("Mode changed to ${captureMode.name}")
            showTransientViewerMessage(
                if (debugModeEnabled) {
                    "Mode command sent"
                } else if (targetMode == CaptureMode.VIDEO) {
                    "Video Mode"
                } else {
                    "Photo Mode"
                }
            )
        }
    }

    fun exportDebugLog() {
        val report = buildString {
            appendLine("XIRO Lite Camera Viewer Debug Export")
            appendLine("Timestamp: ${cameraTimestamp(System.currentTimeMillis())}")
            appendLine("Host: $host")
            appendLine("Profile: ${profile.displayName}")
            appendLine("RTSP URL: $rtspUrl")
            appendLine("Stream Profile: ${streamProfile.label}")
            appendLine("RTSP Transport: ${streamProfile.transportLabel}")
            appendLine("Mode: ${captureMode.name}")
            appendLine("Recording: $isRecording")
            appendLine("Storage: ${localLibraryManager.activeRootPath()}")
            appendLine()
            appendViewerDiagnostics()
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
        RemoteBatteryHub.start(
            context = context.applicationContext,
            host = relayHost,
            onLog = ::log
        )
        lastLoggedPacketTimestamp = liveTelemetryPackets.firstOrNull()?.timestampMs ?: 0L
        val hjState = hjLogRecorder.startSession("LIVEVIEW")
        hjLogStatus = hjState.statusText
        log("Flight log started: ${hjState.activeFile?.absolutePath ?: hjState.statusText}")
        log("Camera viewer opened")
        log("Initial stream profile: ${streamProfile.label} (${streamProfile.transportLabel})")
        if (debugModeEnabled && autoDebugCapture) {
            val captureResult = rootedLiveViewCaptureManager.startCapture(
                host = host,
                relayHost = relayHost,
                profile = profile,
                streamProfile = streamProfile,
                appPid = android.os.Process.myPid()
            )
            captureResult.onSuccess { session ->
                debugCaptureStatus = "Combo log running: ${session.transportFolderName} -> ${session.folder.absolutePath}"
                log(
                    "Debug combo capture started: ${session.transportFolderName} " +
                        "pcap=${session.pcapFile.name} logcat=${session.logcatFile.name}"
                )
            }.onFailure { error ->
                debugCaptureStatus = "Combo log failed: ${error.message ?: "Unknown error"}"
                log(debugCaptureStatus)
            }
        }
        captureMode = CaptureMode.PHOTO
        clearRecordingState()
        automaticTcpFallbackUsed = false
        log("Viewer opened with PHOTO selected by default")
        if (usesLegacyXplorerLiveViewPatch) {
            liveFrameRendered = false
            clockSyncAttempted = false
            log("Viewer startup: delaying RTSP until XIRO Xplorer live-view init completes")
            primeLegacyLiveView("Viewer startup")
            reloadToken += 1
            playerActive = true
            scheduleLegacyPreviewKick("Viewer startup")
            log("Viewer startup: starting RTSP after XIRO Xplorer init")
        }
    }

    LaunchedEffect(liveFrameRendered, playerActive, autoRecoveryPending, profile.id) {
        if (
            usesLegacyXplorerLiveViewPatch &&
            liveFrameRendered &&
            playerActive &&
            !autoRecoveryPending &&
            !clockSyncAttempted
        ) {
            clockSyncAttempted = true
            delay(8_000)
            if (!playerActive || autoRecoveryPending) return@LaunchedEffect
            log("Syncing XIRO Xplorer camera clock after live view stabilized")
            val syncResult = cameraMediaController.syncClock(host)
            lastOperation = syncResult
            log("${syncResult.title}: ${syncResult.summary}")
        }
    }

    LaunchedEffect(Unit) {
        activeOfflineMap = offlineMapManager.activeMapFile()
    }

    LaunchedEffect(activeOfflineMap?.fileName) {
        if (activeOfflineMap != null && !phoneLocationPermissionGranted) {
            requestPhoneLocationPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
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
            relayProbeResults = relayProbe.probeSettingsRootLiveView(relayHost)
            delay(10_000)
        }
    }

    LaunchedEffect(networkInfo.localIp, host, playerActive, profile.id) {
        if (networkInfo.localIp.isNullOrBlank()) {
            cameraStorageResults = emptyList()
            return@LaunchedEffect
        }

        if (usesLegacyXplorerLiveViewPatch && playerActive) {
            if (cameraStorageResults.isEmpty()) {
                cameraStorageResults = telemetryProbe.readCameraStorage(host)
            }
            while (true) {
                val keepAlive = telemetryProbe.readLiveViewKeepAlive(host)
                if (!keepAlive.status.startsWith("HTTP 200")) {
                    log("Live View keepalive ${keepAlive.label}: ${keepAlive.status}")
                }
                delay(5_000)
            }
        } else {
            while (true) {
                cameraStorageResults = telemetryProbe.readCameraStorage(host)
                delay(10_000)
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            relayProbe.closeSession()
        }
    }

    LaunchedEffect(autoRecoveryPending, playerActive, reloadToken, streamProfile, liveFrameRendered) {
        if (!autoRecoveryPending || !playerActive) return@LaunchedEffect
        while (autoRecoveryPending && playerActive) {
            val retryDelayMs = if (streamProfile.forceRtpTcp && !liveFrameRendered) {
                maxOf(12_000L, streamProfile.startupRecoveryMs)
            } else {
                12_000L
            }
            delay(retryDelayMs)
            if (!autoRecoveryPending || !playerActive) break
            if (!lastRecoveryWasPreemptive) {
                recoveryMessage = "Camera link interrupted. Retrying live feed..."
            }
            if (streamProfile.forceRtpTcp && !liveFrameRendered) {
                log("Automatic RTSP recovery still pending; holding the TCP stability session open longer before another hard restart")
            } else {
                log("Automatic RTSP recovery still pending; hard restarting the live feed again")
            }
            scheduleHardRtspRestart("automatic recovery retry while player stayed unavailable", autoRecoveryMessageToken)
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
        if (viewerSettingsPanelVisible) {
            viewerSettingsPanelVisible = false
        } else {
            scope.launch { requestViewerExit() }
        }
    }

    CameraFullScreenSystemBars(enabled = true)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val insetModifier = Modifier
            .align(Alignment.BottomStart)
            .padding(start = 18.dp, bottom = 20.dp)
            .fillMaxWidth(0.19f)
            .aspectRatio(1f)

        if (mapExpanded) {
            OfflineFlightMapSurface(
                modifier = Modifier.fillMaxSize(),
                activeMap = activeOfflineMap,
                dronePosition = derivedFlightTelemetry.latestCoordinate,
                phonePosition = phoneFlightCoordinate,
                homePosition = derivedFlightTelemetry.homeCoordinate,
                distanceLabel = derivedFlightTelemetry.distanceFromHomeMeters
                    ?.let { formatDistanceForUnit(it, measurementUnit) }
                    ?: "--",
                elevationLabel = derivedFlightTelemetry.altitudeMeters
                    ?.let { formatAltitudeForUnit(it, measurementUnit) }
                    ?: "--",
                activeMapLabel = activeOfflineMap?.displayName ?: "Offline Maps",
                expanded = true,
                onSwapRequested = { },
                tapToSwapEnabled = false,
                onRequestLocationPermission = {
                    requestPhoneLocationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                phoneLocationPermissionGranted = phoneLocationPermissionGranted
            )
            CameraPreviewSurface(
                modifier = insetModifier,
                playerActive = playerActive,
                rtspUrl = rtspUrl,
                reloadToken = reloadToken,
                streamProfile = streamProfile,
                rtspController = rtspController,
                recoveryFrameBitmap = recoveryFrameBitmap,
                debugRtspMessages = debugModeEnabled,
                onFirstFrameRendered = {
                    liveFrameRendered = true
                    if (commandTransitionOverlayActive) {
                        clearCommandTransitionOverlay("RTSP stream resumed after camera command")
                    }
                    if (autoRecoveryPending) {
                        clearAutoRecoveryOverlay("RTSP stream resumed after automatic reload")
                    }
                },
                onPlaybackResumed = {
                    if (commandTransitionOverlayActive) {
                        clearCommandTransitionOverlay("RTSP playback resumed after camera command")
                    }
                    if (autoRecoveryPending) {
                        clearAutoRecoveryOverlay("RTSP playback resumed after automatic reload")
                    }
                },
                onRecoveryRequested = { reason -> handleAutomaticStreamRecovery(reason) },
                onLog = ::log,
                onSwapRequested = { mapExpanded = false },
                overlayLabel = "Live"
            )
        } else {
            CameraPreviewSurface(
                modifier = Modifier.fillMaxSize(),
                playerActive = playerActive,
                rtspUrl = rtspUrl,
                reloadToken = reloadToken,
                streamProfile = streamProfile,
                rtspController = rtspController,
                recoveryFrameBitmap = recoveryFrameBitmap,
                debugRtspMessages = debugModeEnabled,
                onFirstFrameRendered = {
                    liveFrameRendered = true
                    if (commandTransitionOverlayActive) {
                        clearCommandTransitionOverlay("RTSP stream resumed after camera command")
                    }
                    if (autoRecoveryPending) {
                        clearAutoRecoveryOverlay("RTSP stream resumed after automatic reload")
                    }
                },
                onPlaybackResumed = {
                    if (commandTransitionOverlayActive) {
                        clearCommandTransitionOverlay("RTSP playback resumed after camera command")
                    }
                    if (autoRecoveryPending) {
                        clearAutoRecoveryOverlay("RTSP playback resumed after automatic reload")
                    }
                },
                onRecoveryRequested = { reason -> handleAutomaticStreamRecovery(reason) },
                onLog = ::log
            )
            OfflineFlightMapSurface(
                modifier = insetModifier,
                activeMap = activeOfflineMap,
                dronePosition = derivedFlightTelemetry.latestCoordinate,
                phonePosition = phoneFlightCoordinate,
                homePosition = derivedFlightTelemetry.homeCoordinate,
                distanceLabel = derivedFlightTelemetry.distanceFromHomeMeters
                    ?.let { formatDistanceForUnit(it, measurementUnit) }
                    ?: "--",
                elevationLabel = derivedFlightTelemetry.altitudeMeters
                    ?.let { formatAltitudeForUnit(it, measurementUnit) }
                    ?: "--",
                activeMapLabel = activeOfflineMap?.displayName ?: "Offline Maps",
                expanded = false,
                onSwapRequested = { mapExpanded = activeOfflineMap != null },
                tapToSwapEnabled = true,
                onRequestLocationPermission = {
                    requestPhoneLocationPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                },
                phoneLocationPermissionGranted = phoneLocationPermissionGranted
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
            XiroIconSurface(shape = RoundedCornerShape(16.dp)) {
                IconButton(
                    onClick = { scope.launch { requestViewerExit() } }
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top
            ) {
                val hudCompactLevel = when {
                    viewerHudItems.size >= 7 -> 2
                    viewerHudItems.size >= 5 -> 1
                    else -> 0
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    viewerHudItems.forEach { item ->
                        ViewerHudPill(item = item, compactLevel = hudCompactLevel)
                    }
                }
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalAlignment = Alignment.End
                ) {
                    XiroIconSurface(shape = RoundedCornerShape(16.dp)) {
                        IconButton(
                            onClick = { viewerSettingsPanelVisible = !viewerSettingsPanelVisible }
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Settings,
                                contentDescription = "Live View Settings",
                                tint = Color.White
                            )
                        }
                    }
                    if (debugModeEnabled) {
                        XiroSecondaryButton(onClick = { exportDebugLog() }) { Text("Export Debug Log") }
                        XiroSecondaryButton(onClick = { debugExpanded = !debugExpanded }) { Text(if (debugExpanded) "Debug ^" else "Debug v") }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = viewerSettingsPanelVisible,
            modifier = Modifier.fillMaxSize()
        ) {
            ViewerLiveSettingsPanel(
                showTransportControls = debugModeEnabled && supportsStabilityToggle,
                streamProfile = streamProfile,
                onSelectStreamProfile = { selectedProfile ->
                    streamProfile = selectedProfile
                    automaticTcpFallbackUsed = false
                    log("Live preview stream profile set to ${streamProfile.label} (${streamProfile.transportLabel})")
                },
                selectedTab = viewerCameraSettingsTab,
                onSelectTab = { viewerCameraSettingsTab = it },
                previewResolution = viewerPreviewResolution,
                imageResolution = viewerImageResolution,
                antiBlink = viewerAntiBlink,
                controlsEnabled = !actionObservationInProgress,
                onSettingSelected = { settingKey, option ->
                    scope.launch {
                        when (settingKey) {
                            ViewerInteractiveSettingKey.PREVIEW_RESOLUTION -> applyPreviewResolution(option)
                            ViewerInteractiveSettingKey.IMAGE_RESOLUTION -> applyImageResolution(option)
                            ViewerInteractiveSettingKey.ANTI_BLINK -> applyAntiBlink(option)
                        }
                    }
                },
                onDismiss = { viewerSettingsPanelVisible = false }
            )
        }

        if (viewerWarnings.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 18.dp)
                    .widthIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                viewerWarnings.forEach { warning ->
                    ViewerWarningBanner(warning)
                }
            }
        }

        if (debugModeEnabled && (actionExportStatus.isNotBlank() || debugCaptureStatus.isNotBlank())) {
            XiroRaisedCard(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 72.dp),
                containerColor = XiroDesignTokens.SurfaceOverlay
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (debugCaptureStatus.isNotBlank()) {
                        Text(
                            text = debugCaptureStatus,
                            color = Color.White
                        )
                    }
                    if (actionExportStatus.isNotBlank()) {
                        Text(
                            text = actionExportStatus,
                            color = Color.White
                        )
                    }
                }
            }
        }

        recoveryMessage?.let {
            XiroRaisedCard(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(horizontal = 24.dp),
                containerColor = XiroDesignTokens.SurfaceOverlay
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                    color = Color.White
                )
            }
        }

        AnimatedVisibility(
            visible = !viewerSettingsPanelVisible,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 22.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                XiroPillSurface(
                    modifier = Modifier
                        .wrapContentWidth()
                        .clickable(enabled = !actionObservationInProgress && !isRecording) {
                            scope.launch {
                                switchViewerCaptureMode(
                                    if (captureMode == CaptureMode.PHOTO) CaptureMode.VIDEO else CaptureMode.PHOTO
                                )
                            }
                        },
                    shape = RoundedCornerShape(20.dp),
                    containerColor = XiroDesignTokens.SurfaceOverlay
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
            }
        }

        AnimatedVisibility(
            visible = debugModeEnabled && debugExpanded,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 18.dp)
        ) {
            XiroRaisedCard(
                modifier = Modifier.fillMaxWidth(0.62f),
                shape = RoundedCornerShape(20.dp),
                containerColor = XiroDesignTokens.SurfaceOverlay
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
                    XiroSecondaryButton(
                        onClick = {
                            scope.launch {
                                runDebugCaptureCommand(
                                    command = DebugCaptureCommand.PHOTO_TRIGGER_ONLY,
                                    refreshLibraryAfterRecovery = false
                                )
                            }
                        },
                    ) { Text("Photo Trigger Only (1001)") }
                    Text("Sends only the direct take-photo trigger without switching mode first.", color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                    XiroSecondaryButton(
                        onClick = {
                            scope.launch {
                                runDebugCaptureCommand(
                                    command = DebugCaptureCommand.PHOTO_MODE_THEN_TRIGGER,
                                    refreshLibraryAfterRecovery = false
                                )
                            }
                        },
                    ) { Text("Photo Mode Then Trigger (3001 -> 1001)") }
                    Text("Switches to photo mode first, waits briefly, then sends the photo trigger.", color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                    XiroSecondaryButton(
                        onClick = {
                            scope.launch {
                                runDebugCaptureCommand(
                                    command = DebugCaptureCommand.VIDEO_START_ONLY,
                                    refreshLibraryAfterRecovery = false
                                )
                            }
                        },
                    ) { Text("Video Start Only (2001 par=1)") }
                    Text("Sends only the direct video-start command with no extra prep sequence.", color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
                    XiroSecondaryButton(
                        onClick = {
                            scope.launch {
                                runDebugCaptureCommand(
                                    command = DebugCaptureCommand.VIDEO_STOP_ONLY,
                                    refreshLibraryAfterRecovery = false
                                )
                            }
                        },
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
private fun CameraPreviewSurface(
    modifier: Modifier,
    playerActive: Boolean,
    rtspUrl: String,
    reloadToken: Int,
    streamProfile: StreamProfile,
    rtspController: RtspPlayerController,
    recoveryFrameBitmap: Bitmap?,
    debugRtspMessages: Boolean,
    onFirstFrameRendered: () -> Unit,
    onPlaybackResumed: () -> Unit,
    onRecoveryRequested: (String) -> Unit,
    onLog: (String) -> Unit,
    onSwapRequested: (() -> Unit)? = null,
    overlayLabel: String? = null
) {
    Box(
        modifier = modifier
            .let { base ->
                if (onSwapRequested != null) {
                    base.clickable(onClick = onSwapRequested)
                } else {
                    base
                }
            }
            .background(Color.Black)
    ) {
        if (playerActive) {
            RtspPlayer(
                url = rtspUrl,
                reloadToken = reloadToken,
                streamProfile = streamProfile,
                debugRtspMessages = debugRtspMessages,
                modifier = Modifier.fillMaxSize(),
                controller = rtspController,
                onFirstFrameRendered = onFirstFrameRendered,
                onPlaybackResumed = onPlaybackResumed,
                onRecoveryRequested = onRecoveryRequested,
                onLog = onLog
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

        if (overlayLabel != null) {
            XiroPillSurface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(14.dp),
                containerColor = XiroDesignTokens.SurfaceOverlay
            ) {
                Text(
                    text = overlayLabel,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    color = XiroDesignTokens.TextPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
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
private fun ViewerHudPill(item: PreflightItem, compactLevel: Int) {
    val horizontalPadding = when (compactLevel) {
        2 -> 8.dp
        1 -> 10.dp
        else -> 12.dp
    }
    val verticalPadding = when (compactLevel) {
        2 -> 6.dp
        1 -> 7.dp
        else -> 8.dp
    }
    val labelStyle = when (compactLevel) {
        2 -> MaterialTheme.typography.labelSmall
        else -> MaterialTheme.typography.bodySmall
    }
    val valueStyle = when (compactLevel) {
        2 -> MaterialTheme.typography.bodySmall
        1 -> MaterialTheme.typography.bodyMedium
        else -> MaterialTheme.typography.bodyLarge
    }
    val label = compactHudLabel(item.label, compactLevel)
    XiroPillSurface(
        shape = RoundedCornerShape(18.dp),
        containerColor = XiroDesignTokens.SurfaceOverlay
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (item.label == "Wi-Fi Signal") {
                Text(label, color = Color(0xFFD7DCE3), style = labelStyle)
                SignalStrengthBars(level = parseSignalStrengthVisual(item.value)?.level ?: 0)
            } else {
                StatusDot(level = resolveStatusIndicatorLevel(item.label, item.value, item.ok), size = 8.dp)
                Text(label, color = Color(0xFFD7DCE3), style = labelStyle)
                Text(item.value, color = Color.White, fontWeight = FontWeight.SemiBold, style = valueStyle, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ViewerWarningBanner(warning: FlightWarning) {
    val containerColor = when (warning.severity) {
        FlightWarningSeverity.INFO -> Color(0xFF2D4559)
        FlightWarningSeverity.CAUTION -> Color(0xFF5B4A14)
        FlightWarningSeverity.CRITICAL -> Color(0xFF5D2A2A)
    }
    val accentColor = when (warning.severity) {
        FlightWarningSeverity.INFO -> Color(0xFF64B5F6)
        FlightWarningSeverity.CAUTION -> Color(0xFFFFC107)
        FlightWarningSeverity.CRITICAL -> Color(0xFFFF6E6E)
    }

    XiroPillSurface(
        modifier = Modifier.wrapContentWidth(),
        shape = RoundedCornerShape(18.dp),
        containerColor = containerColor
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
            Column(
                modifier = Modifier.widthIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(warning.title, color = Color.White)
                Text(warning.detail, color = Color(0xFFD7DCE3), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ViewerLiveSettingsPanel(
    showTransportControls: Boolean,
    streamProfile: StreamProfile,
    onSelectStreamProfile: (StreamProfile) -> Unit,
    selectedTab: ViewerCameraSettingsTab,
    onSelectTab: (ViewerCameraSettingsTab) -> Unit,
    previewResolution: String,
    imageResolution: String,
    antiBlink: String,
    controlsEnabled: Boolean,
    onSettingSelected: (ViewerInteractiveSettingKey, String) -> Unit,
    onDismiss: () -> Unit
) {
    val backdropInteraction = remember { MutableInteractionSource() }
    val panelInteraction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.42f))
            .clickable(
                interactionSource = backdropInteraction,
                indication = null,
                onClick = onDismiss
            )
    ) {
        XiroDialogPanel(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 82.dp, end = 14.dp, start = 92.dp)
                .widthIn(max = 520.dp)
                .height(540.dp)
                .clickable(
                    interactionSource = panelInteraction,
                    indication = null,
                    onClick = {}
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Live Camera Settings",
                            color = XiroDesignTokens.TextPrimary,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "Legacy XIRO Xplorer tabs recreated here. Capture findings are highlighted; unknown command mappings stay grayed out for now.",
                            color = XiroDesignTokens.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    XiroSecondaryButton(
                        onClick = onDismiss,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Close")
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(XiroDesignTokens.BorderLight)
                )

                if (showTransportControls) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Live stream transport",
                            color = XiroDesignTokens.AccentBright,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "These controls affect XIRO Lite's transport behavior. Preview Resolution below is the legacy camera-side stream-size setting.",
                            color = XiroDesignTokens.TextSecondary,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Row(
                            modifier = Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ViewerStreamProfileChip(
                                label = "Auto (Legacy UDP)",
                                selected = streamProfile == StreamProfile.AUTO,
                                onClick = { onSelectStreamProfile(StreamProfile.AUTO) }
                            )
                            ViewerStreamProfileChip(
                                label = "Legacy UDP direct",
                                selected = streamProfile == StreamProfile.QUALITY,
                                onClick = { onSelectStreamProfile(StreamProfile.QUALITY) }
                            )
                            ViewerStreamProfileChip(
                                label = "Stability (TCP)",
                                selected = streamProfile == StreamProfile.STABILITY,
                                onClick = { onSelectStreamProfile(StreamProfile.STABILITY) }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(XiroDesignTokens.BorderLight)
                    )
                }

                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ViewerCameraSettingsTab.entries.forEach { tab ->
                        XiroToggleChip(
                            selected = selectedTab == tab,
                            onClick = { onSelectTab(tab) },
                            label = {
                                Text(
                                    text = tab.title,
                                    color = if (selectedTab == tab) XiroDesignTokens.TextPrimary else XiroDesignTokens.TextSecondary,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        )
                    }
                }

                viewerCameraSettingsFor(
                    tab = selectedTab,
                    previewResolution = previewResolution,
                    imageResolution = imageResolution,
                    antiBlink = antiBlink
                ).forEach { setting ->
                    ViewerCameraSettingCard(
                        setting = setting,
                        controlsEnabled = controlsEnabled,
                        onOptionSelected = setting.actionKey?.let { settingKey ->
                            { option -> onSettingSelected(settingKey, option) }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerStreamProfileChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    XiroToggleChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                color = if (selected) XiroDesignTokens.TextPrimary else XiroDesignTokens.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    )
}

@Composable
private fun ViewerCameraSettingCard(
    setting: ViewerCameraSettingRow,
    controlsEnabled: Boolean,
    onOptionSelected: ((String) -> Unit)? = null
) {
    val visualAlpha = when (setting.evidence) {
        ViewerCameraSettingEvidence.VERIFIED -> 1f
        ViewerCameraSettingEvidence.OBSERVED -> 0.9f
        ViewerCameraSettingEvidence.PENDING -> 0.56f
    }
    val titleColor = when (setting.evidence) {
        ViewerCameraSettingEvidence.VERIFIED -> XiroDesignTokens.AccentBright
        ViewerCameraSettingEvidence.OBSERVED -> XiroDesignTokens.TextPrimary
        ViewerCameraSettingEvidence.PENDING -> XiroDesignTokens.TextPrimary
    }

    XiroRaisedCard(
        containerColor = if (setting.evidence == ViewerCameraSettingEvidence.PENDING) {
            XiroDesignTokens.SurfaceBottom
        } else {
            XiroDesignTokens.SurfaceInset
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer(alpha = visualAlpha)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = setting.label,
                        color = titleColor,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        text = "Current: ${setting.currentValue}",
                        color = XiroDesignTokens.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                XiroPillSurface(
                    shape = RoundedCornerShape(14.dp),
                    containerColor = when (setting.evidence) {
                        ViewerCameraSettingEvidence.VERIFIED -> XiroDesignTokens.Accent
                        ViewerCameraSettingEvidence.OBSERVED -> XiroDesignTokens.SurfaceOverlay
                        ViewerCameraSettingEvidence.PENDING -> XiroDesignTokens.SurfaceBottom
                    }
                ) {
                    Text(
                        text = setting.evidence.label,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = XiroDesignTokens.TextPrimary,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            if (setting.options.isNotEmpty()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    setting.options.forEach { option ->
                        ViewerSettingChip(
                            label = option,
                            selected = option == setting.selectedOption,
                            evidence = setting.evidence,
                            enabled = controlsEnabled,
                            interactive = onOptionSelected != null,
                            onClick = if (onOptionSelected != null) {
                                { onOptionSelected(option) }
                            } else {
                                null
                            }
                        )
                    }
                }
            }

            if (setting.note.isNotBlank()) {
                Text(
                    text = setting.note,
                    color = XiroDesignTokens.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ViewerSettingChip(
    label: String,
    selected: Boolean,
    evidence: ViewerCameraSettingEvidence,
    enabled: Boolean,
    interactive: Boolean,
    onClick: (() -> Unit)? = null
) {
    val containerColor = when {
        selected && evidence == ViewerCameraSettingEvidence.VERIFIED -> XiroDesignTokens.Accent
        selected && evidence == ViewerCameraSettingEvidence.OBSERVED -> XiroDesignTokens.SurfaceOverlay
        selected -> XiroDesignTokens.SurfaceInset
        else -> XiroDesignTokens.SurfaceBottom
    }
    val textColor = when {
        selected && evidence != ViewerCameraSettingEvidence.PENDING -> XiroDesignTokens.TextPrimary
        evidence == ViewerCameraSettingEvidence.PENDING -> XiroDesignTokens.TextMuted
        else -> XiroDesignTokens.TextSecondary
    }
    val chipAlpha = when {
        !interactive && evidence == ViewerCameraSettingEvidence.PENDING -> 0.72f
        !enabled && interactive -> 0.58f
        else -> 1f
    }

    XiroPillSurface(
        modifier = Modifier
            .graphicsLayer(alpha = chipAlpha)
            .then(
                if (interactive && enabled && onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(16.dp),
        containerColor = containerColor
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = textColor,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun compactHudLabel(label: String, compactLevel: Int): String {
    if (compactLevel <= 0) return label
    return when (label) {
        "GPS Sat" -> "GPS"
        "Aircraft Power" -> if (compactLevel >= 2) "Power" else label
        "Remote Power" -> if (compactLevel >= 2) "Remote" else label
        "Flight Mode" -> "Mode"
        "Elevation" -> "Elev"
        "Target Elevation" -> if (compactLevel >= 2) "Target" else "Target Elev"
        "SD Card" -> "SD"
        "Camera" -> "Cam"
        "Wi-Fi Signal" -> "Wi-Fi"
        else -> label
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
