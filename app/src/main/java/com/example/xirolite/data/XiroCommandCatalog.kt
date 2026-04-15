package com.example.xirolite.data

enum class XiroCommandKind {
    HTTP,
    UDP_6800_PROBE
}

data class XiroCommand(
    val name: String,
    val path: String,
    val notes: String = "",
    val kind: XiroCommandKind = XiroCommandKind.HTTP,
    val runnable: Boolean = kind == XiroCommandKind.UDP_6800_PROBE ||
        path.startsWith("/") ||
        path.startsWith("http://") ||
        path.startsWith("https://")
)

object XiroCommandCatalog {
    val commands: List<XiroCommand> = commandsFor(LegacyCompatibilityCatalog.defaultProfile)

    fun commandsFor(profile: LegacyDroneProfile): List<XiroCommand> {
        val items = mutableListOf<XiroCommand>()
        val cameraCommands = profile.cameraCommands

        items += XiroCommand(
            name = "UDP 6800 Probe",
            path = "udp://0.0.0.0:6800",
            notes = "Capture UDP telemetry for 20 seconds and auto-export a legacy-model worksheet. Useful for hunting power, voltage, searchStarNum, currentControlState, and flightGear.",
            kind = XiroCommandKind.UDP_6800_PROBE
        )

        cameraCommands?.prepPath?.let {
            items += XiroCommand("Camera Prep", it, "Legacy pre-capture setup command (2016).")
        }
        cameraCommands?.setPhotoModePath?.let {
            items += XiroCommand("Photo Mode", it, "Switch camera into photo mode (3001 par=0). Currently considered risky.")
        }
        cameraCommands?.setVideoModePath?.let {
            items += XiroCommand("Video Mode", it, "Switch camera into video mode (3001 par=1).")
        }
        cameraCommands?.takePhotoPath?.let {
            items += XiroCommand("Take Photo", it, "Photo trigger command (1001).")
        }
        cameraCommands?.startVideoPath?.let {
            items += XiroCommand("Start Video", it, "Start video recording (2001 par=1). Currently considered risky.")
        }
        cameraCommands?.stopVideoPath?.let {
            items += XiroCommand("Stop Video", it, "Stop video recording (2001 par=0).")
        }

        cameraCommands?.probePaths?.forEach { items += XiroCommand("Probe", it, "Legacy probe endpoint.") }
        cameraCommands?.libraryPaths?.forEach { items += XiroCommand("Library", it, "Legacy media library or status endpoint.") }
        cameraCommands?.telemetryPaths?.forEach { items += XiroCommand("Telemetry", it, "Legacy telemetry/status endpoint.") }
        cameraCommands?.rtspPaths?.forEach {
            items += XiroCommand("RTSP candidate", it, "Streaming candidate URL. Listed for reference only.", runnable = false)
        }

        items += XiroCommand("Delete sample", "/uav.cgi?op=delete&type=pic&id=1", "Sample delete endpoint from legacy app.")
        items += XiroCommand("Thumbnail getter", "/cgiget/thumbnail?filename=", "Thumbnail fetch endpoint. Needs a filename suffix.")
        items += XiroCommand("Screennail getter", "/cgiget/screennail?filename=", "Screen preview fetch endpoint. Needs a filename suffix.")

        if (profile.id == LegacyCompatibilityCatalog.repeaterTinyQ.id) {
            items += XiroCommand("Relay list socket", "tcp://192.168.2.254:6662", "Primary repeater bind/list transport candidate.", runnable = false)
            items += XiroCommand("Relay fallback socket", "tcp://192.168.2.254:6666", "Fallback repeater command transport candidate.", runnable = false)
        }

        return items.distinctBy { "${it.name}|${it.path}" }
    }
}
