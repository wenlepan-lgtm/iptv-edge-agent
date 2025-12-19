package com.example.androidtvspeechrecognition

import android.app.Application
import android.util.Log

class SpeechRecognitionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Application created")
    }

    companion object {
        private const val TAG = "SpeechRecognitionApp"
    }
}