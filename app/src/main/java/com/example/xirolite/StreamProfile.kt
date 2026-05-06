package com.example.xirolite

enum class StreamProfile(
    val label: String,
    val transportLabel: String,
    val forceRtpTcp: Boolean,
    val timeoutMs: Long,
    val preemptiveRefreshMs: Long?,
    val startupRecoveryMs: Long,
    val staleFrameRecoveryMs: Long,
    val bufferingRecoveryMs: Long,
    val liveTargetOffsetMs: Long,
    val minPlaybackSpeed: Float,
    val maxPlaybackSpeed: Float,
    val liveSnapbackOffsetMs: Long?
) {
    AUTO("Auto", "Legacy UDP RTP", false, 8_000L, null, 18_000L, 20_000L, 30_000L, 20L, 1.0f, 1.45f, 650L),
    QUALITY("Legacy UDP", "UDP RTP", false, 8_000L, null, 18_000L, 20_000L, 30_000L, 20L, 1.0f, 1.45f, 650L),
    STABILITY("Stability", "TCP interleaved", true, 30_000L, null, 45_000L, 25_000L, 20_000L, 700L, 0.98f, 1.03f, null)
}
