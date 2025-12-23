package com.joctv.agent.tts

import android.content.Context
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class TTSManager(private val context: Context, private val ttsListener: TTSListener) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    
    companion object {
        const val TAG = "TTSManager"
    }
    
    init {
        Log.d(TAG, "TTS_INIT")
        tts = TextToSpeech(context, this)
    }
    
    fun isReady(): Boolean {
        return isInitialized
    }
    
    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.CHINESE)
            if (result == TextToSpeech.LANG_MISSING_DATA || 
                result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e(TAG, "TTS_STATE=TTS_ERROR reason=language_not_supported")
                ttsListener.onTTSError("Language not supported")
            } else {
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                
                // 设置语音完成监听器
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS_STATE=TTS_SPEAK")
                        ttsListener.onTTSSpeak()
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS_STATE=TTS_DONE")
                        ttsListener.onTTSDone()
                    }
                    
                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS_STATE=TTS_ERROR reason=speech_error")
                        ttsListener.onTTSError("Speech error")
                    }
                })
                
                isInitialized = true
                Log.d(TAG, "TTS_STATE=TTS_READY")
                ttsListener.onTTSReady()
            }
        } else {
            Log.e(TAG, "TTS_STATE=TTS_ERROR reason=initialization_failed")
            ttsListener.onTTSError("Initialization failed")
        }
    }
    
    fun speak(text: String, flush: Boolean = true) {
        if (!isInitialized) {
            Log.w(TAG, "TTS_STATE=TTS_ERROR reason=not_initialized")
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(text, mode, null, "utteranceId")
        } else {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            @Suppress("DEPRECATION")
            tts?.speak(text, mode, null)
        }
    }
    
    fun stop() {
        tts?.stop()
    }
    
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        isInitialized = false
    }
    
    interface TTSListener {
        fun onTTSReady()
        fun onTTSSpeak()
        fun onTTSDone()
        fun onTTSError(error: String)
    }
}