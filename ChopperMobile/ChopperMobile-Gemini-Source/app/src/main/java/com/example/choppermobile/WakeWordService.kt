package com.example.choppermobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import kotlin.concurrent.thread

class WakeWordService : Service() {

    companion object {
        const val ACTION_START =
            "com.example.choppermobile.START_WAKE_WORD"
        const val ACTION_PAUSE = "com.example.choppermobile.ACTION_PAUSE"
        const val ACTION_RESUME = "com.example.choppermobile.ACTION_RESUME"
        const val ACTION_STOP =
            "com.example.choppermobile.STOP_WAKE_WORD"

        const val EXTRA_WAKE_WORD_TRIGGERED =
            "wake_word_triggered"

        const val EXTRA_DETECTED_KEYWORD =
            "detected_keyword"

        private const val TAG =
            "ChopperWakeWord"

        private const val NOTIFICATION_CHANNEL_ID =
            "chopper_wake_word_channel"

        private const val NOTIFICATION_ID =
            2401

        private const val SAMPLE_RATE =
            16000

        private const val FEATURE_DIM =
            80

        private const val DETECTION_DELAY =
            2500L
    }

    private var keywordSpotter:
            KeywordSpotter? = null

    private var keywordStream:
            OnlineStream? = null

    private var audioRecord:
            AudioRecord? = null

    private var recordingThread:
            Thread? = null

    @Volatile
    private var isListening =
        false

    private var lastDetectionTime =
        0L

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (
            intent?.action ==
            ACTION_STOP
        ) {
            stopWakeWordListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()

            return START_NOT_STICKY
        }

        startForeground(
            NOTIFICATION_ID,
            createNotification()
        )

        if (!isListening) {
            startWakeWordListening()
        }

