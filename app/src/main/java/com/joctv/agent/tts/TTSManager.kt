package com.joctv.agent.tts

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.Engine
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

class TTSManager(private val context: Context, private val ttsListener: TTSListener) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var currentVolume = 1.0f
    private var isSpeaking = false
    
    companion object {
        const val TAG = "TTSManager"
        const val PREFERRED_ENGINE = "com.k2fsa.sherpa.onnx.tts.engine"
    }
    
    init {
        Log.d(TAG, "TTS_INIT starting...")
        try {
            val isEngineInstalled = isPackageInstalled(context, PREFERRED_ENGINE)
            if (isEngineInstalled) {
                Log.d(TAG, "Using preferred engine: $PREFERRED_ENGINE")
                tts = TextToSpeech(context, this, PREFERRED_ENGINE)
            } else {
                Log.w(TAG, "Preferred engine $PREFERRED_ENGINE not found, using system default")
                tts = TextToSpeech(context, this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create TextToSpeech instance", e)
            ttsListener.onTTSError("TTS实例创建失败: ${e.message}")
        }
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
    }
    
    fun isReady(): Boolean = isInitialized
    
    fun isSpeaking(): Boolean = isSpeaking

    override fun onInit(status: Int) {
        Log.d(TAG, "onInit status=$status")
        try {
            if (status == TextToSpeech.SUCCESS) {
                val currentTts = tts
                if (currentTts == null) {
                    Log.e(TAG, "TTS instance is null in onInit")
                    ttsListener.onTTSError("TTS实例异常")
                    return
                }

                // 核心修复：强制设置音频属性为媒体流
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    currentTts.setAudioAttributes(attributes)
                    Log.d(TAG, "AudioAttributes forced to USAGE_MEDIA")
                }

                val result = currentTts.setLanguage(Locale.CHINESE)
                Log.d(TAG, "setLanguage result=$result")
                
                if (result == TextToSpeech.LANG_MISSING_DATA || 
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.e(TAG, "TTS_STATE=TTS_ERROR reason=language_not_supported")
                    ttsListener.onTTSError("中文语音包未安装或不支持")
                } else {
                    currentTts.setSpeechRate(1.0f)
                    currentTts.setPitch(1.0f)
                    
                    currentTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                        isSpeaking = true
                        Log.d(TAG, "TTS_STATE=TTS_SPEAK id=$utteranceId")
                        ttsListener.onTTSStart() // 新增：通知 TTS 开始
                        ttsListener.onTTSSpeak()
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        isSpeaking = false
                        Log.d(TAG, "TTS_STATE=TTS_DONE id=$utteranceId")
                        ttsListener.onTTSDone()
                    }
                        
                        override fun onError(utteranceId: String?) {
                            isSpeaking = false
                            Log.e(TAG, "TTS_STATE=TTS_ERROR reason=speech_error id=$utteranceId")
                            ttsListener.onTTSError("语音合成失败")
                        }

                        override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                            ttsListener.onTTSProgress(start, end)
                        }
                    })
                    
                    isInitialized = true
                    Log.d(TAG, "TTS_STATE=TTS_READY")
                    ttsListener.onTTSReady()
                }
            } else {
                Log.e(TAG, "TTS_STATE=TTS_ERROR reason=initialization_failed status=$status")
                ttsListener.onTTSError("TTS引擎初始化失败(status=$status)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception in onInit", e)
            ttsListener.onTTSError("初始化异常: ${e.message}")
        }
    }
    
    fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0.0f, 1.0f)
    }

    fun speak(text: String, flush: Boolean = true) {
        if (!isInitialized || text.isEmpty()) return
        
        Log.d(TAG, "speak text: $text")
        
        val maxLen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            TextToSpeech.getMaxSpeechInputLength()
        } else {
            4000
        }

        val params = Bundle()
        params.putFloat(Engine.KEY_PARAM_VOLUME, currentVolume)
        params.putInt(Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)

        if (text.length <= maxLen) {
            val mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            tts?.speak(text, mode, params, "utterance_" + System.currentTimeMillis())
        } else {
            val parts = text.chunked(maxLen)
            var first = true
            parts.forEach { part ->
                val mode = if (first && flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                tts?.speak(part, mode, params, "utterance_part_" + System.currentTimeMillis())
                first = false
            }
        }
    }
    
    fun stop() {
        tts?.stop()
        isSpeaking = false
    }
    
    fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "Error during shutdown", e)
        }
        isInitialized = false
        isSpeaking = false
    }
    
    interface TTSListener {
        fun onTTSReady()
        fun onTTSSpeak()
        fun onTTSDone()
        fun onTTSError(error: String)
        fun onTTSProgress(start: Int, end: Int)
        fun onTTSStart() // 新增：TTS 开始回调
    }
}
