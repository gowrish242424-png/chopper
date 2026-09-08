package com.example.choppermobile

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

class ChopperVoiceSessionService :
    VoiceInteractionSessionService() {

    override fun onNewSession(
        arguments: Bundle?
    ): VoiceInteractionSession {

        Log.i(
            TAG,
            "Creating Chopper assistant session."
        )

        return ChopperVoiceInteractionSession(
            this
        )
    }

    companion object {
        private const val TAG =
            "ChopperAssistant"
    }
}