        return START_STICKY
    }

    private fun startWakeWordListening() {
        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(
                TAG,
                "Microphone permission is not granted."
            )

            stopSelf()
            return
        }

        try {
            initializeKeywordSpotter()

            val minimumBufferSize =
                AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

            if (
                minimumBufferSize ==
                AudioRecord.ERROR ||
                minimumBufferSize ==
                AudioRecord.ERROR_BAD_VALUE
            ) {
                throw IllegalStateException(
                    "Unable to calculate microphone buffer size."
                )
            }

            val finalBufferSize =
                maxOf(
                    minimumBufferSize * 2,
                    SAMPLE_RATE
                )

            audioRecord =
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    finalBufferSize
                )

            if (
                audioRecord?.state !=
                AudioRecord.STATE_INITIALIZED
            ) {
                throw IllegalStateException(
                    "Microphone could not be initialized."
                )
            }

            audioRecord?.startRecording()

            isListening = true

            recordingThread =
                thread(
                    start = true,
                    name = "ChopperWakeWordThread"
                ) {
                    processMicrophoneAudio()
                }

            Log.i(
                TAG,
                "Wake-word listening started."
            )

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Wake-word startup failed.",
                error
            )

            stopWakeWordListening()
            stopSelf()
        }
    }

    private fun initializeKeywordSpotter() {
        keywordStream?.release()
        keywordSpotter?.release()

        val transducerConfig =
            OnlineTransducerModelConfig(
                encoder = "kws/encoder.onnx",
                decoder = "kws/decoder.onnx",
                joiner = "kws/joiner.onnx"
            )

        val modelConfig =
            OnlineModelConfig(
                transducer = transducerConfig,
                tokens = "kws/tokens.txt",
                numThreads = 2,
                debug = false,
                provider = "cpu"
            )

        val spotterConfig =
            KeywordSpotterConfig(
                featConfig =
                    getFeatureConfig(
                        sampleRate = SAMPLE_RATE,
                        featureDim = FEATURE_DIM
                    ),
                modelConfig = modelConfig,
                keywordsFile =
                    "kws/keywords.txt"
            )

        keywordSpotter =
            KeywordSpotter(
                assetManager = assets,
                config = spotterConfig
            )

        keywordStream =
            keywordSpotter?.createStream()

        Log.i(
            TAG,
            "Sherpa-ONNX wake-word model loaded."
        )
    }

    private fun processMicrophoneAudio() {
        val sampleBuffer =
            ShortArray(1600)

        try {
            while (isListening) {
                val numberOfSamples =
                    audioRecord?.read(
                        sampleBuffer,
                        0,
                        sampleBuffer.size
                    ) ?: 0

                if (numberOfSamples <= 0) {
                    continue
                }

                val floatSamples =
                    FloatArray(numberOfSamples) {
                            index ->

                        sampleBuffer[index] /
                                32768.0f
                    }

                val currentSpotter =
                    keywordSpotter
                        ?: continue

                val currentStream =
                    keywordStream
                        ?: continue

                currentStream.acceptWaveform(
                    floatSamples,
                    sampleRate = SAMPLE_RATE
                )

                while (
                    currentSpotter.isReady(
                        currentStream
                    )
                ) {
                    currentSpotter.decode(
                        currentStream
                    )
                }

                val result =
                    currentSpotter.getResult(
                        currentStream
                    )

                if (
                    result.keyword.isNotBlank()
                ) {
                    Log.i(
                        TAG,
                        "Detected wake word: " +
                                result.keyword
                    )

                    currentSpotter.reset(
                        currentStream
                    )

                    handleWakeWordDetected(
                        result.keyword
                    )
                }
            }

        } catch (error: Exception) {
            if (isListening) {
                Log.e(
                    TAG,
                    "Wake-word listening failed.",
                    error
                )
            }
        }
    }

    private fun handleWakeWordDetected(
        keyword: String
    ) {
        val currentTime =
            SystemClock.elapsedRealtime()

        if (
            currentTime -
            lastDetectionTime <
            DETECTION_DELAY
        ) {
            return
        }

        lastDetectionTime =
            currentTime

        val openChopperIntent =
            Intent(
                this,
                MainActivity::class.java
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP

                putExtra(
                    EXTRA_WAKE_WORD_TRIGGERED,
                    true
                )

                putExtra(
                    EXTRA_DETECTED_KEYWORD,
                    keyword
                )
            }

        startActivity(
            openChopperIntent
        )
    }

    private fun createNotificationChannel() {
        val notificationManager =
            getSystemService(
                NotificationManager::class.java
            )

        val channel =
            NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Chopper wake-word detection",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Allows Chopper to listen for Hey Chopper and Hey Chop."

                setSound(
                    null,
                    null
                )

                enableVibration(
                    false
                )
            }

        notificationManager
            .createNotificationChannel(
                channel
            )
    }

    private fun createNotification():
            Notification {

        val openChopperIntent =
            Intent(
                this,
                MainActivity::class.java
            )

        val openPendingIntent =
            PendingIntent.getActivity(
                this,
                0,
                openChopperIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        val stopServiceIntent =
            Intent(
                this,
                WakeWordService::class.java
            ).apply {
                action =
                    ACTION_STOP
            }

        val stopPendingIntent =
            PendingIntent.getService(
                this,
                1,
                stopServiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                        PendingIntent.FLAG_IMMUTABLE
            )

        return NotificationCompat
            .Builder(
                this,
                NOTIFICATION_CHANNEL_ID
            )
            .setSmallIcon(
                android.R.drawable
                    .ic_btn_speak_now
            )
            .setContentTitle(
                "Chopper voice activation"
            )
            .setContentText(
                "Listening for “Hey Chopper” and “Hey Chop”"
            )
            .setContentIntent(
                openPendingIntent
            )
            .setOngoing(
                true
            )
            .setSilent(
                true
            )
            .setPriority(
                NotificationCompat
                    .PRIORITY_LOW
            )
            .addAction(
                android.R.drawable
                    .ic_menu_close_clear_cancel,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    private fun stopWakeWordListening() {
        isListening =
            false

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }

        try {
            audioRecord?.release()
        } catch (_: Exception) {
        }

        audioRecord =
            null

        if (
            recordingThread !=
            Thread.currentThread()
        ) {
            try {
                recordingThread?.join(
                    1000
                )
            } catch (_: InterruptedException) {
            }
        }

        recordingThread =
            null

        try {
            keywordStream?.release()
        } catch (_: Exception) {
        }

        keywordStream =
            null

        try {
            keywordSpotter?.release()
        } catch (_: Exception) {
        }

        keywordSpotter =
            null

        Log.i(
            TAG,
            "Wake-word listening stopped."
        )
    }

    override fun onDestroy() {
        stopWakeWordListening()

        super.onDestroy()
    }

    override fun onBind(
        intent: Intent?
    ): IBinder? {
        return null
    }
}