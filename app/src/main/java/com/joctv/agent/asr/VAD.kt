package com.joctv.agent.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

class VAD(private val listener: VadListener, private val sampleRate: Int = 16000) {
    companion object {
        const val TAG = "ASR_VAD"
        private const val FRAME_SIZE_MS = 20 // 20ms frame
        private const val SILENCE_THRESHOLD_MULTIPLIER = 3.0
        private const val MIN_SILENCE_DURATION_MS = 800
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var noiseLevel = 0.0
    private var silenceStartMs: Long = 0
    private var isNoiseCalibrated = false

    private val frameSize = sampleRate * FRAME_SIZE_MS / 1000
    private val buffer = ShortArray(frameSize)

    fun startRecording() {
        if (isRecording) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = frameSize * 2 // Double buffer size for safety

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true
        isNoiseCalibrated = false
        silenceStartMs = 0

        Thread {
            calibrateNoise()
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, frameSize) ?: 0
                if (read > 0) {
                    processFrame()
                }
            }
        }.start()
    }

    fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun calibrateNoise() {
        Log.d(TAG, "Calibrating noise level...")
        var sumSq = 0.0
        val calibrationFrames = 15 // ~300ms at 20ms/frame
        
        for (i in 0 until calibrationFrames) {
            val read = audioRecord?.read(buffer, 0, frameSize) ?: 0
            if (read > 0) {
                for (j in 0 until read) {
                    val sample = buffer[j].toDouble()
                    sumSq += sample * sample
                }
            }
        }
        
        noiseLevel = sqrt(sumSq / (calibrationFrames * frameSize))
        val threshold = noiseLevel * SILENCE_THRESHOLD_MULTIPLIER
        Log.d(TAG, "Noise calibrated. RMS: $noiseLevel, Threshold: $threshold")
        isNoiseCalibrated = true
    }

    private fun processFrame() {
        if (!isNoiseCalibrated) return

        var sumSq = 0.0
        for (i in buffer.indices) {
            val sample = buffer[i].toDouble()
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq / buffer.size)
        
        val threshold = noiseLevel * SILENCE_THRESHOLD_MULTIPLIER
        val isSilent = rms < threshold

        if (isSilent) {
            if (silenceStartMs == 0L) {
                silenceStartMs = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - silenceStartMs >= MIN_SILENCE_DURATION_MS) {
                Log.d(TAG, "Silence detected for ${MIN_SILENCE_DURATION_MS}ms, triggering finalize")
                listener.onSilenceDetected()
                silenceStartMs = 0 // Reset to avoid repeated triggers
            }
        } else {
            silenceStartMs = 0 // Reset silence timer
        }
    }

    interface VadListener {
        fun onSilenceDetected()
    }
}