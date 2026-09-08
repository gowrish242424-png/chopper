package com.example.choppermobile

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

class ChopperRecognitionService : RecognitionService() {

    override fun onStartListening(
        recognizerIntent: Intent,
        listener: Callback
    ) {
        listener.readyForSpeech(Bundle())
        listener.error(SpeechRecognizer.ERROR_CLIENT)
    }

    override fun onStopListening(listener: Callback) {
        // No action required yet.
    }

    override fun onCancel(listener: Callback) {
        // No action required.
    }
}