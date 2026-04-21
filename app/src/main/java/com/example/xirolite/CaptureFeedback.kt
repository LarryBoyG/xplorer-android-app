package com.example.xirolite

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator

class CaptureFeedback(context: Context) {
    private val mediaActionSound = MediaActionSound().apply {
        try {
            load(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Throwable) {
        }
        try {
            load(MediaActionSound.START_VIDEO_RECORDING)
        } catch (_: Throwable) {
        }
        try {
            load(MediaActionSound.STOP_VIDEO_RECORDING)
        } catch (_: Throwable) {
        }
    }

    fun playPhotoShutter() {
        try {
            mediaActionSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Throwable) {
        }
        // Keep a short fallback click so devices that suppress MediaActionSound
        // still give the user immediate feedback when a photo is triggered.
        playTone(ToneGenerator.TONE_PROP_BEEP2, 90, stream = AudioManager.STREAM_MUSIC)
    }

    fun playRecordBeep() {
        try {
            mediaActionSound.play(MediaActionSound.START_VIDEO_RECORDING)
        } catch (_: Throwable) {
        }
        playTone(ToneGenerator.TONE_PROP_BEEP, 160)
    }

    fun playRecordStopBeep() {
        try {
            mediaActionSound.play(MediaActionSound.STOP_VIDEO_RECORDING)
        } catch (_: Throwable) {
        }
        playTone(ToneGenerator.TONE_PROP_BEEP2, 130)
    }

    private fun playTone(toneType: Int, durationMs: Int, stream: Int = AudioManager.STREAM_MUSIC) {
        Thread {
            var tone: ToneGenerator? = null
            try {
                tone = ToneGenerator(stream, 100)
                tone.startTone(toneType, durationMs)
                Thread.sleep((durationMs + 20).toLong())
            } catch (_: Throwable) {
            } finally {
                try {
                    tone?.release()
                } catch (_: Throwable) {
                }
            }
        }.start()
    }
}
