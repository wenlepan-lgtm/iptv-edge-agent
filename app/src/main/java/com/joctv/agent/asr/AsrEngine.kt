package com.joctv.agent.asr

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import org.json.JSONArray
import org.vosk.Model
import org.vosk.Recognizer
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.sqrt

class AsrEngine(
    private val model: Model,
    private val listener: AsrListener
) {
    companion object {
        const val TAG = "ASR_ENGINE"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val FRAME_SIZE_MS = 20 // 20ms frame
        private const val COMMAND_TIMEOUT_MS = 6000L // 6 seconds
    }

    private var currentState: State = State.Idle
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recognizer: Recognizer? = null
    private var commandTimeoutThread: Thread? = null
    private var vad: VAD? = null

    private val frameSize = SAMPLE_RATE * FRAME_SIZE_MS / 1000
    private val buffer = ShortArray(frameSize)

    enum class State {
        Idle, ListeningWakeword, ListeningCommand
    }

    fun start() {
        startListeningWakeword()
    }

    fun stop() {
        stopRecording()
        vad?.stopRecording()
    }

    private fun startListeningWakeword() {
        currentState = State.ListeningWakeword
        listener.onStateChanged(currentState)
        startRecording(createWakewordRecognizer())
    }

    private fun startListeningCommand() {
        currentState = State.ListeningCommand
        listener.onStateChanged(currentState)
        
        // Start VAD
        vad = VAD(object : VAD.VadListener {
            override fun onSilenceDetected() {
                Log.d(VAD.TAG, "VAD silence detected, finalizing command")
                finalizeCommand()
            }
        })
        vad?.startRecording()
        
        // Start timeout timer
        commandTimeoutThread = thread {
            Thread.sleep(COMMAND_TIMEOUT_MS)
            if (currentState == State.ListeningCommand) {
                Log.d(TAG, "Command timeout reached, finalizing command")
                finalizeCommand()
            }
        }
        
        startRecording(createCommandRecognizer())
    }

    private fun startRecording(recognizer: Recognizer) {
        stopRecording() // Stop any existing recording
        
        this.recognizer = recognizer
        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        val bufferSize = frameSize * 4 // Quadruple buffer size for safety

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord initialization failed")
            return
        }

        audioRecord?.startRecording()
        isRecording = true

        thread {
            while (isRecording) {
                val read = audioRecord?.read(buffer, 0, frameSize) ?: 0
                if (read > 0) {
                    // Convert ShortArray to ByteArray for Vosk
                    val byteArray = ShortArrayToByteArray(buffer, read)
                    
                    // Feed to recognizer
                    if (recognizer.acceptWaveForm(byteArray, read * 2)) {
                        val result = recognizer.result
                        processResult(result, true)
                    } else {
                        val partialResult = recognizer.partialResult
                        processResult(partialResult, false)
                    }
                }
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        recognizer = null
    }

    private fun createWakewordRecognizer(): Recognizer {
        // Create grammar JSON for wakeword
        val grammar = JSONArray()
        grammar.put("小智")
        grammar.put("小智。")
        grammar.put("小志")
        val grammarStr = grammar.toString()
        Log.d(TAG, "Wakeword grammar: $grammarStr")
        return Recognizer(model, SAMPLE_RATE.toFloat(), grammarStr)
    }

    private fun createCommandRecognizer(): Recognizer {
        // Create grammar JSON for hotwords and common phrases
        val grammar = JSONArray()
        Hotwords.LIST.forEach { word ->
            grammar.put(word)
        }
        // Add some common phrases
        grammar.put("我要退房")
        grammar.put("早餐几点开始")
        grammar.put("Wi-Fi 密码是多少")
        grammar.put("空调怎么开")
        grammar.put("冰箱在哪里")
        val grammarStr = grammar.toString()
        Log.d(TAG, "Command grammar: $grammarStr")
        return Recognizer(model, SAMPLE_RATE.toFloat(), grammarStr)
    }

    private fun processResult(json: String, isFinal: Boolean) {
        try {
            listener.onResult(json, isFinal)
            
            if (isFinal) {
                val text = extractTextFromJson(json)
                when (currentState) {
                    State.ListeningWakeword -> {
                        if (text.contains("小智")) {
                            Log.d(TAG, "Wakeword detected: $text")
                            stopRecording()
                            startListeningCommand()
                        } else {
                            // Continue listening for wakeword
                            startListeningWakeword()
                        }
                    }
                    State.ListeningCommand -> {
                        Log.d(TAG, "Command recognized: $text")
                        // VAD or timeout will handle finalization
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing result", e)
        }
    }

    private fun finalizeCommand() {
        // Stop recording and VAD
        stopRecording()
        vad?.stopRecording()
        commandTimeoutThread?.interrupt()
        
        // Get final result
        recognizer?.let {
            val finalResult = it.finalResult
            listener.onResult(finalResult, true)
        }
        
        // Transition to next state
        listener.onCommandFinalized()
        
        // Return to wakeword listening
        startListeningWakeword()
    }

    private fun extractTextFromJson(json: String): String {
        try {
            val jsonObj = org.json.JSONObject(json)
            return jsonObj.optString("text", "")
        } catch (e: Exception) {
            Log.e(TAG, "Error extracting text from JSON", e)
            return ""
        }
    }

    private fun ShortArrayToByteArray(shortArray: ShortArray, length: Int): ByteArray {
        val byteBuffer = ByteBuffer.allocate(length * 2)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until length) {
            byteBuffer.putShort(shortArray[i])
        }
        return byteBuffer.array()
    }

    interface AsrListener {
        fun onStateChanged(newState: State)
        fun onResult(json: String, isFinal: Boolean)
        fun onCommandFinalized()
    }
}