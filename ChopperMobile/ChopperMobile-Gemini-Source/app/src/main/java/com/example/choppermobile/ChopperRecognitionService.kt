package com.example.choppermobile

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * Required compatibility component for VoiceInteractionService metadata.
 * Chopper's assistant session owns its visible input; this component deliberately
 * reports an unavailable recognizer rather than silently routing audio elsewhere.
 */
class ChopperRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        listener.readyForSpeech(Bundle())
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) = Unit

    override fun onCancel(listener: Callback) = Unit
}
