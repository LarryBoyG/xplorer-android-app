package com.example.xirolite

enum class StreamProfile(
    val label: String,
    val transportLabel: String,
    val forceRtpTcp: Boolean,
    val timeoutMs: Long,
    val preemptiveRefreshMs: Long?,
    val startupRecoveryMs: Long,
    val staleFrameRecoveryMs: Long,
    val bufferingRecoveryMs: Long
) {
    AUTO("Auto", "Legacy UDP RTP", false, 8_000L, null, 18_000L, 20_000L, 30_000L),
    QUALITY("Legacy UDP", "UDP RTP", false, 8_000L, null, 18_000L, 20_000L, 30_000L),
    STABILITY("Stability", "TCP interleaved", true, 30_000L, null, 45_000L, 25_000L, 20_000L)
}
