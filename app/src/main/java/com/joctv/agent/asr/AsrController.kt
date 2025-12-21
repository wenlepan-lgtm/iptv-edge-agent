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
        private const val MIN_VAD_RMS = 120.0
        private const val SILENCE_DURATION_MS = 800L
        private const val COOLDOWN_MS = 800L
        private const val PARTIAL_THROTTLE_MS = 200L
    }

    // ASR 状态机枚举
    enum class AsrState {
        IDLE, LISTENING, FINAL, COOLDOWN
    }

    private var isRunning = false
    private var recordingThread: Thread? = null
    
    private val buffer = ShortArray(BUFFER_SIZE)
    private var currentRecognizer: Recognizer? = null
    private var currentState = AsrState.IDLE
    private var lastPartial = ""
    private var lastPartialCallbackTime = 0L
    private var lastVoiceTime = 0L
    private var isInSpeech = false
    private var cooldownEndTime = 0L
    private var callbacksEnabled = true
    private var sessionId = 0
    private var partialEmitCount = 0
    private var finalEmitCount = 0
    private var voiceStartTime = 0L
    private var inVoiceSession = false

    fun startContinuousAsr() {
        stopContinuousAsr() // Stop any existing recording
        
        isRunning = true
        currentState = AsrState.LISTENING
        lastPartial = ""
        lastPartialCallbackTime = 0L
        lastVoiceTime = System.currentTimeMillis()
        isInSpeech = false
        cooldownEndTime = 0L
        callbacksEnabled = true
        sessionId++
        partialEmitCount = 0
        finalEmitCount = 0
        inVoiceSession = false
        
        Log.d("ASR", "ASR_STATE=LISTENING")
        Log.d("ASR", "ASR_SESSION=$sessionId START")
        
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
                
                while (isRunning) {
                    val read = localRecord.read(buffer, 0, buffer.size)
                    
                    // Calculate RMS for logging
                    var sum = 0.0
                    if (read > 0) {
                        for (i in 0 until read) {
                            val sample = buffer[i].toInt()
                            sum += sample * sample
                        }
                    }
                    val rms = if (read > 0) sqrt(sum / read) else 0.0
                    
                    // Log RMS and inSpeech state
                    Log.d("ASR", "RMS=$rms inSpeech=$isInSpeech")
                    
                    // State machine processing
                    processStateMachine(localRecord, rms, read)
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
        Log.d("ASR", "ASR_SESSION=$sessionId END partialEmits=$partialEmitCount finalEmits=$finalEmitCount")
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

    /**
     * ASR 状态机处理函数
     */
    private fun processStateMachine(localRecord: AudioRecord, rms: Double, read: Int) {
        val currentTime = System.currentTimeMillis()
        
        when (currentState) {
            AsrState.LISTENING -> {
                // 更新语音活动状态
                val wasInSpeech = isInSpeech
                isInSpeech = rms >= MIN_VAD_RMS
                
                // 检查语音活动开始/结束
                if (!wasInSpeech && isInSpeech) {
                    voiceStartTime = currentTime
                    if (!inVoiceSession) {
                        inVoiceSession = true
                        Log.d("ASR", "VOICE_START rms=$rms threshold=$MIN_VAD_RMS")
                    }
                } else if (wasInSpeech && !isInSpeech) {
                    lastVoiceTime = currentTime
                    if (inVoiceSession) {
                        inVoiceSession = false
                        Log.d("ASR", "VOICE_END rms=$rms threshold=$MIN_VAD_RMS")
                    }
                }
                
                // 检查静音超时
                if (!isInSpeech && currentTime - lastVoiceTime >= SILENCE_DURATION_MS && inVoiceSession) {
                    // 触发 final result
                    val silenceMs = currentTime - lastVoiceTime
                    Log.d("ASR", "FINAL_TRIGGER reason=silence silenceMs=$silenceMs")
                    triggerFinalResult()
                    currentState = AsrState.FINAL
                    Log.d("ASR", "ASR_STATE=FINAL")
                } else {
                    // 处理正常的音频数据
                    processAudioData(localRecord, read, rms)
                }
            }
            
            AsrState.FINAL -> {
                // 进入冷却状态
                cooldownEndTime = currentTime + COOLDOWN_MS
                callbacksEnabled = false
                currentState = AsrState.COOLDOWN
                Log.d("ASR", "ASR_STATE=COOLDOWN")
            }
            
            AsrState.COOLDOWN -> {
                // 冷却时间结束后回到监听状态
                if (currentTime >= cooldownEndTime) {
                    currentState = AsrState.LISTENING
                    callbacksEnabled = true
                    lastVoiceTime = currentTime // 修复：初始化 lastVoiceTime
                    inVoiceSession = false
                    Log.d("ASR", "ASR_STATE=LISTENING")
                }
                // 在冷却期间仍然处理音频数据，但不触发任何回调
                processAudioData(localRecord, read, rms)
            }
            
            AsrState.IDLE -> {
                // 不应该到达这里，但为了完整性
                currentState = AsrState.LISTENING
                callbacksEnabled = true
                lastVoiceTime = currentTime // 修复：初始化 lastVoiceTime
                Log.d("ASR", "ASR_STATE=LISTENING")
            }
        }
    }
    
    /**
     * 处理音频数据
     */
    private fun processAudioData(localRecord: AudioRecord, read: Int, rms: Double) {
        // Skip processing if no data read
        if (read <= 0) {
            return
        }
        
        // Log RMS info
        Log.d("ASR", "RMS=$rms inSpeech=$isInSpeech threshold=$MIN_VAD_RMS")
        
        // Convert ShortArray to ByteArray for Vosk
        val byteArray = ShortArrayToByteArray(buffer, read)
        
        // Get current recognizer
        val recognizer = currentRecognizer
        if (recognizer == null) {
            return
        }
        
        // Call acceptWaveForm with proper Boolean return value
        val ok: Boolean = recognizer.acceptWaveForm(byteArray, byteArray.size)
        // Get result or partialResult based on ok value
        val json = if (ok) {
            recognizer.result ?: ""
        } else {
            recognizer.partialResult ?: ""
        }
        
        // Process result only if json is not blank
        if (json.isNotBlank()) {
            try {
                val jsonObj = JSONObject(json)
                
                // Parse partial result
                if (jsonObj.has("partial")) {
                    val partial = jsonObj.optString("partial", "")
                    handlePartialResult(partial)
                }
                
                // Handle final result (should not happen in normal flow)
                if (jsonObj.has("text")) {
                    val text = jsonObj.optString("text", "")
                    if (text.isNotEmpty()) {
                        Log.d("ASR", "final=$text")
                        listener.onAsrResult(text, true)
                        lastPartial = ""
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing JSON result", e)
            }
        }
    }
    
    /**
     * 处理 partial 结果
     */
    private fun handlePartialResult(partial: String) {
        // 在 COOLDOWN 期间禁止任何回调
        if (!callbacksEnabled) {
            return
        }
        
        val currentTime = System.currentTimeMillis()
        
        // 仅在以下条件成立时才回调 partial：
        // 1. partial 非空
        // 2. 与上一次 partial 不相同
        // 3. 节流控制 ≥ 200ms
        if (partial.isNotEmpty() && 
            partial != lastPartial && 
            currentTime - lastPartialCallbackTime >= PARTIAL_THROTTLE_MS) {
            
            partialEmitCount++
            Log.d("ASR", "PARTIAL_EMIT count=$partialEmitCount text=$partial")
            Log.d("ASR", "partial=$partial")
            listener.onAsrResult(partial, false)
            lastPartial = partial
            lastPartialCallbackTime = currentTime
        }
        // 空字符串 partial 直接忽略，不打印日志
    }
    
    /**
     * 触发 final 结果
     */
    private fun triggerFinalResult() {
        // 在 COOLDOWN 期间禁止任何回调
        if (!callbacksEnabled) {
            return
        }
        
        val recognizer = currentRecognizer
        if (recognizer != null) {
            val finalResult = recognizer.finalResult
            if (finalResult.isNotBlank()) {
                try {
                    val jsonObj = JSONObject(finalResult)
                    if (jsonObj.has("text")) {
                        val text = jsonObj.optString("text", "")
                        if (text.isNotEmpty()) {
                            finalEmitCount++
                            Log.d("ASR", "FINAL_EMIT count=$finalEmitCount text=$text")
                            Log.d("ASR", "final=$text")
                            listener.onAsrResult(text, true)
                            // 清空 lastPartial
                            lastPartial = ""
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing final result", e)
                }
            }
        }
        
        // 重置识别器
        resetRecognizer()
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