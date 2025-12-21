package com.joctv.agent.asr

import android.util.Log

class StateMachine(private val listener: StateListener) {
    companion object {
        const val TAG = "ASR_STATE"
    }

    enum class State {
        Idle, ListenCommand, Thinking, Speaking
    }

    private var currentState: State = State.Idle
        set(value) {
            Log.d(TAG, "State changed from ${field.name} to ${value.name}")
            field = value
            listener.onStateChanged(value)
        }

    fun getCurrentState(): State = currentState

    fun onWakewordDetected() {
        if (currentState == State.Idle) {
            currentState = State.ListenCommand
        }
    }

    fun onCommandTimeout() {
        if (currentState == State.ListenCommand) {
            finalizeCommand()
        }
    }

    fun onVadSilence() {
        if (currentState == State.ListenCommand) {
            finalizeCommand()
        }
    }

    fun onCommandFinalized() {
        if (currentState == State.ListenCommand) {
            currentState = State.Thinking
            // Simulate thinking delay
            Thread {
                Thread.sleep(1000)
                onThinkingComplete()
            }.start()
        }
    }

    private fun finalizeCommand() {
        currentState = State.Thinking
        listener.onFinalizeCommand()
    }

    private fun onThinkingComplete() {
        currentState = State.Speaking
        // Simulate speaking delay
        Thread {
            Thread.sleep(1500)
            onSpeakingComplete()
        }.start()
    }

    private fun onSpeakingComplete() {
        currentState = State.Idle
    }

    interface StateListener {
        fun onStateChanged(newState: State)
        fun onFinalizeCommand()
    }
}