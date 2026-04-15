package com.example.xirolite.data

data class CameraCommandSet(
    val prepPath: String,
    val setPhotoModePath: String,
    val setVideoModePath: String,
    val takePhotoPath: String,
    val startVideoPath: String,
    val stopVideoPath: String,
    val libraryPaths: List<String>,
    val telemetryPaths: List<String>,
    val probePaths: List<String>,
    val rtspPaths: List<String>
)

data class LegacyDroneProfile(
    val id: String,
    val displayName: String,
    val hostCandidates: List<String>,
    val relayCandidates: List<String> = emptyList(),
    val cameraCommands: CameraCommandSet? = null,
    val notes: String = ""
) {
    fun primaryHost(): String = hostCandidates.firstOrNull() ?: "192.168.1.254"

    fun primaryRtspUrl(host: String = primaryHost()): String {
        val template = cameraCommands?.rtspPaths?.firstOrNull() ?: "rtsp://{host}/xxxx.mov"
        return template.replace("{host}", host)
    }
}

object LegacyCompatibilityCatalog {
    private val xplorerCameraCommands = CameraCommandSet(
        prepPath = "/?custom=1&cmd=2016",
        setPhotoModePath = "/?custom=1&cmd=3001&par=0",
        setVideoModePath = "/?custom=1&cmd=3001&par=1",
        takePhotoPath = "/?custom=1&cmd=1001",
        startVideoPath = "/?custom=1&cmd=2001&par=1",
        stopVideoPath = "/?custom=1&cmd=2001&par=0",
        libraryPaths = listOf(
            "/?custom=1&cmd=3015",
            "/?custom=1&cmd=3012",
            "/?custom=1&cmd=3014",
            "/DCIM/PHOTO/",
            "/DCIM/MOVIE/"
        ),
        telemetryPaths = listOf(
            "/?custom=1&cmd=3002",
            "/?custom=1&cmd=3014",
            "/?custom=1&cmd=3017",
            "/?custom=1&cmd=2010",
            "/?custom=1&cmd=2005",
            "/?custom=1&cmd=2033",
            "/?custom=1&cmd=2034",
            "/?custom=1&cmd=3009",
            "/?custom=1&cmd=3080",
            "/?custom=1&cmd=3081",
            "/?custom=1&cmd=4001"
        ),
        probePaths = listOf(
            "/",
            "/uav.cgi?op=",
            "/internal/get_tinty_q_vesion.json",
            "/uav.cgi?op=select&type=pic&startPos=1",
            "/cgi-bin/get_amba_4k_version"
        ),
        rtspPaths = listOf(
            "rtsp://{host}/xxxx.mov",
            "rtsp://{host}:554/xxxx.mov",
            "rtsp://{host}:8553/videoCodecType=H.264"
        )
    )

    val xplorer = LegacyDroneProfile(
        id = "xplorer",
        displayName = "XIRO Xplorer",
        hostCandidates = listOf("192.168.1.254", "192.168.1.1", "192.168.1.21", "192.168.1.3", "192.168.42.1", "10.0.0.200"),
        cameraCommands = xplorerCameraCommands,
        notes = "Baseline profile for Xplorer V/G camera control and media endpoints, including extra hosts seen in the legacy app."
    )

    val xplorer4k = LegacyDroneProfile(
        id = "xplorer_4k",
        displayName = "XIRO Xplorer 4K",
        hostCandidates = listOf("192.168.1.254", "192.168.1.1", "192.168.1.21", "192.168.1.3", "192.168.42.1", "10.0.0.200"),
        cameraCommands = xplorerCameraCommands.copy(
            probePaths = xplorerCameraCommands.probePaths + "/cgi-bin/get_amba_4k_version"
        ),
        notes = "Uses the main XIRO camera surface with Ambarella-flavored probes."
    )

    val gimbalHandheld = LegacyDroneProfile(
        id = "gimbal_handheld",
        displayName = "XIRO Gimbal Handheld",
        hostCandidates = listOf("192.168.1.254", "192.168.1.1", "192.168.1.21", "192.168.1.3", "10.0.0.200"),
        cameraCommands = xplorerCameraCommands,
        notes = "Direct camera Wi-Fi profile matching the legacy gimbal handheld flow on 192.168.1.254."
    )

    val repeaterTinyQ = LegacyDroneProfile(
        id = "repeater_tiny_q",
        displayName = "XIRO Repeater / Tiny-Q Relay",
        hostCandidates = listOf("192.168.2.254"),
        relayCandidates = listOf("192.168.2.254"),
        notes = "Relay-only profile for extender binding, Wi-Fi list, and current-air discovery."
    )

    val profiles: List<LegacyDroneProfile> = listOf(xplorer, xplorer4k, gimbalHandheld, repeaterTinyQ)

    val defaultProfile: LegacyDroneProfile = xplorer

    fun byId(id: String?): LegacyDroneProfile =
        profiles.firstOrNull { it.id == id } ?: defaultProfile
}
