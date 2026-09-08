package com.example.choppermobile

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log

class ChopperVoiceInteractionService :
    VoiceInteractionService() {

    private val mainHandler =
        Handler(Looper.getMainLooper())

    override fun onReady() {
        super.onReady()

        activeService = this

        Log.i(
            TAG,
            "Chopper is ready as the digital assistant."
        )
    }

    override fun onShutdown() {
        if (activeService === this) {
            activeService = null
        }

        Log.i(
            TAG,
            "Chopper digital assistant stopped."
        )

        super.onShutdown()
    }

    private fun displayAssistantSession(
        wakePhrase: String
    ) {
        val sessionArguments =
            Bundle().apply {
                putString(
                    EXTRA_WAKE_PHRASE,
                    wakePhrase
                )

                putBoolean(
                    EXTRA_STARTED_BY_WAKE_WORD,
                    true
                )
            }

        mainHandler.post {
            try {
                showSession(
                    sessionArguments,
                    VoiceInteractionSession
                        .SHOW_WITH_ASSIST
                )

                Log.i(
                    TAG,
                    "Assistant session requested for: $wakePhrase"
                )
            } catch (error: Exception) {
                Log.e(
                    TAG,
                    "Unable to show assistant session.",
                    error
                )
            }
        }
    }

    companion object {

        private const val TAG =
            "ChopperAssistant"

        const val EXTRA_WAKE_PHRASE =
            "chopper_wake_phrase"

        const val EXTRA_STARTED_BY_WAKE_WORD =
            "chopper_started_by_wake_word"

        @Volatile
        private var activeService:
                ChopperVoiceInteractionService? =
            null

        fun showWakeWordSession(
            wakePhrase: String
        ): Boolean {

            val service =
                activeService
                    ?: return false

            service.displayAssistantSession(
                wakePhrase
            )

            return true
        }

        fun isAssistantReady(): Boolean {
            return activeService != null
        }
    }
}