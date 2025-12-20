package com.joctv.agent.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.min
import kotlin.math.sqrt

class AsrController(
    private val model: Model,
    private val listener: AsrListener
) {
    companion object {
        const val TAG = "ASR_Controller"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE = 1600 // 100ms buffer
        private const val WARMUP_DURATION_MS = 500L // Warmup duration in ms
        private const val NOISE_SAMPLING_DURATION_MS = 1000L // Noise sampling duration in ms
        private const val SILENCE_DURATION_MS = 800L // Silence duration for finalize
        private const val MIN_VAD_RMS = 120.0 // Minimum VAD RMS threshold
    }

    private var isRunning = false
    private var recordingThread: Thread? = null
    
    private val buffer = ShortArray(BUFFER_SIZE)
    private var currentRecognizer: Recognizer? = null

    fun startContinuousAsr() {
        stopContinuousAsr() // Stop any existing recording
        
        isRunning = true
        
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = BUFFER_SIZE * 2 // Double buffer size for safety

        // Try different audio sources in order of preference
        val audioSources = arrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.CAMCORDER,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION
        )
        
        var localRecord: AudioRecord? = null
        var selectedAudioSource = -1
        
        // Try each audio source until one works
        for (source in audioSources) {
            try {
                val record = AudioRecord(
                    source,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    bufferSize
                )
                
                if (record.state == AudioRecord.STATE_INITIALIZED) {
                    localRecord = record
                    selectedAudioSource = source
                    break
                } else {
                    record.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize AudioRecord with source: $source", e)
            }
        }
        
        // If no audio source worked, fallback to VOICE_RECOGNITION
        if (localRecord == null) {
            selectedAudioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
            localRecord = AudioRecord(
                selectedAudioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
        }
        
        // Create initial recognizer without grammar
        currentRecognizer = Recognizer(model, SAMPLE_RATE.toFloat())

        // Log AudioRecord initialization details
        Log.i("AudioRecord", "Initialized with:")
        Log.i("AudioRecord", "  audioSource: $selectedAudioSource")
        Log.i("AudioRecord", "  sampleRate: $SAMPLE_RATE")
        Log.i("AudioRecord", "  channelConfig: $CHANNEL_CONFIG")
        Log.i("AudioRecord", "  audioFormat: $AUDIO_FORMAT")
        Log.i("AudioRecord", "  minBufferSize: $minBufferSize")
        Log.i("AudioRecord", "  actual bufferSize: $bufferSize")
        Log.i("AudioRecord", "  state: ${localRecord.state}")
        Log.i("AudioRecord", "  recordingState: ${localRecord.recordingState}")

        if (localRecord.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioRecord", "AudioRecord initialization failed")
            localRecord.release()
            return
        }

        val startResult = localRecord.startRecording()
        
        // Log recording state after start
        Log.i("AudioRecord", "After startRecording:")
        Log.i("AudioRecord", "  startRecording result: $startResult")
        Log.i("AudioRecord", "  state: ${localRecord.state}")
        Log.i("AudioRecord", "  recordingState: ${localRecord.recordingState}")

        recordingThread = thread {
            try {
                // Reset recognizer at start
                resetRecognizer()
                
                // Warmup phase - discard first 500ms of audio
                val warmupEndTime = System.currentTimeMillis() + WARMUP_DURATION_MS
                while (isRunning && System.currentTimeMillis() < warmupEndTime) {
                    val read = localRecord.read(buffer, 0, buffer.size)
                    // Discard audio during warmup
                }
                Log.i("AudioRecord", "Warmup completed, discarded first ${WARMUP_DURATION_MS}ms")
                
                // Noise sampling phase - sample for 1 second to calculate noise floor
                var noiseSum = 0.0
                var noiseSampleCount = 0
                val noiseSamplingEndTime = System.currentTimeMillis() + NOISE_SAMPLING_DURATION_MS
                while (isRunning && System.currentTimeMillis() < noiseSamplingEndTime) {
                    val read = localRecord.read(buffer, 0, buffer.size)
                    
                    // Calculate RMS for noise sampling
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            val sample = buffer[i].toInt()
                            sum += sample * sample
                        }
                        val rms = sqrt(sum / read)
                        noiseSum += rms
                        noiseSampleCount++
                    }
                }
                
                // Calculate noise RMS and VAD threshold
                val noiseRms = if (noiseSampleCount > 0) noiseSum / noiseSampleCount else 0.0
                val vadRms = kotlin.math.max(noiseRms * 3, MIN_VAD_RMS)
                Log.i("AudioRecord", "Noise sampling completed: noiseRms=$noiseRms, vadRms=$vadRms")
                
                // Main processing loop
                var lastVoiceTime = System.currentTimeMillis()
                var isInSpeech = false
                var lastPartial = ""
                
                while (isRunning) {
                    val read = localRecord.read(buffer, 0, buffer.size)
                    
                    // Calculate RMS and peak for logging
                    var sum = 0.0
                    var peak = 0
                    if (read > 0) {
                        for (i in 0 until read) {
                            val sample = buffer[i].toInt()
                            sum += sample * sample
                            if (Math.abs(sample) > peak) {
                                peak = Math.abs(sample)
                            }
                        }
                    }
                    val rms = if (read > 0) sqrt(sum / read) else 0.0
                    
                    // Log AudioRecord read result
                    Log.i("AudioRecord", "read=$read rms=$rms peak=$peak")
                    
                    // Check for voice activity using dynamic threshold
                    val wasInSpeech = isInSpeech
                    isInSpeech = rms >= vadRms
                    
                    // Check for silence timeout - only trigger when transitioning from speech to silence
                    val currentTime = System.currentTimeMillis()
                    if (wasInSpeech && !isInSpeech && currentTime - lastVoiceTime >= SILENCE_DURATION_MS) {
                        // Get final result when silence detected after speech
                        val recognizer = currentRecognizer
                        if (recognizer != null) {
                            val finalResult = recognizer.finalResult
                            if (finalResult.isNotBlank()) {
                                Log.d("ASR", "final=$finalResult")
                                try {
                                    val jsonObj = JSONObject(finalResult)
                                    if (jsonObj.has("text")) {
                                        val text = jsonObj.optString("text", "")
                                        if (text.isNotEmpty()) {
                                            Log.d("ASR", "final_text=$text")
                                            listener.onAsrResult(text, true)
                                            // Clear lastPartial after final result
                                            lastPartial = ""
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error parsing final result", e)
                                }
                            }
                        }
                        
                        // Reset recognizer for next utterance
                        resetRecognizer()
                        // Reset speech state to prevent repeated triggering
                        isInSpeech = false
                        
                        // Reset lastVoiceTime to prevent continuous firing
                        lastVoiceTime = currentTime
                    }
                    
                    // Update last voice time if in speech
                    if (isInSpeech) {
                        lastVoiceTime = currentTime
                    }
                    
                    // Skip processing if no data read
                    if (read <= 0) {
                        continue
                    }
                    
                    // Convert ShortArray to ByteArray for Vosk
                    val byteArray = ShortArrayToByteArray(buffer, read)
                    
                    // Get current recognizer
                    val recognizer = currentRecognizer
                    if (recognizer == null) {
                        continue
                    }
                    
                    // Call acceptWaveForm with proper Boolean return value
                    val ok: Boolean = recognizer.acceptWaveForm(byteArray, byteArray.size)
                    // Get result or partialResult based on ok value
                    val json = if (ok) {
                        recognizer.result ?: ""
                    } else {
                        recognizer.partialResult ?: ""
                    }
                    
                    // Log Recognizer result
                    Log.d("ASR", "ok=$ok json=$json")
                    
                    // Process result only if json is not blank
                    if (json.isNotBlank()) {
                        try {
                            val jsonObj = JSONObject(json)
                            
                            // Parse partial result
                            if (jsonObj.has("partial")) {
                                val partial = jsonObj.optString("partial", "")
                                // Only callback if partial is not empty and has changed
                                if (partial.isNotEmpty() && partial != lastPartial) {
                                    Log.d("ASR", "partial=$partial")
                                    listener.onAsrResult(partial, false)
                                    lastPartial = partial
                                }
                                // Skip logging empty partial results
                            }
                            
                            // Handle final result
                            if (jsonObj.has("text")) {
                                val text = jsonObj.optString("text", "")
                                if (text.isNotEmpty()) {
                                    Log.d("ASR", "final_text=$text")
                                    listener.onAsrResult(text, true)
                                    // Clear lastPartial after final result
                                    lastPartial = ""
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error parsing JSON result", e)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error in recording thread", e)
            } finally {
                // Unified resource release
                try {
                    if (localRecord?.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                        localRecord.stop()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error stopping AudioRecord", e)
                }
                
                try {
                    localRecord?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Error releasing AudioRecord", e)
                }
                
                try {
                    currentRecognizer?.close()
                } catch (e: Exception) {
                    Log.w(TAG, "Error closing Recognizer", e)
                }
                
                Log.d(TAG, "Resources released")
            }
        }
    }

    fun stopContinuousAsr() {
        isRunning = false
        recordingThread?.interrupt()
    }

    private fun resetRecognizer() {
        try {
            currentRecognizer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing Recognizer", e)
        }
        
        // Create new recognizer
        currentRecognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        Log.d(TAG, "Recognizer reset")
    }

    private fun ShortArrayToByteArray(shortArray: ShortArray, length: Int): ByteArray {
        val byteBuffer = ByteBuffer.allocate(length * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until min(length, shortArray.size)) {
            byteBuffer.putShort(shortArray[i])
        }
        return byteBuffer.array()
    }

    interface AsrListener {
        fun onAsrResult(text: String, isFinal: Boolean)
    }
}