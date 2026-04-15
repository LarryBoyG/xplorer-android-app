package com.example.xirolite

import android.content.Context
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator

class CaptureFeedback(context: Context) {
    private val shutterSound = MediaActionSound().apply {
        try {
            load(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Throwable) {
        }
    }

    fun playPhotoShutter() {
        try {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (_: Throwable) {
        }
        // Keep a short fallback click so devices that suppress MediaActionSound
        // still give the user immediate feedback when a photo is triggered.
        playTone(ToneGenerator.TONE_PROP_BEEP2, 90, stream = AudioManager.STREAM_SYSTEM)
    }

    fun playRecordBeep() {
        playTone(ToneGenerator.TONE_PROP_BEEP, 160)
    }

    fun playRecordStopBeep() {
        playTone(ToneGenerator.TONE_PROP_BEEP2, 130)
    }

    private fun playTone(toneType: Int, durationMs: Int, stream: Int = AudioManager.STREAM_NOTIFICATION) {
        Thread {
            var tone: ToneGenerator? = null
            try {
                tone = ToneGenerator(stream, 90)
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
