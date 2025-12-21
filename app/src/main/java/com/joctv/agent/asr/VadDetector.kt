package com.joctv.agent.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlin.math.sqrt

class VadDetector(private val listener: VadListener, private val sampleRate: Int = 16000) {
    companion object {
        const val TAG = "ASR_VAD"
        private const val FRAME_SIZE_MS = 20 // 20ms frame
        private const val SILENCE_THRESHOLD_MULTIPLIER = 3.0
        private const val MIN_SILENCE_DURATION_MS = 800
        private const val NOISE_ESTIMATION_DURATION_MS = 500
        private const val MIN_SPEECH_FRAMES = 3
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    // Noise estimation
    private var noiseFloorRms = 0.0
    private var isNoiseEstimated = false
    
    // Speech detection
    private var speechThreshold = 0.0
    private var speechStarted = false
    private var consecutiveSpeechFrames = 0
    private var silenceStartMs: Long = 0
    
    private val frameSize = sampleRate * FRAME_SIZE_MS / 1000
    private val buffer = ShortArray(frameSize)

    fun startRecording() {
        if (isRecording) return

        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        val bufferSize = frameSize * 4 // Larger buffer for safety

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
        isNoiseEstimated = false
        silenceStartMs = 0
        speechStarted = false
        consecutiveSpeechFrames = 0

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
        Log.d(TAG, "Calibrating noise floor...")
        var sumSq = 0.0
        val framesToEstimate = (sampleRate * NOISE_ESTIMATION_DURATION_MS / 1000) / frameSize
        
        for (i in 0 until framesToEstimate) {
            val read = audioRecord?.read(buffer, 0, frameSize) ?: 0
            if (read > 0) {
                for (j in 0 until read) {
                    val sample = buffer[j].toDouble()
                    sumSq += sample * sample
                }
            }
        }
        
        noiseFloorRms = sqrt(sumSq / (framesToEstimate * frameSize))
        speechThreshold = noiseFloorRms * SILENCE_THRESHOLD_MULTIPLIER
        Log.d(TAG, "Noise calibrated. RMS: $noiseFloorRms, Threshold: $speechThreshold")
        isNoiseEstimated = true
        listener.onNoiseEstimated(noiseFloorRms, speechThreshold)
    }

    private fun processFrame() {
        if (!isNoiseEstimated) return

        var sumSq = 0.0
        for (i in buffer.indices) {
            val sample = buffer[i].toDouble()
            sumSq += sample * sample
        }
        val rms = sqrt(sumSq / buffer.size)
        
        val isSpeech = rms > speechThreshold

        if (!speechStarted) {
            if (isSpeech) {
                consecutiveSpeechFrames++
                if (consecutiveSpeechFrames >= MIN_SPEECH_FRAMES) {
                    speechStarted = true
                    consecutiveSpeechFrames = 0
                    Log.d(TAG, "Speech started")
                    listener.onSpeechStarted()
                }
            } else {
                consecutiveSpeechFrames = 0
            }
        } else {
            if (isSpeech) {
                silenceStartMs = 0 // Reset silence timer
            } else {
                if (silenceStartMs == 0L) {
                    silenceStartMs = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - silenceStartMs >= MIN_SILENCE_DURATION_MS) {
                    Log.d(TAG, "Silence detected for ${MIN_SILENCE_DURATION_MS}ms, triggering finalize")
                    listener.onSilenceDetected()
                    silenceStartMs = 0 // Reset to avoid repeated triggers
                }
            }
        }
        
        listener.onRmsUpdated(rms, speechThreshold)
    }

    interface VadListener {
        fun onNoiseEstimated(noiseFloorRms: Double, speechThreshold: Double)
        fun onSpeechStarted()
        fun onSilenceDetected()
        fun onRmsUpdated(rms: Double, threshold: Double)
    }
}