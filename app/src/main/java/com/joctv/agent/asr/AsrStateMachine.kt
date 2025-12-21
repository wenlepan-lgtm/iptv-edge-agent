package com.joctv.agent.asr

import android.util.Log

class AsrStateMachine(private val listener: StateListener) {
    companion object {
        const val TAG = "ASR_StateMachine"
    }

    enum class State {
        IDLE, LISTEN_CMD, THINKING, SPEAKING
    }

    private var currentState: State = State.IDLE
        set(value) {
            Log.d(TAG, "State changed from ${field.name} to ${value.name}")
            field = value
            listener.onStateChanged(value)
        }

    fun getCurrentState(): State = currentState

    fun onWakewordDetected() {
        if (currentState == State.IDLE) {
            currentState = State.LISTEN_CMD
        }
    }

    fun onCommandTimeout() {
        if (currentState == State.LISTEN_CMD) {
            finalizeCommand("timeout")
        }
    }

    fun onVadSilence() {
        if (currentState == State.LISTEN_CMD) {
            finalizeCommand("silence")
        }
    }

    fun onCommandFinalized() {
        if (currentState == State.LISTEN_CMD) {
            currentState = State.THINKING
            // Simulate thinking delay
            Thread {
                Thread.sleep(300)
                onThinkingComplete()
            }.start()
        }
    }

    private fun finalizeCommand(reason: String) {
        Log.d(TAG, "Finalizing command due to: $reason")
        currentState = State.THINKING
        listener.onFinalizeCommand(reason)
    }

    private fun onThinkingComplete() {
        currentState = State.SPEAKING
        // Simulate speaking delay
        Thread {
            Thread.sleep(1500)
            onSpeakingComplete()
        }.start()
    }

    private fun onSpeakingComplete() {
        currentState = State.IDLE
    }

    interface StateListener {
        fun onStateChanged(newState: State)
        fun onFinalizeCommand(reason: String)
    }
}