package com.example.choppermobile

// CHOPPER SECURE SEMANTIC MEMORY VERSION 6

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.content.pm.PackageManager
import android.security.keystore.UserNotAuthenticatedException
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.Editable
import android.text.TextWatcher
import android.text.method.LinkMovementMethod
import android.text.util.Linkify
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.FrameLayout
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.example.choppermobile.data.ChatMessage
import com.example.choppermobile.data.ChopperDao
import com.example.choppermobile.data.ChopperDatabase
import com.example.choppermobile.data.Conversation
import com.example.choppermobile.databinding.ActivityMainBinding
import com.example.choppermobile.memory.CommandAction
import com.example.choppermobile.memory.CommandDecision
import com.example.choppermobile.memory.GemmaPromptBuilder
import com.example.choppermobile.memory.MemoryLifetime
import com.example.choppermobile.memory.PrivateMemory
import com.example.choppermobile.memory.PrivateMemoryVault
import com.example.choppermobile.memory.SemanticCommandInterpreter
import com.example.choppermobile.sidebar.ChopperSidebarService
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale


class MainActivity : AppCompatActivity() {

    companion object {
        const val GENERATED_IMAGE_MARKER = "[[CHOPPER_GENERATED_IMAGE]]|"
        const val EXTRA_ASSISTANT_QUERY = "chopper_assistant_query"
        const val EXTRA_ASSISTANT_SCREENSHOT_PATH =
            "chopper_assistant_screenshot_path"
        const val EXTRA_SIDEBAR_CONVERSATION_ID =
            "chopper_sidebar_conversation_id"
        const val EXTRA_OPEN_SECTION = "chopper_open_section"
        const val SECTION_SETTINGS = "settings"
        const val SECTION_PROJECTS = "projects"
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var llmInference: LlmInference
    private lateinit var chopperDao: ChopperDao
    private val privateMemoryVault by lazy {
        PrivateMemoryVault(applicationContext)
    }

    private val commandInterpreter = SemanticCommandInterpreter()
    private val gemmaPromptBuilder = GemmaPromptBuilder()

    private var currentConversationId: Long = 0
    private var restoringMessages = false

    private var conversationTitleSet = false
    private var currentMode = "Auto"
    private var modelReady = false
    private var logoAnimator: ObjectAnimator? = null
    private var selectedImageUri: Uri? = null
    private var pendingCameraUri: Uri? = null
    private var imagePreviewPanel: LinearLayout? = null
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var spokenRepliesEnabled = true
    private var currentSpeakingView: TextView? = null
    private var currentSpeakingText = ""
    private var finalSpeechUtteranceId: String? = null
    private val speechChunkOffsets = mutableMapOf<String, Int>()
    private var speechGeneration = 0L
    private var voiceButtonView: TextView? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var listeningDialog: AlertDialog? = null
    private var listeningStatusText: TextView? = null
    private var wakeStatusPanel: View? = null
    private var isListening = false
    private var conversationModeEnabled = false
    private var modeBeforeConversation = "Auto"
    private var pendingConversationListening = false
    private var manualVoiceStop = false
    private val voiceHandler = Handler(Looper.getMainLooper())
    private var unlockReceiverRegistered = false
    private var waitingForSidebarPermission = false

    private val sidebarPreferences by lazy {
        getSharedPreferences(
            "chopper_sidebar_settings",
            Context.MODE_PRIVATE
        )
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, receivedIntent: Intent?) {
            if (receivedIntent?.action == Intent.ACTION_USER_PRESENT) {
                unregisterUnlockReceiver()
                handleAssistantSearchIntent(intent)
            }
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val permissionGranted = Settings.canDrawOverlays(this)
        val shouldStartSidebar = waitingForSidebarPermission
        waitingForSidebarPermission = false

        if (permissionGranted && shouldStartSidebar) {
            startChopperSidebar()
        } else if (!permissionGranted) {
            sidebarPreferences.edit()
                .putBoolean("sidebar_enabled", false)
                .apply()

            Toast.makeText(
                this,
                "Display over other apps permission is required for the Chopper sidebar.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val audioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceRecognition(pendingConversationListening)
        } else {
            Toast.makeText(
                this,
                "Microphone permission is needed to speak with Chopper.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val galleryLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: Exception) {
                // Photo Picker access remains valid for this app session.
            }
            showSelectedImage(uri)
        }
    }

    private val cameraLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { saved ->
        if (saved) {
            pendingCameraUri?.let { showSelectedImage(it) }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            openCamera()
        } else {
            Toast.makeText(
                this,
                "Camera permission is needed to take a photo.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val wakeWordPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val microphoneAllowed =
                permissions[Manifest.permission.RECORD_AUDIO] == true ||
                        ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED

            if (microphoneAllowed && isHelloChopperEnabled()) {
                startWakeWordService()
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is needed for Hey Chopper.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private fun requestWakeWordPermissions() {
        if (!isHelloChopperEnabled()) {
            stopWakeWordService()
            return
        }

        val requiredPermissions =
            mutableListOf(
                Manifest.permission.RECORD_AUDIO
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requiredPermissions.add(
                Manifest.permission.POST_NOTIFICATIONS
            )
        }

        val missingPermissions =
            requiredPermissions.filter { permission ->
                ContextCompat.checkSelfPermission(
                    this,
                    permission
                ) != PackageManager.PERMISSION_GRANTED
            }

        if (missingPermissions.isEmpty()) {
            startWakeWordService()
        } else {
            wakeWordPermissionLauncher.launch(
                missingPermissions.toTypedArray()
            )
        }
    }

    private fun startWakeWordService() {
        if (!isHelloChopperEnabled()) {
            return
        }

        val serviceIntent =
            Intent(
                this,
                WakeWordService::class.java
            ).apply {
                action = WakeWordService.ACTION_START
            }

        ContextCompat.startForegroundService(
            this,
            serviceIntent
        )
    }


    private fun sendWakeWordServiceAction(
        actionName: String
    ) {
        val serviceIntent = Intent(
            this,
            WakeWordService::class.java
        ).apply {
            action = actionName
        }

        ContextCompat.startForegroundService(
            this,
            serviceIntent
        )
    }


    private fun pauseWakeWordService() {
        sendWakeWordServiceAction(
            WakeWordService.ACTION_PAUSE
        )
    }


    private fun resumeWakeWordService() {
        if (isHelloChopperEnabled()) {
            sendWakeWordServiceAction(
                WakeWordService.ACTION_RESUME
            )
        }
    }


    private fun stopWakeWordService() {
        stopService(
            Intent(
                this,
                WakeWordService::class.java
            )
        )
    }


    private fun isHelloChopperEnabled(): Boolean {
        return getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).getBoolean(
            "hello_chopper_enabled",
            true
        )
    }


    private fun setHelloChopperEnabled(
        enabled: Boolean
    ) {
        getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean(
                "hello_chopper_enabled",
                enabled
            )
            .apply()

        if (enabled) {
            requestWakeWordPermissions()
        } else {
            stopWakeWordService()
        }
    }


    private fun isChopperSidebarEnabled(): Boolean {
        return sidebarPreferences.getBoolean(
            "sidebar_enabled",
            false
        )
    }


    private fun setChopperSidebarEnabled(
        enabled: Boolean
    ) {
        if (!enabled) {
            sidebarPreferences.edit()
                .putBoolean("sidebar_enabled", false)
                .apply()

            stopService(
                Intent(
                    this,
                    ChopperSidebarService::class.java
                )
            )

            Toast.makeText(
                this,
                "Chopper sidebar is OFF",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            waitingForSidebarPermission = true

            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )

            try {
                overlayPermissionLauncher.launch(permissionIntent)
            } catch (_: Exception) {
                waitingForSidebarPermission = false

                Toast.makeText(
                    this,
                    "Unable to open the overlay permission screen.",
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }

        startChopperSidebar()
    }


    private fun startChopperSidebar() {
        if (!Settings.canDrawOverlays(this)) {
            sidebarPreferences.edit()
                .putBoolean("sidebar_enabled", false)
                .apply()
            return
        }

        sidebarPreferences.edit()
            .putBoolean("sidebar_enabled", true)
            .apply()

        val sidebarIntent = Intent(
            this,
            ChopperSidebarService::class.java
        ).apply {
            action = ChopperSidebarService.ACTION_START
        }

        ContextCompat.startForegroundService(
            this,
            sidebarIntent
        )

        Toast.makeText(
            this,
            "Chopper sidebar is ON",
            Toast.LENGTH_SHORT
        ).show()
    }


    private fun handleWakeWordIntent(
        wakeIntent: Intent?
    ) {
        if (
            wakeIntent?.getBooleanExtra(
                WakeWordService.EXTRA_WAKE_WORD_TRIGGERED,
                false
            ) != true
        ) {
            return
        }

        wakeIntent.removeExtra(
            WakeWordService.EXTRA_WAKE_WORD_TRIGGERED
        )
        wakeIntent.removeExtra(
            WakeWordService.EXTRA_DETECTED_KEYWORD
        )

        if (currentMode != "Conversation") {
            modeBeforeConversation = currentMode
        }

        currentMode = "Conversation"
        binding.modeButton.text = "Conversation ▼"
        conversationModeEnabled = true
        manualVoiceStop = false
        spokenRepliesEnabled = true

        getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean("spoken_replies_enabled", true)
            .apply()

        showWakeLogoScreen()

        voiceHandler.postDelayed({
            if (!manualVoiceStop) {
                startVoiceRecognition(true)
            }
        }, 300)
    }


    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        chopperDao = ChopperDatabase.getInstance(applicationContext).chopperDao()

        binding = ActivityMainBinding.inflate(
            layoutInflater
        )

        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(
            window, false
        )

        setupInsets()
        setupRoundedMainControls()
        setupChatGptStyleComposer()
        setupModeButton()
        setupHistoryButton()
        setupSendButton()
        setupImageAttachment()
        setupVoiceFeatures()
        requestWakeWordPermissions()

        savedInstanceState
            ?.getString("selected_image_uri")
            ?.takeIf { it.isNotBlank() }
            ?.let { showSelectedImage(Uri.parse(it)) }

        loadOrCreateConversation(savedInstanceState)
        loadModel()

        voiceHandler.postDelayed({
            handleWakeWordIntent(intent)
            handleAssistantSearchIntent(intent)
            handleSidebarSectionIntent(intent)
        }, 350)
    }


    override fun onNewIntent(
        intent: Intent
    ) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSidebarConversationIntent(intent)
        handleWakeWordIntent(intent)
        handleAssistantSearchIntent(intent)
        handleSidebarSectionIntent(intent)
    }


    private fun handleSidebarConversationIntent(sidebarIntent: Intent?) {
        val conversationId = sidebarIntent?.getLongExtra(
            EXTRA_SIDEBAR_CONVERSATION_ID,
            0L
        ) ?: 0L

        if (conversationId == 0L) return

        sidebarIntent?.removeExtra(EXTRA_SIDEBAR_CONVERSATION_ID)
        openConversation(conversationId)
    }


    private fun handleSidebarSectionIntent(sidebarIntent: Intent?) {
        val section = sidebarIntent
            ?.getStringExtra(EXTRA_OPEN_SECTION)
            ?.trim()
            .orEmpty()

        if (section.isEmpty()) return

        sidebarIntent?.removeExtra(EXTRA_OPEN_SECTION)
        voiceHandler.postDelayed({
            when (section) {
                SECTION_SETTINGS -> showSettingsMenu()
                SECTION_PROJECTS -> showProjects()
            }
        }, 250)
    }


    override fun onResume() {
        super.onResume()

        if (
            waitingForSidebarPermission &&
            Settings.canDrawOverlays(this)
        ) {
            waitingForSidebarPermission = false
            startChopperSidebar()
        }
    }


    private fun handleAssistantSearchIntent(
        assistantIntent: Intent?
    ) {
        val query = assistantIntent
            ?.getStringExtra(EXTRA_ASSISTANT_QUERY)
            ?.trim()
            .orEmpty()

        val screenshotPath = assistantIntent
            ?.getStringExtra(EXTRA_ASSISTANT_SCREENSHOT_PATH)
            ?.trim()
            .orEmpty()

        if (query.isEmpty() && screenshotPath.isEmpty()) {
            return
        }

        val keyguardManager =
            getSystemService(KeyguardManager::class.java)

        if (keyguardManager.isKeyguardLocked) {
            registerUnlockReceiver()
            return
        }

        assistantIntent?.removeExtra(EXTRA_ASSISTANT_QUERY)
        assistantIntent?.removeExtra(EXTRA_ASSISTANT_SCREENSHOT_PATH)

        if (screenshotPath.isNotEmpty()) {
            val screenshotFile = File(screenshotPath)

            if (screenshotFile.exists()) {
                showSelectedImage(Uri.fromFile(screenshotFile))
            }
        }

        binding.messageInput.setText(query)
        binding.messageInput.setSelection(query.length)

        voiceHandler.postDelayed({
            sendCurrentMessage()
        }, 250)
    }


    private fun registerUnlockReceiver() {
        if (unlockReceiverRegistered) {
            return
        }

        ContextCompat.registerReceiver(
            this,
            unlockReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        unlockReceiverRegistered = true
    }


    private fun unregisterUnlockReceiver() {
        if (!unlockReceiverRegistered) {
            return
        }

        try {
            unregisterReceiver(unlockReceiver)
        } catch (_: Exception) {
            // Receiver may already have been removed by Android.
        }

        unlockReceiverRegistered = false
    }


    private fun setupRoundedMainControls() {
        binding.messageInput.background = ColorDrawable(Color.TRANSPARENT)
        binding.modeButton.background = roundedBackground("#21262D", 22)
        binding.sendButton.background = roundedBackground("#6F52B5", 26)
        binding.historyButton.background = roundedBackground("#21262D", 24)

        binding.historyButton.apply {
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            textSize = 24f
            layoutParams = layoutParams.apply {
                width = dp(46)
                height = dp(42)
            }
        }

        binding.sendButton.apply {
            minWidth = 0
            minHeight = 0
            text = "↑"
            textSize = 25f
            setPadding(0, 0, 0, dp(2))
            layoutParams = layoutParams.apply {
                width = dp(50)
                height = dp(50)
            }
        }

        binding.modeButton.apply {
            minWidth = 0
            minHeight = 0
            setPadding(dp(14), 0, dp(14), 0)
            layoutParams = layoutParams.apply {
                height = dp(36)
            }
        }
    }


    private fun setupChatGptStyleComposer() {
        val inputRow = binding.messageInput.parent as? LinearLayout ?: return

        inputRow.gravity = Gravity.CENTER_VERTICAL
        inputRow.setPadding(dp(8), dp(6), dp(8), dp(6))
        inputRow.background = roundedBackground("#21262D", 30)

        binding.messageInput.apply {
            setPadding(dp(10), dp(11), dp(8), dp(11))
            hint = "Message Chopper"
            minHeight = dp(48)
        }

        binding.composerArea.setPadding(
            dp(10), dp(8), dp(10), dp(10)
        )
    }


    override fun onSaveInstanceState(
        outState: Bundle
    ) {
        outState.putLong(
            "current_conversation_id", currentConversationId
        )

        outState.putString(
            "selected_image_uri",
            selectedImageUri?.toString().orEmpty()
        )

        super.onSaveInstanceState(outState)
    }


    // =====================================================
    // IMAGE ATTACHMENT: CAMERA + GALLERY
    // =====================================================

    private fun setupImageAttachment() {
        val inputRow = binding.messageInput.parent as? LinearLayout ?: return

        val attachButton = TextView(this).apply {
            text = "+"
            textSize = 26f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Attach image"
            background = roundedBackground("#30363D", 22)
            setOnClickListener { showImageSourceMenu() }
        }

        inputRow.addView(
            attachButton,
            inputRow.indexOfChild(binding.messageInput),
            LinearLayout.LayoutParams(dp(44), dp(44)).apply {
                marginEnd = dp(2)
            }
        )
    }


    // =====================================================
    // VOICE INPUT + SPOKEN REPLIES
    // =====================================================

    private fun setupVoiceFeatures() {
        textToSpeech = TextToSpeech(this) { status ->
            textToSpeechReady = status == TextToSpeech.SUCCESS

            if (textToSpeechReady) {
                val preferredLanguage = Locale.getDefault()
                val languageResult = textToSpeech?.setLanguage(preferredLanguage)

                if (
                    languageResult == TextToSpeech.LANG_MISSING_DATA ||
                    languageResult == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    textToSpeech?.setLanguage(Locale.US)
                }

                applySavedVoiceSettings()
            }
        }

        textToSpeech?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    runOnUiThread {
                        voiceButtonView?.text = "■"
                    }
                }

                override fun onRangeStart(
                    utteranceId: String?,
                    start: Int,
                    end: Int,
                    frame: Int
                ) {
                    val id = utteranceId ?: return
                    val offset = synchronized(speechChunkOffsets) {
                        speechChunkOffsets[id]
                    } ?: return

                    highlightSpokenRange(offset + start, offset + end)
                }

                override fun onDone(utteranceId: String?) {
                    if (utteranceId == finalSpeechUtteranceId) {
                        runOnUiThread {
                            stopSpeakingAndClearHighlight(false)
                            dismissWakeLogoScreen()
                            if (conversationModeEnabled && !manualVoiceStop) {
                                voiceHandler.postDelayed({
                                    startVoiceRecognition(true)
                                }, 450)
                            }
                        }
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    runOnUiThread {
                        stopSpeakingAndClearHighlight(false)
                        dismissWakeLogoScreen()
                    }
                }
            }
        )

        val inputRow = binding.messageInput.parent as? LinearLayout ?: return

        val voiceButton = TextView(this).apply {
            text = "MIC"
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            contentDescription = "Speak to Chopper"
            background = roundedBackground("#30363D", 22)

            setOnClickListener {
                showVoiceStartMenu()
            }
        }
        voiceButtonView = voiceButton

        inputRow.addView(
            voiceButton,
            inputRow.indexOfChild(binding.sendButton),
            LinearLayout.LayoutParams(dp(48), dp(44)).apply {
                marginStart = dp(2)
                marginEnd = dp(4)
            }
        )
    }


    private fun showVoiceStartMenu() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(10))
            background = roundedBackground("#161B22", 28)
        }
        panel.addView(TextView(this).apply {
            text = "Talk with Chopper"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(15))
        })
        val dialog = AlertDialog.Builder(this).setView(panel).create()

        fun addChoice(title: String, detail: String, color: String, action: () -> Unit) {
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(14), dp(18), dp(14))
                background = roundedBackground(color, 23)
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = detail
                    textSize = 13f
                    setTextColor(Color.parseColor("#D0C6EB"))
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        addChoice(
            "Speak once",
            "Say one message to Chopper",
            "#30363D"
        ) { startVoiceRecognition(false) }
        addChoice(
            "Conversation mode",
            "Hands-free listening with Hey Chopper or Hey Chop",
            "#6F52B5"
        ) { startConversationMode() }
        // This third option is the permanent audio-state indicator:
        // Stop = voice replies are enabled, Audio = text-only mode.
        val voiceSessionActive = spokenRepliesEnabled

        if (voiceSessionActive) {
            addChoice(
                "Stop",
                "Turn off voice conversation and return to text only",
                "#6E2B2B"
            ) { stopVoiceConversation(disableSpokenReplies = true) }
        } else {
            addChoice(
                "Audio",
                "Voice is off. Start hands-free voice conversation",
                "#30363D"
            ) { startConversationMode() }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.72f)
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }


    private fun startConversationMode() {
        modeBeforeConversation = currentMode
        currentMode = "Conversation"
        binding.modeButton.text = "Conversation ▼"
        conversationModeEnabled = true
        manualVoiceStop = false
        spokenRepliesEnabled = true
        getSharedPreferences("chopper_voice_settings", Context.MODE_PRIVATE)
            .edit()
            .putBoolean("spoken_replies_enabled", true)
            .apply()
        startVoiceRecognition(true)
    }


    private fun startVoiceRecognition(conversation: Boolean = false) {
        stopSpeakingAndClearHighlight()
        pendingConversationListening = conversation
        manualVoiceStop = false

        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        pauseWakeWordService()

        // Give the foreground wake service time to release AudioRecord before
        // SpeechRecognizer requests the same microphone.
        voiceHandler.postDelayed({
            if (!manualVoiceStop) {
                beginChopperListening(conversation)
            }
        }, 300)
    }


    private fun beginChopperListening(conversation: Boolean) {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Speech recognition is not available on this phone.",
                Toast.LENGTH_LONG
            ).show()
            conversationModeEnabled = false
            return
        }

        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        isListening = true
                        voiceButtonView?.text = "■"
                        listeningStatusText?.text = "Listening…"
                    }

                    override fun onBeginningOfSpeech() {
                        listeningStatusText?.text = "I can hear you"
                    }

                    override fun onRmsChanged(rmsdB: Float) = Unit
                    override fun onBufferReceived(buffer: ByteArray?) = Unit

                    override fun onEndOfSpeech() {
                        listeningStatusText?.text = "Sending to Chopper…"
                    }

                    override fun onError(error: Int) {
                        isListening = false
                        dismissListeningDialog()
                        voiceButtonView?.text = "MIC"

                        if (conversationModeEnabled && !manualVoiceStop) {
                            voiceHandler.postDelayed({
                                if (conversationModeEnabled && !isListening) {
                                    beginChopperListening(true)
                                }
                            }, if (error == SpeechRecognizer.ERROR_NO_MATCH) 450 else 900)
                        } else {
                            resumeWakeWordService()
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        isListening = false
                        dismissListeningDialog()
                        voiceButtonView?.text = "MIC"

                        val spokenText = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()

                        if (spokenText.isNotBlank()) {
                            val wakePattern = Regex(
                                "^\\s*hey[\\s,]+(?:chopper|chop)\\b[\\s,!.?-]*",
                                RegexOption.IGNORE_CASE
                            )
                            val wakeWordUsed = wakePattern.containsMatchIn(spokenText)
                            val messageToSend = if (wakeWordUsed) {
                                spokenText.replaceFirst(wakePattern, "").trim()
                            } else {
                                spokenText
                            }

                            if (wakeWordUsed) {
                                showWakeLogoScreen()
                            }

                            if (wakeWordUsed && messageToSend.isBlank()) {
                                listeningStatusText?.text = "Chopper is listening…"
                                voiceHandler.postDelayed({
                                    if (!manualVoiceStop) {
                                        beginChopperListening(true)
                                    }
                                }, 250)
                            } else {
                                binding.messageInput.setText(messageToSend)
                                binding.messageInput.setSelection(messageToSend.length)
                                sendCurrentMessage()

                                if (!conversationModeEnabled) {
                                    voiceHandler.postDelayed({
                                        resumeWakeWordService()
                                    }, 1200)
                                }
                            }
                        } else if (conversationModeEnabled && !manualVoiceStop) {
                            voiceHandler.postDelayed({
                                beginChopperListening(true)
                            }, 500)
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                            ?.trim()
                            .orEmpty()
                        if (partial.isNotBlank()) {
                            listeningStatusText?.text = partial
                        }
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) = Unit
                })
            }
        }

        val speechIntent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Speak to Chopper"
            )
            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                1
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                850L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                500L
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                700L
            )
        }

        try {
            pendingConversationListening = conversation
            showChopperListeningDialog(conversation)
            speechRecognizer?.startListening(speechIntent)
        } catch (_: Exception) {
            dismissListeningDialog()
            Toast.makeText(
                this,
                "Speech recognition is not available on this phone.",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun showChopperListeningDialog(conversation: Boolean) {
        dismissListeningDialog()

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(25), dp(24), dp(20))
            background = roundedBackground("#161B22", 30)
        }
        panel.addView(TextView(this).apply {
            text = if (conversation) "Conversation mode" else "Speak to Chopper"
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
        })
        listeningStatusText = TextView(this).apply {
            text = "Getting ready…\nPause briefly and Chopper will reply automatically."
            textSize = 16f
            setTextColor(Color.parseColor("#B9A7E8"))
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(18), dp(8), dp(18))
        }
        panel.addView(listeningStatusText)
        panel.addView(TextView(this).apply {
            text = if (conversation) "■  End conversation" else "Cancel"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground("#6F52B5", 24)
            setOnClickListener { stopVoiceConversation() }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(50)
        ))

        listeningDialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()
            .also { dialog ->
                dialog.setCanceledOnTouchOutside(false)
                dialog.setOnCancelListener { stopVoiceConversation() }
                dialog.setOnShowListener {
                    dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    dialog.window?.setDimAmount(0.72f)
                    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                    dialog.window?.setLayout(
                        (resources.displayMetrics.widthPixels * 0.88f).toInt(),
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                }
                dialog.show()
            }
    }


    private fun dismissListeningDialog() {
        val dialog = listeningDialog
        listeningDialog = null
        listeningStatusText = null
        dialog?.setOnCancelListener(null)
        dialog?.dismiss()
    }


    private fun showWakeLogoScreen() {
        dismissWakeLogoScreen()

        if (isFinishing || isDestroyed) {
            return
        }

        val overlayHost = window.decorView as? ViewGroup ?: return

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(12), dp(12), dp(12))
            background = roundedBackground("#161B22", 32)

            addView(TextView(this@MainActivity).apply {
                text = "Just a sec…"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER_VERTICAL
            }, LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            ))

            addView(TextView(this@MainActivity).apply {
                text = "■"
                textSize = 18f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                contentDescription = "Stop Chopper voice"
                background = roundedBackground("#0B57D0", 22)
                setOnClickListener {
                    stopVoiceConversation(disableSpokenReplies = true)
                }
            }, LinearLayout.LayoutParams(dp(52), dp(52)))
        }

        wakeStatusPanel = panel
        overlayHost.addView(
            panel,
            FrameLayout.LayoutParams(
                (resources.displayMetrics.widthPixels * 0.86f).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = dp(12)
                rightMargin = dp(12)
                bottomMargin = dp(28)
            }
        )
    }


    private fun dismissWakeLogoScreen() {
        wakeStatusPanel?.let { panel ->
            (panel.parent as? ViewGroup)?.removeView(panel)
        }
        wakeStatusPanel = null
    }


    private fun stopVoiceConversation(
        disableSpokenReplies: Boolean = false
    ) {
        manualVoiceStop = true
        conversationModeEnabled = false

        if (disableSpokenReplies) {
            spokenRepliesEnabled = false
            getSharedPreferences(
                "chopper_voice_settings",
                Context.MODE_PRIVATE
            ).edit()
                .putBoolean("spoken_replies_enabled", false)
                .apply()
        }
        if (currentMode == "Conversation") {
            currentMode = modeBeforeConversation
            binding.modeButton.text = "$currentMode ▼"
        }
        voiceHandler.removeCallbacksAndMessages(null)
        if (isListening) {
            speechRecognizer?.cancel()
        }
        isListening = false
        dismissListeningDialog()
        dismissWakeLogoScreen()
        stopSpeakingAndClearHighlight()
        voiceButtonView?.text = "MIC"
        resumeWakeWordService()
        Toast.makeText(this, "Voice stopped", Toast.LENGTH_SHORT).show()
    }


    private fun speakAssistantReply(
        message: String,
        messageView: TextView? = null
    ) {
        if (
            !spokenRepliesEnabled ||
            !textToSpeechReady ||
            restoringMessages ||
            message.isBlank()
        ) {
            dismissWakeLogoScreen()
            return
        }

        stopSpeakingAndClearHighlight()

        configureSpeechLanguageForMessage(message)

        currentSpeakingView = messageView
        currentSpeakingText = message
        speechGeneration++
        val generation = speechGeneration
        var characterOffset = 0
        val chunks = message.chunked(3500)

        synchronized(speechChunkOffsets) {
            speechChunkOffsets.clear()
        }

        chunks.forEachIndexed { index, part ->
            val utteranceId = "chopper_${generation}_${index}"

            synchronized(speechChunkOffsets) {
                speechChunkOffsets[utteranceId] = characterOffset
            }

            if (index == chunks.lastIndex) {
                finalSpeechUtteranceId = utteranceId
            }

            textToSpeech?.speak(
                part,
                if (index == 0) {
                    TextToSpeech.QUEUE_FLUSH
                } else {
                    TextToSpeech.QUEUE_ADD
                },
                null,
                utteranceId
            )

            characterOffset += part.length
        }
    }


    private fun configureSpeechLanguageForMessage(message: String) {
        if (!containsTamilScript(message)) {
            applySavedVoiceSettings()
            return
        }

        val tamilLocale = Locale("ta", "IN")
        val tamilVoice = textToSpeech?.voices
            ?.filter { voice ->
                voice.locale.language.equals("ta", ignoreCase = true)
            }
            ?.sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenByDescending { it.quality }
            )
            ?.firstOrNull()

        val languageResult = textToSpeech?.setLanguage(tamilLocale)

        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            applySavedVoiceSettings()
            return
        }

        if (tamilVoice != null) {
            textToSpeech?.voice = tamilVoice
        }

        val preferences = getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        )

        textToSpeech?.setSpeechRate(
            preferences.getFloat("speech_rate", 0.96f)
        )
        textToSpeech?.setPitch(
            preferences.getFloat("speech_pitch", 1.0f)
        )
    }


    private fun containsTamilScript(text: String): Boolean {
        return text.any { character ->
            character.code in 0x0B80..0x0BFF
        }
    }


    private fun wantsModernTamil(message: String): Boolean {
        if (containsTamilScript(message)) {
            return true
        }

        val normalized = message
            .lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")

        val directRequests = listOf(
            "in tamil",
            "speak tamil",
            "speak in tamil",
            "reply tamil",
            "reply in tamil",
            "tamil la",
            "tamil ah",
            "tamil pesu",
            "tamil sollu"
        )

        if (directRequests.any { normalized.contains(it) }) {
            return true
        }

        val words = normalized.split(Regex("\\s+")).filter { it.isNotBlank() }
        val tanglishWords = setOf(
            "enna", "ennaku", "enakku", "ennoda", "enoda", "naan", "na",
            "nee", "neenga", "unga", "unakku", "epdi", "eppadi", "eppadiya",
            "irukku", "iruka", "iruken", "irukkanum", "pannu", "pannunga",
            "pannanum", "pannala", "pannalam", "venum", "venam", "vendam",
            "sollu", "sollunga", "sonna", "seri", "sari", "illa", "illai",
            "aama", "ama", "athu", "adhu", "ithu", "idhu", "enga", "ethuku",
            "edhuku", "eppo", "evlo", "romba", "konjam", "mudiyuma", "puriyala",
            "puriyuthu", "nalla", "apdi", "appadi", "ipdi", "ippadi", "mattum",
            "kooda", "aprom", "piragu", "da", "machan", "macha"
        )

        return words.count { it in tanglishWords } >= 2
    }


    private fun modernTamilReplyInstruction(message: String): String {
        if (!wantsModernTamil(message)) {
            return ""
        }

        return """
Language rule: Reply in natural present-day spoken Tamil used in everyday
conversation in Tamil Nadu. Use simple Tamil script and naturally keep common
English words such as app, phone, settings, code, search and update when they
sound normal. Match Gowrish's casual speaking style. Do not use ancient Tamil,
literary Tamil, old-fashioned words, overly pure Tamil, or stiff textbook
sentences. Make the reply clear, friendly and easy for a Tamil TTS voice to say.
""".trimIndent()
    }


    private fun highlightSpokenRange(start: Int, end: Int) {
        val view = currentSpeakingView ?: return
        val safeStart = start.coerceIn(0, currentSpeakingText.length)
        val safeEnd = end.coerceIn(safeStart, currentSpeakingText.length)

        runOnUiThread {
            val highlighted = SpannableString(currentSpeakingText)
            Linkify.addLinks(highlighted, Linkify.WEB_URLS)

            if (safeEnd > safeStart) {
                highlighted.setSpan(
                    BackgroundColorSpan(Color.parseColor("#6F52B5")),
                    safeStart,
                    safeEnd,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }

            view.text = highlighted
        }
    }


    private fun stopSpeakingAndClearHighlight(stopEngine: Boolean = true) {
        if (stopEngine) {
            textToSpeech?.stop()
        }

        currentSpeakingView?.let { view ->
            val normalText = SpannableString(currentSpeakingText)
            Linkify.addLinks(normalText, Linkify.WEB_URLS)
            view.text = normalText
        }

        currentSpeakingView = null
        currentSpeakingText = ""
        finalSpeechUtteranceId = null

        synchronized(speechChunkOffsets) {
            speechChunkOffsets.clear()
        }

        voiceButtonView?.text = "MIC"
    }


    private fun applySavedVoiceSettings() {
        val preferences = getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        )

        spokenRepliesEnabled = preferences.getBoolean(
            "spoken_replies_enabled",
            true
        )

        textToSpeech?.setSpeechRate(
            preferences.getFloat("speech_rate", 0.96f)
        )
        textToSpeech?.setPitch(
            preferences.getFloat("speech_pitch", 1.0f)
        )

        preferences.getString(
            "speech_language_tag",
            null
        )?.takeIf { it.isNotBlank() }
            ?.let { languageTag ->
                textToSpeech?.setLanguage(
                    Locale.forLanguageTag(languageTag)
                )
            }

        val savedVoiceName = preferences.getString("system_voice_name", null)
        if (!savedVoiceName.isNullOrBlank()) {
            textToSpeech?.voices
                ?.firstOrNull { it.name == savedVoiceName }
                ?.let { textToSpeech?.voice = it }
        }
    }


    private fun saveVoicePreset(
        name: String,
        rate: Float,
        pitch: Float
    ) {
        if (name.startsWith("Male AI ")) {
            saveMaleAiVoice(name, rate, pitch)
            return
        }

        getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).edit()
            .putString("voice_preset", name)
            .putFloat("speech_rate", rate)
            .putFloat("speech_pitch", pitch)
            .remove("system_voice_name")
            .remove("speech_language_tag")
            .apply()

        textToSpeech?.setLanguage(Locale.getDefault())
        textToSpeech?.setSpeechRate(rate)
        textToSpeech?.setPitch(pitch)
        speakAssistantReply("This is Chopper's $name voice.")
    }


    private fun saveMaleAiVoice(
        presetName: String,
        rate: Float,
        pitch: Float
    ) {
        val preferences = getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        )

        val savedMaleBaseName = preferences.getString(
            "male_base_voice_name",
            null
        )

        val englishVoices = textToSpeech?.voices
            ?.filter { it.locale.language.equals("en", true) }
            .orEmpty()

        val maleNameWords = listOf(
            "male", "man", "guy", "david", "daniel", "george",
            "james", "john", "michael", "ryan", "thomas", "william"
        )

        val selectedVoice = englishVoices
            .firstOrNull { it.name == savedMaleBaseName }
            ?: englishVoices
                .filter { voice ->
                    maleNameWords.any { word ->
                        voice.name.contains(word, ignoreCase = true)
                    }
                }
                .sortedWith(
                    compareBy<Voice> { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                )
                .firstOrNull()
            ?: englishVoices
                .filter { it.locale.country.equals("GB", true) }
                .sortedWith(
                    compareBy<Voice> { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                )
                .firstOrNull()
            ?: englishVoices
                .sortedWith(
                    compareBy<Voice> { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                )
                .firstOrNull()

        val selectedLocale = selectedVoice?.locale ?: Locale.UK

        preferences.edit()
            .putString("voice_preset", presetName)
            .putString("speech_language_tag", selectedLocale.toLanguageTag())
            .putFloat("speech_rate", rate)
            .putFloat("speech_pitch", pitch)
            .apply {
                if (selectedVoice != null) {
                    putString("system_voice_name", selectedVoice.name)
                } else {
                    remove("system_voice_name")
                }
            }
            .apply()

        textToSpeech?.setLanguage(selectedLocale)
        if (selectedVoice != null) {
            textToSpeech?.voice = selectedVoice
        }
        textToSpeech?.setSpeechRate(rate)
        textToSpeech?.setPitch(pitch)

        speakAssistantReply(
            "Good evening. All Chopper systems are online and ready for your command."
        )
    }


    private fun saveSystemVoice(voiceName: String, displayName: String) {
        val voice = textToSpeech?.voices
            ?.firstOrNull { it.name == voiceName }
            ?: return

        getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).edit()
            .putString("voice_preset", displayName)
            .putString("system_voice_name", voiceName)
            .putString("male_base_voice_name", voiceName)
            .putString("speech_language_tag", voice.locale.toLanguageTag())
            .putFloat("speech_rate", 0.96f)
            .putFloat("speech_pitch", 1.0f)
            .apply()

        textToSpeech?.voice = voice
        textToSpeech?.setSpeechRate(0.96f)
        textToSpeech?.setPitch(1.0f)
        speakAssistantReply("This is Chopper using the $displayName voice.")
    }


    private fun showImageSourceMenu() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(12))
            background = roundedBackground("#161B22", 28)
        }

        panel.addView(TextView(this).apply {
            text = "Add an image"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(16))
        })

        val dialog = AlertDialog.Builder(this).setView(panel).create()

        fun addSource(label: String, color: String, action: () -> Unit) {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(20), 0, dp(20), 0)
                background = roundedBackground(color, 24)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply { bottomMargin = dp(10) })
        }

        addSource("📷  Camera", "#6F52B5") { requestCamera() }
        addSource("🖼  Gallery", "#21262D") {
            galleryLauncher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        }
        addSource("Cancel", "#30363D") { }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.72f)
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }


    private fun requestCamera() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            openCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }


    private fun openCamera() {
        try {
            val picturesDirectory = File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "chopper_images"
            ).apply { mkdirs() }

            val imageFile = File.createTempFile(
                "chopper_${System.currentTimeMillis()}_",
                ".jpg",
                picturesDirectory
            )

            pendingCameraUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                imageFile
            )

            cameraLauncher.launch(pendingCameraUri)
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Could not open camera: ${error.message ?: "unknown error"}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun showSelectedImage(uri: Uri) {
        selectedImageUri = uri
        imagePreviewPanel?.let { binding.composerArea.removeView(it) }

        val previewPanel = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground("#21262D", 24)
        }

        val preview = ImageView(this).apply {
            setImageURI(uri)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = roundedBackground("#30363D", 18)
            contentDescription = "Selected image preview"
        }

        previewPanel.addView(preview, LinearLayout.LayoutParams(
            dp(84),
            dp(84)
        ))

        previewPanel.addView(TextView(this).apply {
            text = "Image ready\nAdd a message, then send"
            textSize = 15f
            setTextColor(Color.WHITE)
            setPadding(dp(14), 0, dp(8), 0)
        }, LinearLayout.LayoutParams(
            0,
            LinearLayout.LayoutParams.WRAP_CONTENT,
            1f
        ))

        previewPanel.addView(TextView(this).apply {
            text = "×"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "Remove selected image"
            background = roundedBackground("#6E2B2B", 20)
            setOnClickListener { clearSelectedImage() }
        }, LinearLayout.LayoutParams(dp(42), dp(42)))

        val inputRowIndex = binding.composerArea.childCount - 1
        binding.composerArea.addView(
            previewPanel,
            inputRowIndex.coerceAtLeast(0),
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6)
                bottomMargin = dp(8)
            }
        )

        imagePreviewPanel = previewPanel
        binding.messageInput.hint = "Ask Chopper about this image..."
    }


    private fun clearSelectedImage() {
        selectedImageUri = null
        imagePreviewPanel?.let { binding.composerArea.removeView(it) }
        imagePreviewPanel = null
        binding.messageInput.hint = "Message Chopper..."
    }


// =====================================================
// KEEP INPUT ABOVE KEYBOARD
// =====================================================

    private fun setupInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(
            binding.rootLayout
        ) { _, insets ->

            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars()
            )

            val ime = insets.getInsets(
                WindowInsetsCompat.Type.ime()
            )

            binding.headerLayout.setPadding(
                binding.headerLayout.paddingLeft,
                systemBars.top,
                binding.headerLayout.paddingRight,
                binding.headerLayout.paddingBottom
            )

            val bottomInset = maxOf(
                systemBars.bottom, ime.bottom
            )

            binding.composerArea.setPadding(
                binding.composerArea.paddingLeft,
                binding.composerArea.paddingTop,
                binding.composerArea.paddingRight,
                dp(8) + bottomInset
            )

            insets
        }
    }


    // =====================================================
    // WELCOME MESSAGE
    // =====================================================

    private fun showWelcomeMessage() {
        val previousRestoringState = restoringMessages
        restoringMessages = true
        addAssistantMessage(
            "Hello! I'm Chopper. How can I help you?"
        )
        restoringMessages = previousRestoringState
    }


    // =====================================================
    // MODE BUTTON
    // =====================================================

    private fun setupModeButton() {
        binding.modeButton.setOnClickListener {
            showModeChooser()
        }
    }


    private fun showModeChooser() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(20), dp(18), dp(10))
            background = roundedBackground("#161B22", 28)
        }

        panel.addView(TextView(this).apply {
            text = "Choose response style"
            textSize = 21f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(14))
        })

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()

        listOf(
            "Auto" to "Chopper chooses the best style",
            "Quick" to "Brief point-by-point overview",
            "Balanced" to "Simple, relevant answers",
            "Research" to "Deep analysis with reasons and context",
            "Teacher" to "Learn step by step"
        ).forEach { (name, description) ->
            val selected = name == currentMode
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(12), dp(18), dp(12))
                background = roundedBackground(
                    if (selected) "#6F52B5" else "#21262D",
                    22
                )
                addView(TextView(this@MainActivity).apply {
                    text = if (selected) "$name  ✓" else name
                    textSize = 17f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = description
                    textSize = 12.5f
                    setTextColor(
                        Color.parseColor(if (selected) "#F0EAFE" else "#8B949E")
                    )
                    setPadding(0, dp(3), 0, 0)
                })
                setOnClickListener {
                    currentMode = name
                    binding.modeButton.text = "$currentMode ▼"
                    dialog.dismiss()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(9) })
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.72f)
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }


    // =====================================================
    // HISTORY BUTTON
    // =====================================================

    private fun setupHistoryButton() {
        binding.historyButton.setOnClickListener {
            showConversationHistory()
        }
    }


    // =====================================================
    // OPEN ANDROID AUTOFILL PROVIDER SELECTOR
    // =====================================================

    private fun openAutofillSettings() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE
            ).apply {
                data = Uri.parse(
                    "package:$packageName"
                )
            }

            startActivity(intent)

        } catch (error: Exception) {
            Toast.makeText(
                this, "Unable to open the Autofill selector on this phone.", Toast.LENGTH_LONG
            ).show()

            try {
                startActivity(
                    Intent(Settings.ACTION_SETTINGS)
                )
            } catch (_: Exception) {
                // The device has no compatible settings screen.
            }
        }
    }


    // =====================================================
    // LOAD LOCAL GEMMA FALLBACK
    // =====================================================

    private fun loadModel() {
        binding.statusText.text = "Loading local fallback..."

        val modelFile = File(
            filesDir, "gemma3-270m-it-q8.task"
        )

        Thread {
            try {
                if (!modelFile.exists()) {
                    assets.open(
                        "gemma3-270m-it-q8.task"
                    ).use { input ->

                        FileOutputStream(
                            modelFile
                        ).use { output ->

                            input.copyTo(output)
                        }
                    }
                }

                val options = LlmInference.LlmInferenceOptions.builder().setModelPath(
                    modelFile.absolutePath
                ).setMaxTokens(1024).build()

                llmInference = LlmInference.createFromOptions(
                    this, options
                )

                modelReady = true

                runOnUiThread {
                    binding.statusText.text = "Chopper - Ready"
                }

            } catch (error: Exception) {
                modelReady = false

                runOnUiThread {
                    binding.statusText.text = "Cloud AI - Ready"
                }
            }
        }.start()
    }


    // =====================================================
    // SEND BUTTON
    // =====================================================

    private fun setupSendButton() {
        binding.sendButton.setOnClickListener {
            sendCurrentMessage()
        }

        binding.messageInput.setOnEditorActionListener { _, actionId, _ ->

            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentMessage()
                true
            } else {
                false
            }
        }
    }


    // =====================================================
    // WEB SEARCH ROUTING
    // =====================================================

    private fun needsWebSearch(
        message: String
    ): Boolean {

        val text = message.lowercase().trim()

        val webWords = listOf(
            "latest",
            "today",
            "current",
            "currently",
            "right now",
            "recent",
            "news",
            "weather",
            "temperature",
            "price",
            "search",
            "internet",
            "web",
            "update",
            "breaking",
            "forecast",
            "live",
            "who is the chief minister",
            "who is chief minister",
            "chief minister of",
            "cm of",
            "who is the prime minister",
            "prime minister of",
            "pm of",
            "who is the president",
            "president of",
            "who is the governor",
            "governor of",
            "who is the ceo",
            "ceo of",
            "who is the chairman",
            "chairman of"
        )

        return webWords.any {
            text.contains(it)
        }
    }


    // =====================================================
    // CLOUD CONVERSATIONAL CHAT
    // =====================================================

    private fun chatWithCloud(
        message: String
    ): String {

        val url = URL(
            "https://chopper-du01.onrender.com/chat"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"

            connection.setRequestProperty(
                "Content-Type", "application/json; charset=UTF-8"
            )

            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.doOutput = true

            val historyArray = JSONArray()

            if (currentConversationId != 0L) {
                val savedMessages = chopperDao.getMessages(currentConversationId).takeLast(10)

                savedMessages.forEach { savedMessage ->

                    val historyItem = JSONObject()

                    historyItem.put(
                        "role", if (savedMessage.isUser) {
                            "user"
                        } else {
                            "assistant"
                        }
                    )

                    historyItem.put(
                        "content", if (savedMessage.text.startsWith(GENERATED_IMAGE_MARKER)) {
                            "Chopper generated an image for this conversation."
                        } else {
                            savedMessage.text
                        }
                    )

                    historyArray.put(historyItem)
                }
            }

            val requestJson = JSONObject()

            val tamilInstruction = modernTamilReplyInstruction(message)
            val responseStyleInstruction = responseStyleInstruction()
            val conversationInstruction = if (conversationModeEnabled) {
                "Conversation mode is active. Reply naturally and warmly like " +
                        "Gowrish's close friend. Keep it easy to speak aloud and avoid " +
                        "unnecessary headings."
            } else {
                ""
            }

            requestJson.put(
                "message",
                listOf(
                    conversationInstruction,
                    responseStyleInstruction,
                    tamilInstruction,
                    "Gowrish said: $message"
                ).filter { it.isNotBlank() }
                    .joinToString("\n\n")
            )

            requestJson.put(
                "history", historyArray
            )

            requestJson.put(
                "mode", currentMode
            )

            connection.outputStream.use { output ->
                output.write(
                    requestJson.toString().toByteArray(Charsets.UTF_8)
                )

                output.flush()
            }

            val responseCode = connection.responseCode

            val responseText = readConnectionResponse(
                connection, responseCode
            )

            val responseJson = JSONObject(responseText)

            if (responseCode !in 200..299) {
                throw Exception(
                    responseJson.optString(
                        "error", "Cloud server returned $responseCode"
                    )
                )
            }

            if (!responseJson.optBoolean("success", false)) {
                throw Exception(
                    responseJson.optString(
                        "error", "Cloud chat failed."
                    )
                )
            }

            val result = responseJson.optString("result", "").trim()

            if (result.isEmpty()) {
                throw Exception(
                    "Cloud AI returned an empty response."
                )
            }

            return result

        } finally {
            connection.disconnect()
        }
    }


    // =====================================================
    // LIVE WEB SEARCH
    // =====================================================

    private fun searchWeb(
        message: String
    ): String {

        val url = URL(
            "https://chopper-du01.onrender.com/search"
        )

        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"

            connection.setRequestProperty(
                "Content-Type", "application/json; charset=UTF-8"
            )

            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.doOutput = true

            val requestJson = JSONObject()

            requestJson.put(
                "query", message
            )

            connection.outputStream.use { output ->
                output.write(
                    requestJson.toString().toByteArray(Charsets.UTF_8)
                )

                output.flush()
            }

            val responseCode = connection.responseCode

            val responseText = readConnectionResponse(
                connection, responseCode
            )

            if (responseText.isBlank()) {
                throw Exception(
                    "The web server returned an empty response."
                )
            }

            val responseJson = JSONObject(responseText)

            if (responseCode !in 200..299) {
                throw Exception(
                    responseJson.optString(
                        "error", "Web server returned $responseCode"
                    )
                )
            }

            if (!responseJson.optBoolean("success", false)) {
                throw Exception(
                    responseJson.optString(
                        "error", "Web search failed."
                    )
                )
            }

            val result = responseJson.optString("result", "").trim()

            if (result.isEmpty()) {
                throw Exception(
                    "No web results were returned."
                )
            }

            return result

        } finally {
            connection.disconnect()
        }
    }


    private fun readConnectionResponse(
        connection: HttpURLConnection, responseCode: Int
    ): String {

        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }

        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }


    // =====================================================
    // SEND CURRENT MESSAGE
    // =====================================================

    private fun sendCurrentMessage() {
        val message = binding.messageInput.text.toString().trim()
        val imageUri = selectedImageUri

        if (message.isEmpty() && imageUri == null) {
            return
        }

        if (imageUri != null) {
            binding.messageInput.text.clear()
            addUserImageMessage(imageUri, message)
            clearSelectedImage()
            if (isImageEditRequest(message)) {
                editAttachedImage(imageUri, message)
            } else {
                analyzeAttachedImage(imageUri, message)
            }
            return
        }

        binding.messageInput.text.clear()

        addUserMessage(message)
        startLoading()

        Thread {
            try {
                if (isImageGenerationRequest(message)) {
                    val prompt = extractImageGenerationPrompt(message)

                    runOnUiThread {
                        binding.statusText.text = "Creating Image..."
                    }

                    val generated = generateImageWithCloud(prompt)

                    runOnUiThread {
                        stopLoading()
                        binding.statusText.text = "Chopper - Ready"
                        addGeneratedImageMessage(
                            file = generated.first,
                            prompt = prompt,
                            saveMessage = true
                        )
                    }
                    return@Thread
                }

                val decision = commandInterpreter.interpret(
                    message = message, localModel = null
                )

                if (decision.action == CommandAction.SAVE_MEMORY || decision.action == CommandAction.RECALL_MEMORY || decision.action == CommandAction.DELETE_MEMORY) {
                    runOnUiThread {
                        handlePrivateMemoryCommand(
                            decision = decision, originalMessage = message
                        )
                    }

                    return@Thread
                }

                val useWeb = decision.action == CommandAction.WEB_SEARCH || needsWebSearch(message)

                val response = if (useWeb) {
                    runOnUiThread {
                        binding.statusText.text = "Searching Web..."
                    }

                    searchWeb(message)
                } else {
                    runOnUiThread {
                        binding.statusText.text = "Cloud AI - Thinking..."
                    }

                    try {
                        chatWithCloud(message)
                    } catch (cloudError: Exception) {
                        if (!modelReady) {
                            throw Exception(
                                "Cloud chat failed: " + cloudError.message
                            )
                        }

                        runOnUiThread {
                            binding.statusText.text = "Offline AI - Thinking..."
                        }

                        generateLocalFallback(message)
                    }
                }

                runOnUiThread {
                    stopLoading()
                    binding.statusText.text = "Chopper - Ready"
                    addAssistantMessage(response)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    stopLoading()
                    binding.statusText.text = "Chopper - Ready"

                    addAssistantMessage(
                        "Chopper error: ${error.message}"
                    )
                }
            }
        }.start()
    }


    // =====================================================
    // ON-DEVICE IMAGE TEXT SCANNING (OCR)
    // =====================================================

    private fun analyzeAttachedImage(
        imageUri: Uri,
        userQuestion: String
    ) {
        startLoading()
        binding.statusText.text = "Scanning image..."

        val inputImage = try {
            InputImage.fromFilePath(this, imageUri)
        } catch (error: Exception) {
            stopLoading()
            addAssistantMessage(
                "I couldn't open this image: ${error.message ?: "unknown error"}"
            )
            return
        }

        val textRecognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        textRecognizer.process(inputImage)
            .addOnCompleteListener { textTask ->
                textRecognizer.close()

                val scannedText = if (textTask.isSuccessful) {
                    textTask.result?.text?.trim().orEmpty()
                } else {
                    ""
                }

                binding.statusText.text = "Recognizing image..."

                val labeler = ImageLabeling.getClient(
                    ImageLabelerOptions.Builder()
                        .setConfidenceThreshold(0.60f)
                        .build()
                )

                labeler.process(inputImage)
                    .addOnCompleteListener { labelTask ->
                        labeler.close()

                        val labels = if (labelTask.isSuccessful) {
                            labelTask.result
                                ?.sortedByDescending { it.confidence }
                                ?.take(8)
                                ?.map {
                                    "${it.text} (${(it.confidence * 100).toInt()}%)"
                                }
                                .orEmpty()
                        } else {
                            emptyList()
                        }

                        finishImageAnalysis(
                            imageUri = imageUri,
                            scannedText = scannedText,
                            labels = labels,
                            userQuestion = userQuestion,
                            textError = textTask.exception?.message,
                            labelError = labelTask.exception?.message
                        )
                    }
            }
    }


    private fun finishImageAnalysis(
        imageUri: Uri,
        scannedText: String,
        labels: List<String>,
        userQuestion: String,
        textError: String?,
        labelError: String?
    ) {
        val safeText = scannedText.take(12_000)
        val labelSummary = if (labels.isEmpty()) {
            "No confident object or scene labels"
        } else {
            labels.joinToString(", ")
        }

        if (safeText.isEmpty() && labels.isEmpty()) {
            stopLoading()
            addAssistantMessage(
                "I couldn't confidently read or recognize this image. " +
                        "Try a clearer, brighter or closer photo." +
                        listOfNotNull(textError, labelError)
                            .takeIf { it.isNotEmpty() }
                            ?.joinToString(prefix = "\nDetails: ", separator = "; ")
                            .orEmpty()
            )
            return
        }

        binding.statusText.text = "Understanding image in detail..."

        Thread {
            val detailedQuestion = userQuestion.ifBlank {
                "Describe this image clearly and in useful detail."
            }

            val answer = try {
                analyzeImageWithCloud(
                    imageUri = imageUri,
                    question = detailedQuestion,
                    scannedText = safeText,
                    labelSummary = labelSummary
                )
            } catch (visionError: Exception) {
                "Detailed vision is unavailable, but the local scan above is ready. " +
                        (visionError.message ?: "Network unavailable")
            }

            runOnUiThread {
                stopLoading()
                addAssistantMessage(answer)
            }
        }.start()
    }


    private fun analyzeImageWithCloud(
        imageUri: Uri,
        question: String,
        scannedText: String,
        labelSummary: String
    ): String {
        val encodedImage = encodeImageForVision(imageUri)
        val connection = URL(
            "https://chopper-du01.onrender.com/vision"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            connection.connectTimeout = 25_000
            connection.readTimeout = 120_000
            connection.doOutput = true

            val requestJson = JSONObject().apply {
                put("question", question)
                put("image_base64", encodedImage)
                put("mime_type", "image/jpeg")
                put("scanned_text", scannedText.take(12_000))
                put("local_labels", labelSummary.take(1_000))
            }

            connection.outputStream.use { output ->
                output.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val responseCode = connection.responseCode
            val responseText = readConnectionResponse(connection, responseCode)

            if (responseText.isBlank()) {
                throw Exception("Vision server returned an empty response.")
            }

            val responseJson = JSONObject(responseText)
            if (responseCode !in 200..299 ||
                !responseJson.optBoolean("success", false)
            ) {
                throw Exception(
                    responseJson.optString(
                        "error",
                        "Vision server returned $responseCode"
                    )
                )
            }

            return responseJson.optString("result", "").trim()
                .ifEmpty { throw Exception("Vision AI returned an empty response.") }
        } finally {
            connection.disconnect()
        }
    }


    private fun encodeImageForVision(imageUri: Uri): String {
        val originalBitmap = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val source = ImageDecoder.createSource(contentResolver, imageUri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val largestSide = maxOf(info.size.width, info.size.height)
                if (largestSide > 1600) {
                    val sampleSize = (largestSide / 1600f).toInt().coerceAtLeast(1)
                    decoder.setTargetSampleSize(sampleSize)
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
        }

        val maximumSide = 1280
        val scale = minOf(
            1f,
            maximumSide.toFloat() / maxOf(originalBitmap.width, originalBitmap.height)
        )

        val resizedBitmap = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                originalBitmap,
                (originalBitmap.width * scale).toInt().coerceAtLeast(1),
                (originalBitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            originalBitmap
        }

        var quality = 84
        var bytes: ByteArray
        do {
            val output = ByteArrayOutputStream()
            resizedBitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
            bytes = output.toByteArray()
            quality -= 8
        } while (bytes.size > 1_500_000 && quality >= 44)

        if (resizedBitmap !== originalBitmap) {
            resizedBitmap.recycle()
        }
        originalBitmap.recycle()

        if (bytes.size > 2_000_000) {
            throw Exception("Image is too large. Choose a smaller image.")
        }

        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }


    // =====================================================
    // LOCAL OFFLINE FALLBACK
    // =====================================================

    private fun generateLocalFallback(
        message: String
    ): String {

        val eligibleMemories = chopperDao.getPromptEligibleMemories()
        val basePrompt = gemmaPromptBuilder.buildPrompt(
            userMessage = message,
            eligibleMemories = eligibleMemories
        )

        val tamilInstruction = modernTamilReplyInstruction(message)
        val responseStyleInstruction = responseStyleInstruction()

        val additionalInstructions = listOf(
            if (conversationModeEnabled) "Speak naturally and warmly like Gowrish's close friend. Keep the reply conversational and easy to hear aloud." else "",
            responseStyleInstruction,
            tamilInstruction
        ).filter { it.isNotBlank() }.joinToString("\n")

        val prompt = if (additionalInstructions.isNotBlank()) {
            basePrompt.replace(
                "[USER MESSAGE]",
                "$additionalInstructions\n\n[USER MESSAGE]"
            )
        } else {
            basePrompt
        }

        val response = cleanResponse(
            llmInference.generateResponse(prompt)
        )

        if (response.isEmpty()) {
            throw Exception(
                "The local AI returned an empty response."
            )
        }

        return response
    }


    private fun responseStyleInstruction(): String {
        return when (currentMode) {
            "Quick" -> """
Use Quick response style. Give only a brief general overview. Format the answer as points, keep each point to at most two short lines, and make the response as small as possible.
""".trimIndent()

            "Balanced" -> """
Use Balanced response style. Answer in simple, user-understandable words. Use short paragraphs or a concise summary, stay directly relevant to the question, and do not add unrelated or unnecessary information.
""".trimIndent()

            "Research" -> """
Use Research response style. Give a deep, focused analysis that answers the question and includes closely related information the user is likely to need. For any procedure, explain each step, why it is needed, and the likely causes or consequences of not doing it. Include useful context while staying on topic.
""".trimIndent()

            "Teacher" -> """
Use Teacher response style. Help the user understand rather than only giving the final answer. Teach the idea step by step at an appropriate depth, using simple examples or guiding questions when useful, so the user can solve similar problems independently.
""".trimIndent()

            else -> ""
        }
    }


    // =====================================================
    // USER MESSAGE
    // =====================================================

    private fun addUserMessage(
        message: String
    ) {
        if (!restoringMessages && currentConversationId != 0L) {
            val conversationId = currentConversationId

            val shouldSetTitle = !conversationTitleSet

            if (shouldSetTitle) {
                conversationTitleSet = true
            }

            Thread {
                if (shouldSetTitle) {
                    val title = message.replace("\n", " ").take(45)

                    chopperDao.updateConversation(
                        conversationId = conversationId, title = title
                    )
                }

                chopperDao.insertMessage(
                    ChatMessage(
                        conversationId = conversationId, text = message, isUser = true
                    )
                )
            }.start()
        }

        val bubble = createMessageBubble(
            message, true
        )

        binding.chatContainer.addView(bubble)

        scrollDown()
    }


    private fun addUserImageMessage(
        imageUri: Uri,
        caption: String
    ) {
        val storedText = if (caption.isBlank()) {
            "📷 Image attached"
        } else {
            "📷 Image attached\n$caption"
        }

        if (currentConversationId != 0L) {
            Thread {
                chopperDao.insertMessage(
                    ChatMessage(
                        conversationId = currentConversationId,
                        text = storedText,
                        isUser = true
                    )
                )
            }.start()
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
            setPadding(dp(4), dp(5), dp(4), dp(5))
            background = roundedBackground("#3151B7", 20)
        }

        wrapper.addView(ImageView(this).apply {
            setImageURI(imageUri)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = roundedBackground("#21262D", 17)
            contentDescription = "Attached image"
        }, LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.68f).toInt(),
            dp(220)
        ))

        if (caption.isNotBlank()) {
            wrapper.addView(TextView(this).apply {
                text = caption
                textSize = 16f
                setTextColor(Color.WHITE)
                setPadding(dp(10), dp(9), dp(10), dp(6))
            })
        }

        binding.chatContainer.addView(wrapper, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.END
            topMargin = dp(6)
            bottomMargin = dp(6)
        })

        scrollDown()
    }


    // =====================================================
    // ASSISTANT MESSAGE
    // =====================================================

    private fun addAssistantMessage(
        message: String
    ) {
        val plainMessage = cleanMarkdownForDisplay(message)

        if (!restoringMessages && currentConversationId != 0L) {
            Thread {
                chopperDao.insertMessage(
                    ChatMessage(
                        conversationId = currentConversationId, text = plainMessage, isUser = false
                    )
                )
            }.start()
        }

        val wrapper = LinearLayout(this)

        wrapper.orientation = LinearLayout.VERTICAL

        val wrapperParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        wrapperParams.gravity = Gravity.START

        wrapperParams.topMargin = dp(7)

        wrapperParams.bottomMargin = dp(7)

        wrapper.layoutParams = wrapperParams

        val bubble = createMessageBubble(
            plainMessage, false
        )

        wrapper.addView(bubble)

        val copyText = TextView(this)

        copyText.text = "Copy"

        copyText.textSize = 12f

        copyText.setTextColor(
            Color.parseColor("#8B949E")
        )

        copyText.setPadding(
            dp(12), dp(4), dp(12), dp(4)
        )

        copyText.setOnClickListener {
            copyWholeMessage(plainMessage)
        }

        wrapper.addView(copyText)

        binding.chatContainer.addView(wrapper)

        scrollDown()

        speakAssistantReply(plainMessage, bubble)
    }


    // =====================================================
    // CHOPPER IMAGE GENERATION
    // =====================================================

    private fun isImageGenerationRequest(message: String): Boolean {
        val text = message
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isBlank()) return false

        // Accept descriptive words between the action and image noun.
        // Examples:
        // "Generate a realistic image of a Nissan GTR"
        // "Can you create a high-detail cinematic picture of Chennai?"
        // "Please render me a 4K wallpaper of space"
        val generationAction = Regex(
            "\\b(generate|create|make|draw|paint|render|design|produce|illustrate)\\b"
        )
        val imageNoun = Regex(
            "\\b(image|picture|photo|artwork|illustration|wallpaper|poster)\\b"
        )

        val actionMatch = generationAction.find(text)
        val nounMatch = imageNoun.find(text)

        if (actionMatch != null && nounMatch != null &&
            actionMatch.range.first < nounMatch.range.first
        ) {
            return true
        }

        return text.startsWith("image of ") ||
                text.startsWith("picture of ") ||
                text.startsWith("photo of ")
    }

    private fun isImageEditRequest(message: String): Boolean {
        val text = message.lowercase(Locale.ROOT).trim()
        if (text.isBlank()) return false
        val editCommands = listOf(
            "edit", "change", "transform", "convert", "turn this",
            "make this", "make the", "remove", "replace", "add a",
            "add an", "change the background", "change background",
            "make me", "make him", "make her", "make them"
        )
        return editCommands.any { text.contains(it) }
    }

    private fun extractImageGenerationPrompt(message: String): String {
        var prompt = message.trim()

        prompt = prompt.replace(
            Regex(
                "^(please\\s+)?(can|could|would)\\s+you\\s+",
                RegexOption.IGNORE_CASE
            ),
            ""
        )

        prompt = prompt.replace(
            Regex(
                "^(please\\s+)?(generate|create|make|draw|paint|render|" +
                        "design|produce|illustrate)(\\s+me)?\\s+",
                RegexOption.IGNORE_CASE
            ),
            ""
        ).trimStart(' ', ':', '-', ',')

        return prompt.ifBlank { "A creative high-quality image" }
    }

    private fun generateImageWithCloud(prompt: String): Pair<File, String> {
        val connection = URL(
            "https://chopper-du01.onrender.com/generate-image"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type", "application/json; charset=UTF-8"
            )
            connection.connectTimeout = 30000
            connection.readTimeout = 240000
            connection.doOutput = true

            val requestBody = JSONObject().put("prompt", prompt).toString()
            connection.outputStream.use {
                it.write(requestBody.toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseText = readConnectionResponse(connection, responseCode)
            val json = JSONObject(responseText)

            if (responseCode !in 200..299 || !json.optBoolean("success")) {
                throw Exception(
                    json.optString("error", "Image generation failed ($responseCode)")
                )
            }

            val encodedImage = json.optString("image_base64")
            if (encodedImage.isBlank()) {
                throw Exception("The image server returned no image data.")
            }

            val imageBytes = Base64.decode(encodedImage, Base64.DEFAULT)
            if (BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) == null) {
                throw Exception("The generated image could not be decoded.")
            }

            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "chopper_generated"
            )
            if (!directory.exists() && !directory.mkdirs()) {
                throw Exception("Could not create the generated-image folder.")
            }

            val file = File(directory, "chopper_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { it.write(imageBytes) }
            return file to json.optString("mime_type", "image/png")
        } finally {
            connection.disconnect()
        }
    }

    private fun editAttachedImage(imageUri: Uri, instruction: String) {
        startLoading()
        binding.statusText.text = "Editing Image..."
        Thread {
            try {
                val edited = editImageWithCloud(imageUri, instruction)
                runOnUiThread {
                    stopLoading()
                    binding.statusText.text = "Chopper - Ready"
                    addGeneratedImageMessage(
                        file = edited.first,
                        prompt = "Edited: $instruction",
                        saveMessage = true
                    )
                }
            } catch (error: Exception) {
                runOnUiThread {
                    stopLoading()
                    binding.statusText.text = "Chopper - Ready"
                    addAssistantMessage("Image editing failed: ${error.message}")
                }
            }
        }.start()
    }

    private fun editImageWithCloud(
        imageUri: Uri,
        instruction: String
    ): Pair<File, String> {
        val connection = URL(
            "https://chopper-du01.onrender.com/edit-image"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type", "application/json; charset=UTF-8"
            )
            connection.connectTimeout = 30_000
            connection.readTimeout = 240_000
            connection.doOutput = true

            val requestJson = JSONObject().apply {
                put("prompt", instruction)
                put("image_base64", encodeImageForVision(imageUri))
            }
            connection.outputStream.use { output ->
                output.write(requestJson.toString().toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val responseCode = connection.responseCode
            val responseText = readConnectionResponse(connection, responseCode)
            if (responseText.isBlank()) {
                throw Exception("Image editing server returned an empty response.")
            }
            val responseJson = JSONObject(responseText)
            if (responseCode !in 200..299 ||
                !responseJson.optBoolean("success", false)
            ) {
                throw Exception(
                    responseJson.optString(
                        "error", "Image editing server returned $responseCode"
                    )
                )
            }

            val encodedImage = responseJson.optString("image_base64")
            if (encodedImage.isBlank()) {
                throw Exception("The image editing server returned no image data.")
            }
            val imageBytes = Base64.decode(encodedImage, Base64.DEFAULT)
            if (BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size) == null) {
                throw Exception("The edited image could not be decoded.")
            }

            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "chopper_generated"
            )
            if (!directory.exists() && !directory.mkdirs()) {
                throw Exception("Could not create the edited-image folder.")
            }
            val file = File(directory, "chopper_edit_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { it.write(imageBytes) }
            return file to responseJson.optString("mime_type", "image/png")
        } finally {
            connection.disconnect()
        }
    }

    private fun addGeneratedImageMessage(
        file: File,
        prompt: String,
        saveMessage: Boolean
    ) {
        if (!file.exists()) {
            addAssistantMessage("This generated image is no longer available on the phone.")
            return
        }

        if (saveMessage && !restoringMessages && currentConversationId != 0L) {
            val marker = generatedImageMarker(file, prompt)
            Thread {
                chopperDao.insertMessage(
                    ChatMessage(
                        conversationId = currentConversationId,
                        text = marker,
                        isUser = false
                    )
                )
            }.start()
        }

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.parseColor("#21262D"))
            }
        }

        card.addView(TextView(this).apply {
            text = "Chopper Image"
            textSize = 18f
            setTextColor(Color.WHITE)
            setPadding(dp(5), dp(3), dp(5), dp(9))
        })

        card.addView(ImageView(this).apply {
            setImageURI(Uri.fromFile(file))
            scaleType = ImageView.ScaleType.CENTER_CROP
            adjustViewBounds = true
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(Color.parseColor("#0D1117"))
            }
        }, LinearLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.76).toInt(),
            (resources.displayMetrics.widthPixels * 0.76).toInt()
        ))

        card.addView(TextView(this).apply {
            text = prompt
            textSize = 14f
            setTextColor(Color.parseColor("#C9D1D9"))
            setPadding(dp(5), dp(10), dp(5), dp(8))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        fun actionButton(label: String, action: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 12f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(Color.parseColor("#6F52B5"))
                }
                setOnClickListener { action() }
            }
        }

        actions.addView(actionButton("Save") { saveGeneratedImageToGallery(file) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginEnd = dp(4) })
        actions.addView(actionButton("Share") { shareGeneratedImage(file) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply {
                marginStart = dp(4); marginEnd = dp(4)
            })
        actions.addView(actionButton("Again") { regenerateImage(prompt) },
            LinearLayout.LayoutParams(0, dp(44), 1f).apply { marginStart = dp(4) })
        card.addView(actions)

        binding.chatContainer.addView(card, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.START
            topMargin = dp(7)
            bottomMargin = dp(7)
        })
        scrollDown()
    }

    private fun regenerateImage(prompt: String) {
        startLoading()
        binding.statusText.text = "Creating Image..."
        Thread {
            try {
                val generated = generateImageWithCloud(prompt)
                runOnUiThread {
                    stopLoading()
                    binding.statusText.text = "Chopper - Ready"
                    addGeneratedImageMessage(generated.first, prompt, true)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    stopLoading()
                    binding.statusText.text = "Chopper - Ready"
                    addAssistantMessage("Image generation failed: ${error.message}")
                }
            }
        }.start()
    }

    private fun generatedImageMarker(file: File, prompt: String): String {
        val encodedPrompt = Base64.encodeToString(
            prompt.toByteArray(Charsets.UTF_8), Base64.NO_WRAP or Base64.URL_SAFE
        )
        return "$GENERATED_IMAGE_MARKER${file.absolutePath}|$encodedPrompt"
    }

    private fun restoreGeneratedImageMessage(marker: String) {
        val parts = marker.removePrefix(GENERATED_IMAGE_MARKER).split('|', limit = 2)
        if (parts.size != 2) return
        val prompt = try {
            String(Base64.decode(parts[1], Base64.URL_SAFE), Charsets.UTF_8)
        } catch (_: Exception) {
            "Generated by Chopper"
        }
        addGeneratedImageMessage(File(parts[0]), prompt, false)
    }

    private fun saveGeneratedImageToGallery(file: File) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Chopper")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
                ) ?: throw Exception("Gallery could not create the image.")
                contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: throw Exception("Gallery could not save the image.")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                Toast.makeText(this, "Saved to Pictures/Chopper", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    this, "Use Share to save this image on this Android version.",
                    Toast.LENGTH_LONG
                ).show()
            }
        } catch (error: Exception) {
            Toast.makeText(this, "Save failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun shareGeneratedImage(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this, "${packageName}.fileprovider", file
            )
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Share Chopper image"))
        } catch (error: Exception) {
            Toast.makeText(this, "Share failed: ${error.message}", Toast.LENGTH_LONG).show()
        }
    }


    // =====================================================
    // MESSAGE BUBBLE
    // =====================================================

    private fun createMessageBubble(
        message: String, isUser: Boolean
    ): TextView {

        val textView = TextView(this)

        val screenWidth = resources.displayMetrics.widthPixels

        textView.maxWidth = (screenWidth * 0.82).toInt()

        textView.textSize = 16f

        textView.setTextColor(Color.WHITE)

        textView.setPadding(
            dp(14), dp(11), dp(14), dp(11)
        )

        val background = GradientDrawable()

        background.cornerRadius = dp(18).toFloat()

        background.setColor(
            if (isUser) {
                Color.parseColor("#3151B7")
            } else {
                Color.parseColor("#21262D")
            }
        )

        textView.background = background

        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        params.gravity = if (isUser) {
            Gravity.END
        } else {
            Gravity.START
        }

        params.topMargin = dp(5)
        params.bottomMargin = dp(5)
        params.marginStart = dp(4)
        params.marginEnd = dp(4)

        textView.layoutParams = params

        textView.setTextIsSelectable(true)

        val linkedText = SpannableString(message)

        Linkify.addLinks(
            linkedText, Linkify.WEB_URLS
        )

        textView.text = linkedText

        textView.linksClickable = true

        textView.movementMethod = LinkMovementMethod.getInstance()

        return textView
    }


    // =====================================================
    // COPY MESSAGE
    // =====================================================

    private fun copyWholeMessage(
        message: String
    ) {
        val clipboard = getSystemService(
            Context.CLIPBOARD_SERVICE
        ) as ClipboardManager

        val clip = ClipData.newPlainText(
            "Chopper response", message
        )

        clipboard.setPrimaryClip(clip)

        Toast.makeText(
            this, "Copied", Toast.LENGTH_SHORT
        ).show()
    }


    // =====================================================
    // LOADING
    // =====================================================

    private fun startLoading() {
        binding.sendButton.isEnabled = false
        logoAnimator?.cancel()

        binding.statusLogo.rotation = 0f
        logoAnimator = ObjectAnimator.ofFloat(
            binding.statusLogo,
            View.ROTATION,
            0f,
            360f
        ).apply {
            duration = 1400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }


    private fun stopLoading() {
        binding.sendButton.isEnabled = true

        logoAnimator?.cancel()
        logoAnimator = null
        binding.statusLogo.rotation = 0f
    }


    // =====================================================
    // CLEAN LOCAL RESPONSE
    // =====================================================

    private fun cleanResponse(
        response: String
    ): String {

        return response.replace(
            "<end_of_turn>", ""
        ).replace(
            "\\n", "\n"
        ).replace(
            "**", ""
        ).replace(
            "Chopper:", ""
        ).trim()
    }


    // =====================================================
    // REMOVE RAW MARKDOWN FROM CLOUD AND LOCAL REPLIES
    // =====================================================

    private fun cleanMarkdownForDisplay(
        response: String
    ): String {
        val cleanedLines = response.replace("\r\n", "\n").lines().mapNotNull { originalLine ->
            val trimmed = originalLine.trim()

            val isHorizontalRule = trimmed.matches(
                Regex("^[-=_]{3,}$")
            )

            val isTableDivider = trimmed.contains("-") && trimmed.matches(
                Regex("^\\|?[\\s:|\\-]+\\|?$")
            )

            if (isHorizontalRule || isTableDivider) {
                null
            } else {
                var line = originalLine

                line = line.replace(
                    Regex("\\*\\*(.*?)\\*\\*"), "\$1"
                )

                line = line.replace(
                    Regex("__(.*?)__"), "\$1"
                )

                line = line.replace(
                    Regex("^\\s*#{1,6}\\s*"), ""
                )

                line = line.replace("```", "")
                line = line.replace("`", "")

                line = line.replace(
                    Regex("^\\s*[-*+]\\s+"), "• "
                )

                if (line.trim().startsWith("|") && line.trim().endsWith("|")) {
                    line = line.trim().trim('|').split('|').map { it.trim() }
                        .filter { it.isNotEmpty() }.joinToString(" — ")
                }

                line.trimEnd()
            }
        }

        return cleanedLines.joinToString("\n").replace(
            Regex("\n{3,}"), "\n\n"
        ).trim()
    }


    // =====================================================
    // ENCRYPTED SEMANTIC MEMORY
    // =====================================================

    private fun handlePrivateMemoryCommand(
        decision: CommandDecision, originalMessage: String
    ) {
        if (decision.action == CommandAction.SAVE_MEMORY && (decision.canonicalKey.isBlank() || decision.value.isBlank())) {
            finishPrivateMemoryCommand(
                "Please tell me exactly what information I should remember."
            )
            return
        }

        authenticateForPrivateMemory {
            Thread {
                try {
                    val response = when (decision.action) {
                        CommandAction.SAVE_MEMORY -> {
                            val expiresAt =
                                if (decision.lifetime == MemoryLifetime.TEMPORARY && decision.expiresAt == 0L) {
                                    System.currentTimeMillis() + 7L * 24L * 60L * 60L * 1000L
                                } else {
                                    decision.expiresAt
                                }

                            privateMemoryVault.save(
                                PrivateMemory(
                                    canonicalKey = decision.canonicalKey,
                                    value = decision.value,
                                    lifetime = decision.lifetime,
                                    expiresAt = expiresAt
                                )
                            )

                            val type = decision.lifetime.name.lowercase()
                            "Saved securely as $type memory."
                        }

                        CommandAction.RECALL_MEMORY -> {
                            recallPrivateMemory(decision, originalMessage)
                        }

                        CommandAction.DELETE_MEMORY -> {
                            if (decision.canonicalKey.isBlank()) {
                                "Please specify which memory I should delete."
                            } else if (privateMemoryVault.deleteByKey(
                                    decision.canonicalKey
                                )
                            ) {
                                "That private memory has been deleted."
                            } else {
                                "I could not find that memory."
                            }
                        }

                        else -> "I could not process that memory command."
                    }

                    runOnUiThread {
                        finishPrivateMemoryCommand(response)
                    }
                } catch (_: UserNotAuthenticatedException) {
                    runOnUiThread {
                        finishPrivateMemoryCommand(
                            "Authentication expired. Please try again."
                        )
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        finishPrivateMemoryCommand(
                            "Private memory error: ${error.message}"
                        )
                    }
                }
            }.start()
        }
    }


    private fun recallPrivateMemory(
        decision: CommandDecision, originalMessage: String
    ): String {
        val text = originalMessage.lowercase()

        if (text.contains("what do you remember about me") || text.contains("what do you know about me") || text.contains(
                "show my memories"
            )
        ) {
            val memories = privateMemoryVault.getAll()
            return if (memories.isEmpty()) {
                "Your private vault is empty."
            } else {
                memories.joinToString(
                    separator = "\n", prefix = "I securely remember:\n"
                ) {
                    "• ${
                        it.canonicalKey.replace(
                            '_', ' '
                        )
                    }: ${it.value} (${it.lifetime.name.lowercase()})"
                }
            }
        }

        val key = decision.canonicalKey
        if (key.isBlank()) {
            return "Please specify what information you want me to recall."
        }

        val memory = privateMemoryVault.find(key)
            ?: return "I don't have that information in your private vault."

        if (key == "date_of_birth" && (text.contains("age") || text.contains("how old"))) {
            val age = calculateAgeFromDateOfBirth(memory.value)
            return if (age == null) {
                "Your date of birth is ${memory.value}, but I could not calculate the age from its format."
            } else {
                "You are $age years old."
            }
        }

        return "Your ${key.replace('_', ' ')} is ${memory.value}."
    }


    private fun calculateAgeFromDateOfBirth(value: String): Int? {
        val formats = listOf(
            "yyyy-MM-dd", "dd-MM-yyyy", "dd/MM/yyyy", "d MMMM yyyy", "MMMM d yyyy", "d MMM yyyy"
        )

        for (pattern in formats) {
            try {
                val formatter = SimpleDateFormat(pattern, Locale.ENGLISH).apply {
                    isLenient = false
                }
                val birthDate = formatter.parse(value) ?: continue
                val birth = Calendar.getInstance().apply { time = birthDate }
                val today = Calendar.getInstance()
                var age = today.get(Calendar.YEAR) - birth.get(Calendar.YEAR)

                if (today.get(Calendar.DAY_OF_YEAR) < birth.get(Calendar.DAY_OF_YEAR)) {
                    age--
                }

                return age.takeIf { it in 0..150 }
            } catch (_: Exception) {
                // Try the next supported date format.
            }
        }

        return null
    }


    private fun finishPrivateMemoryCommand(response: String) {
        stopLoading()
        binding.statusText.text = "Chopper - Ready"
        addAssistantMessage(response)
    }


    private fun authenticateForPrivateMemory(
        onSuccess: () -> Unit
    ) {
        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

        val biometricManager = BiometricManager.from(this)

        if (biometricManager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            finishPrivateMemoryCommand(
                "Set a screen lock or fingerprint before using private memory."
            )
            return
        }

        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this, executor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }

                override fun onAuthenticationError(
                    errorCode: Int, errString: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errString)
                    finishPrivateMemoryCommand("Private memory was not unlocked.")
                }
            })

        val promptInfo =
            BiometricPrompt.PromptInfo.Builder().setTitle("Unlock Chopper private memory")
                .setSubtitle("Confirm that it is you").setAllowedAuthenticators(authenticators)
                .build()

        prompt.authenticate(promptInfo)
    }


    private fun showMemoryManager() {
        authenticateForPrivateMemory {
            Thread {
                try {
                    val memories = privateMemoryVault.getAll()
                    runOnUiThread {
                        showUnlockedMemoryManager(memories)
                    }
                } catch (error: Exception) {
                    runOnUiThread {
                        Toast.makeText(
                            this, "Memory error: ${error.message}", Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }.start()
        }
    }


    private fun showUnlockedMemoryManager(
        memories: List<PrivateMemory>
    ) {
        if (memories.isEmpty()) {
            Toast.makeText(this, "Private vault is empty", Toast.LENGTH_SHORT).show()
            return
        }

        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(24))
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        memories.forEach { memory ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), dp(16), dp(18), dp(14))
                background = roundedBackground("#21262D", 24)
            }

            val type = memory.canonicalKey.replace('_', ' ').replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString()
            }

            card.addView(TextView(this).apply {
                text = "Data type: $type"
                setTextColor(Color.parseColor("#8B949E"))
                textSize = 14f
            })

            card.addView(TextView(this).apply {
                text = "Saved data: ${memory.value}"
                setTextColor(Color.WHITE)
                textSize = 17f
                setPadding(0, dp(9), 0, dp(8))
            })

            card.addView(TextView(this).apply {
                text = "Lifetime: ${memory.lifetime.name.lowercase()}"
                setTextColor(Color.parseColor("#8B949E"))
                textSize = 13f
            })

            card.addView(Button(this).apply {
                text = "Delete"
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = roundedBackground("#8B2C2C", 22)
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Delete memory?")
                        .setMessage("$type: ${memory.value}")
                        .setPositiveButton("Delete") { _, _ ->
                            Thread { privateMemoryVault.delete(memory.id) }.start()
                            (card.parent as? LinearLayout)?.removeView(card)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)
            ).apply { topMargin = dp(14) })

            list.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) })
        }

        val scroll = ScrollView(this).apply { addView(list) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Private Memory")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        showFullScreenDialog(dialog)
    }


    private fun showSettingsMenu() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        fun addSetting(title: String, subtitle: String, action: () -> Unit) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(17), dp(20), dp(17))
                background = roundedBackground("#21262D", 26)
                isClickable = true
                isFocusable = true
            }
            row.addView(TextView(this).apply {
                text = title
                textSize = 18f
                setTextColor(Color.WHITE)
            })
            row.addView(TextView(this).apply {
                text = subtitle
                textSize = 13f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(0, dp(5), 0, 0)
            })
            row.setOnClickListener { action() }
            container.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) })
        }

        fun addToggleSetting(
            title: String,
            subtitle: String,
            enabled: Boolean,
            onChanged: (Boolean) -> Unit
        ) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(17), dp(16), dp(17))
                background = roundedBackground("#21262D", 26)
            }

            val textArea = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@MainActivity).apply {
                    text = title
                    textSize = 18f
                    setTextColor(Color.WHITE)
                })
                addView(TextView(this@MainActivity).apply {
                    text = subtitle
                    textSize = 13f
                    setTextColor(Color.parseColor("#8B949E"))
                    setPadding(0, dp(5), dp(10), 0)
                })
            }

            row.addView(
                textArea,
                LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                )
            )

            val toggle = SwitchCompat(this).apply {
                isChecked = enabled
                contentDescription = "$title ON or OFF"
                setOnCheckedChangeListener { _, checked ->
                    onChanged(checked)
                    Toast.makeText(
                        this@MainActivity,
                        if (checked) {
                            "$title is ON"
                        } else {
                            "$title is OFF"
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            row.addView(toggle)
            row.setOnClickListener {
                toggle.isChecked = !toggle.isChecked
            }

            container.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(14)
                }
            )
        }

        addToggleSetting(
            title = "Hello Chopper",
            subtitle = "Listen for the Chopper wake phrase in the background",
            enabled = isHelloChopperEnabled()
        ) { enabled ->
            setHelloChopperEnabled(enabled)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Settings")
            .setView(container)
            .setNegativeButton("Close", null)
            .create()

        addSetting("Private Memory", "View and delete saved personal data") {
            dialog.dismiss()
            showMemoryManager()
        }
        addSetting(
            title = if (isChopperSidebarEnabled()) {
                "Disable Chopper Sidebar"
            } else {
                "Enable Chopper Sidebar"
            },
            subtitle = if (isChopperSidebarEnabled()) {
                "Remove the floating Chopper handle from the screen"
            } else {
                "Show Chopper above other apps using a floating edge handle"
            }
        ) {
            dialog.dismiss()
            setChopperSidebarEnabled(
                !isChopperSidebarEnabled()
            )
        }
        addSetting("Chopper Autofill", "Choose Chopper as the Android Autofill service") {
            dialog.dismiss()
            openAutofillSettings()
        }
        addSetting("Voice changer", "Choose Chopper's voice and spoken replies") {
            dialog.dismiss()
            showVoiceSettings()
        }

        showFullScreenDialog(dialog)
    }


    private fun showVoiceSettings() {
        val preferences = getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        )
        val selectedPreset = preferences.getString(
            "voice_preset",
            "Natural"
        ) ?: "Natural"

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Color.parseColor("#0D1117"))
        }

        container.addView(TextView(this).apply {
            text = "Voice changer"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), 0, dp(2), dp(5))
        })
        container.addView(TextView(this).apply {
            text = "Choose how Chopper sounds when reading replies."
            textSize = 14f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(2), 0, dp(2), dp(18))
        })

        val scroll = ScrollView(this).apply { addView(container) }
        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()

        fun addVoicePreset(
            name: String,
            description: String,
            rate: Float,
            pitch: Float
        ) {
            val selected = selectedPreset == name
            container.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(15), dp(20), dp(15))
                background = roundedBackground(
                    if (selected) "#6F52B5" else "#21262D",
                    24
                )
                addView(TextView(this@MainActivity).apply {
                    text = if (selected) "$name  ✓" else name
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    text = description
                    textSize = 13f
                    setTextColor(
                        Color.parseColor(if (selected) "#F0EAFE" else "#8B949E")
                    )
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener {
                    saveVoicePreset(name, rate, pitch)
                    Toast.makeText(
                        this@MainActivity,
                        "$name voice selected",
                        Toast.LENGTH_SHORT
                    ).show()
                    dialog.dismiss()
                    showVoiceSettings()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(11) })
        }

        maleAiVoicePresets().forEach { preset ->
            addVoicePreset(
                preset.name,
                preset.description,
                preset.rate,
                preset.pitch
            )
        }

        addVoicePreset("Natural", "Comfortable and clear", 0.96f, 1.0f)
        addVoicePreset("Female – Natural", "Clear and friendly", 0.98f, 1.12f)
        addVoicePreset("Female – Bright", "Lighter and energetic", 1.04f, 1.28f)
        addVoicePreset("Quick", "Faster reading", 1.18f, 1.0f)

        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = roundedBackground("#30363D", 24)
            addView(TextView(this@MainActivity).apply {
                text = "More phone voices"
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Choose an installed male or female Android voice"
                textSize = 13f
                setTextColor(Color.parseColor("#B0B7C3"))
                setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener {
                dialog.dismiss()
                showInstalledVoiceChooser()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(12) })

        val spokenEnabled = preferences.getBoolean(
            "spoken_replies_enabled",
            true
        )
        container.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = roundedBackground("#21262D", 24)
            addView(TextView(this@MainActivity).apply {
                text = if (spokenEnabled) {
                    "Spoken replies  ON"
                } else {
                    "Spoken replies  OFF"
                }
                textSize = 18f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@MainActivity).apply {
                text = "Tap to turn automatic reading on or off"
                textSize = 13f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(0, dp(4), 0, 0)
            })
            setOnClickListener {
                spokenRepliesEnabled = !spokenEnabled
                preferences.edit()
                    .putBoolean("spoken_replies_enabled", spokenRepliesEnabled)
                    .apply()
                if (!spokenRepliesEnabled) {
                    stopSpeakingAndClearHighlight()
                }
                dialog.dismiss()
                showVoiceSettings()
            }
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(5)
            bottomMargin = dp(12)
        })

        showFullScreenDialog(dialog)
    }


    private data class MaleAiVoicePreset(
        val name: String,
        val description: String,
        val rate: Float,
        val pitch: Float
    )


    private fun maleAiVoicePresets(): List<MaleAiVoicePreset> {
        return listOf(
            MaleAiVoicePreset("Male AI 01 — Synth Fast", "Fast computer response voice", 1.12f, 0.75f),
            MaleAiVoicePreset("Male AI 02 — Assistant Energy", "Energetic daily assistant", 1.09f, 0.82f)
        )
    }


    private fun showInstalledVoiceChooser() {
        val availableVoices = textToSpeech?.voices
            ?.filter { voice ->
                voice.locale.language == Locale.getDefault().language ||
                        voice.locale.language == Locale.ENGLISH.language
            }
            ?.sortedWith(
                compareBy<Voice> { it.isNetworkConnectionRequired }
                    .thenBy { it.name }
            )
            ?.take(16)
            .orEmpty()

        if (availableVoices.isEmpty()) {
            Toast.makeText(
                this,
                "No additional phone voices are installed.",
                Toast.LENGTH_LONG
            ).show()
            showVoiceSettings()
            return
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(24))
            setBackgroundColor(Color.parseColor("#0D1117"))
        }
        panel.addView(TextView(this).apply {
            text = "Phone voices"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(2), 0, dp(2), dp(5))
        })
        panel.addView(TextView(this).apply {
            text = "The voices shown here are provided by your phone."
            textSize = 14f
            setTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(2), 0, dp(2), dp(17))
        })

        val scroll = ScrollView(this).apply { addView(panel) }
        val dialog = AlertDialog.Builder(this)
            .setView(scroll)
            .setNegativeButton("Back") { _, _ -> showVoiceSettings() }
            .create()

        availableVoices.forEachIndexed { index, voice ->
            val displayName = "Voice ${index + 1}"
            panel.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(14), dp(20), dp(14))
                background = roundedBackground("#21262D", 24)
                addView(TextView(this@MainActivity).apply {
                    text = displayName
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                addView(TextView(this@MainActivity).apply {
                    val connection = if (voice.isNetworkConnectionRequired) {
                        "Online"
                    } else {
                        "On-device"
                    }
                    text = "${voice.locale.displayName} • $connection"
                    textSize = 13f
                    setTextColor(Color.parseColor("#8B949E"))
                    setPadding(0, dp(4), 0, 0)
                })
                setOnClickListener {
                    saveSystemVoice(voice.name, displayName)
                    dialog.dismiss()
                    showVoiceSettings()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }

        showFullScreenDialog(dialog)
    }


    // =====================================================
    // RESTORE CONVERSATION
    // =====================================================
    private val chatPreferences by lazy {
        getSharedPreferences("chopper_chat_manager", Context.MODE_PRIVATE)
    }


    private data class HistoryEntry(
        val conversation: Conversation, val messages: List<ChatMessage>
    )


    private fun showConversationHistory() {
        Thread {
            val entries = mutableListOf<HistoryEntry>()

            chopperDao.getAllConversations().forEach { conversation ->
                val messages = chopperDao.getMessages(conversation.id)
                val firstUserMessage = messages.firstOrNull { it.isUser }

                if (firstUserMessage == null) {
                    if (conversation.id != currentConversationId) {
                        chopperDao.deleteMessages(conversation.id)
                        chopperDao.deleteConversation(conversation.id)
                        setConversationPinned(conversation.id, false)
                        setConversationProject(conversation.id, null)
                    }
                } else {
                    var correctedConversation = conversation

                    if (conversation.title.equals("New Chat", ignoreCase = true)) {
                        val recoveredTitle =
                            firstUserMessage.text.replace("\n", " ").trim().take(45)

                        if (recoveredTitle.isNotEmpty()) {
                            chopperDao.updateConversation(conversation.id, recoveredTitle)
                            correctedConversation = Conversation(
                                id = conversation.id, title = recoveredTitle
                            )
                        }
                    }

                    entries.add(
                        HistoryEntry(
                            conversation = correctedConversation, messages = messages
                        )
                    )
                }
            }

            val pinnedIds = getPinnedConversationIds()
            val sortedEntries = entries.sortedWith(compareByDescending<HistoryEntry> {
                pinnedIds.contains(it.conversation.id)
            }.thenByDescending {
                it.conversation.id
            })

            runOnUiThread {
                showSearchableHistoryDialog(sortedEntries, pinnedIds)
            }
        }.start()
    }


    private fun showSearchableHistoryDialog(
        allEntries: List<HistoryEntry>, pinnedIds: Set<Long>
    ) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(22), dp(16), dp(12))
            setBackgroundColor(Color.parseColor("#0D1117"))
            minimumHeight = resources.displayMetrics.heightPixels
        }

        val historyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        historyHeader.addView(TextView(this).apply {
            text = "Chopper"
            textSize = 25f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(58), 1f).apply {
            gravity = Gravity.CENTER_VERTICAL
        })

        val closeHistoryButton = Button(this).apply {
            text = "✕"
            isAllCaps = false
            textSize = 18f
            setTextColor(Color.WHITE)
            minWidth = 0
            minHeight = 0
            setPadding(0, 0, 0, 0)
            background = roundedBackground("#21262D", 24)
        }

        historyHeader.addView(closeHistoryButton, LinearLayout.LayoutParams(dp(48), dp(48)))
        container.addView(historyHeader)

        val searchInput = EditText(this).apply {
            hint = "Search Chopper"
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(20), dp(8), dp(20), dp(8))
            background = roundedBackground("#21262D", 28)
        }

        val resultList = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(10)
            setPadding(0, dp(12), 0, dp(12))
            clipToPadding = false
        }

        container.addView(
            searchInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52)
            )
        )

        container.addView(
            resultList, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        )

        var displayedEntries = allEntries
        var actionOffset = 4
        var historyDialog: AlertDialog? = null

        val adapter = object : ArrayAdapter<String>(
            this, android.R.layout.simple_list_item_1, mutableListOf()
        ) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val entryPosition = position - actionOffset

                if (entryPosition in displayedEntries.indices) {
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        setPadding(dp(20), dp(8), dp(8), dp(8))
                        background = roundedBackground("#21262D", 24)
                    }

                    row.addView(TextView(this@MainActivity).apply {
                        text = getItem(position).orEmpty()
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        maxLines = 2
                        setPadding(0, 0, dp(8), 0)
                    }, LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        1f
                    ).apply { gravity = Gravity.CENTER_VERTICAL })

                    row.addView(TextView(this@MainActivity).apply {
                        text = "⋮"
                        contentDescription = "Conversation options"
                        setTextColor(Color.WHITE)
                        textSize = 26f
                        gravity = Gravity.CENTER
                        background = roundedBackground("#30363D", 22)
                        setOnClickListener {
                            val selectedEntry = displayedEntries.getOrNull(entryPosition)
                            if (selectedEntry != null) {
                                historyDialog?.dismiss()
                                showConversationActions(selectedEntry.conversation)
                            }
                        }
                    }, LinearLayout.LayoutParams(dp(46), dp(46)))

                    return row
                }

                return TextView(this@MainActivity).apply {
                    text = getItem(position).orEmpty()
                    gravity = Gravity.CENTER_VERTICAL
                    if (actionOffset == 4 && position == 3) {
                        setTextColor(Color.parseColor("#A78BFA"))
                        textSize = 20f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(dp(8), dp(14), dp(8), dp(4))
                        background = ColorDrawable(Color.TRANSPARENT)
                    } else {
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.NORMAL)
                        setPadding(dp(20), dp(16), dp(20), dp(16))
                        background = roundedBackground("#21262D", 24)
                    }
                }
            }
        }

        resultList.adapter = adapter

        fun refreshResults(searchText: String) {
            val query = searchText.trim().lowercase()

            displayedEntries = if (query.isEmpty()) {
                allEntries
            } else {
                allEntries.filter { entry ->
                    entry.conversation.title.lowercase()
                        .contains(query) || entry.messages.any { message ->
                        message.text.lowercase().contains(query)
                    }
                }
            }

            actionOffset = if (query.isEmpty()) 4 else 0

            val labels = mutableListOf<String>()

            if (query.isEmpty()) {
                labels.add("＋ New Chat")
                labels.add("🗂 Projects")
                labels.add("⚙ Settings")
                labels.add("Chat History")
            }

            displayedEntries.forEach { entry ->
                val conversation = entry.conversation
                val pin = if (pinnedIds.contains(conversation.id)) "📌 " else ""
                val project = getConversationProject(conversation.id)
                val projectLabel = if (project.isNullOrBlank()) "" else "  [$project]"

                val matchingMessage = if (query.isEmpty()) {
                    null
                } else {
                    entry.messages.firstOrNull {
                        it.text.lowercase().contains(query)
                    }
                }

                val preview = matchingMessage?.text?.replace("\n", " ")?.trim()?.take(90)

                labels.add(
                    if (preview.isNullOrEmpty() || preview == conversation.title) {
                        "$pin${conversation.title}$projectLabel"
                    } else {
                        "$pin${conversation.title}$projectLabel\n$preview"
                    }
                )
            }

            if (labels.isEmpty()) {
                labels.add("No matching chats")
            }

            adapter.clear()
            adapter.addAll(labels)
            adapter.notifyDataSetChanged()
        }

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()
        historyDialog = dialog

        closeHistoryButton.setOnClickListener { dialog.dismiss() }

        resultList.setOnItemClickListener { _, _, position, _ ->
            val queryIsEmpty = searchInput.text.toString().trim().isEmpty()

            if (queryIsEmpty && position == 0) {
                dialog.dismiss()
                createNewConversation()
            } else if (queryIsEmpty && position == 1) {
                showProjects()
            } else if (queryIsEmpty && position == 2) {
                dialog.dismiss()
                showSettingsMenu()
            } else if (queryIsEmpty && position == 3) {
                return@setOnItemClickListener // <-- CHANGE THIS TO: return
            } else {
                val entryPosition = position - actionOffset
                if (entryPosition in displayedEntries.indices) {
                    dialog.dismiss()
                    openConversation(displayedEntries[entryPosition].conversation.id)
                }
            }
        }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?, start: Int, count: Int, after: Int
            ) = Unit

            override fun onTextChanged(
                text: CharSequence?, start: Int, before: Int, count: Int
            ) {
                refreshResults(text?.toString().orEmpty())
            }

            override fun afterTextChanged(text: Editable?) = Unit
        })

        refreshResults("")

        showFullScreenDialog(dialog)
    }


    private fun showConversationActions(conversation: Conversation) {
        val pinned = getPinnedConversationIds().contains(conversation.id)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(8))
            background = roundedBackground("#161B22", 28)
        }

        panel.addView(TextView(this).apply {
            text = conversation.title
            textSize = 22f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(4), dp(8), dp(14))
        })

        val dialog = AlertDialog.Builder(this).setView(panel).create()

        fun addAction(label: String, color: String = "#21262D", action: () -> Unit) {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(20), 0, dp(20), 0)
                background = roundedBackground(color, 22)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
            ).apply { bottomMargin = dp(10) })
        }

        addAction("Open chat") { openConversation(conversation.id) }
        addAction(if (pinned) "Unpin chat" else "Pin chat") {
            setConversationPinned(conversation.id, !pinned)
            showConversationHistory()
        }
        addAction("Rename chat") { renameConversation(conversation) }
        addAction("Add to project") { chooseProjectForConversation(conversation.id) }
        addAction("Delete chat", "#6E2B2B") { confirmDeleteConversation(conversation) }
        addAction("Cancel", "#30363D") { }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }


    private fun renameConversation(conversation: Conversation) {
        showChopperInputDialog(
            title = "Rename chat",
            hint = "Chat name",
            initialText = conversation.title,
            confirmText = "Save"
        ) { enteredText ->
            val title = enteredText.trim().take(60)
            if (title.isNotEmpty()) {
                Thread {
                    chopperDao.updateConversation(conversation.id, title)
                    runOnUiThread { showConversationHistory() }
                }.start()
            }
        }
    }


    private fun showChopperInputDialog(
        title: String,
        hint: String,
        initialText: String = "",
        confirmText: String,
        onConfirm: (String) -> Unit
    ) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = roundedBackground("#161B22", 28)
        }

        panel.addView(TextView(this).apply {
            text = title
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val input = EditText(this).apply {
            this.hint = hint
            setText(initialText)
            setSelection(text.length)
            setSingleLine(true)
            textSize = 16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(18), 0, dp(18), 0)
            background = roundedBackground("#21262D", 25)
        }
        panel.addView(input, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(54)
        ).apply {
            topMargin = dp(18)
            bottomMargin = dp(20)
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val dialog = AlertDialog.Builder(this).setView(panel).create()

        fun actionButton(label: String, color: String, action: () -> Unit) =
            TextView(this).apply {
                text = label
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                background = roundedBackground(color, 24)
                setOnClickListener { action() }
            }

        buttonRow.addView(
            actionButton("Cancel", "#30363D") { dialog.dismiss() },
            LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginEnd = dp(10)
            }
        )
        buttonRow.addView(
            actionButton(confirmText, "#6F52B5") {
                val value = input.text.toString().trim()
                if (value.isNotEmpty()) {
                    dialog.dismiss()
                    onConfirm(value)
                }
            },
            LinearLayout.LayoutParams(0, dp(50), 1f)
        )
        panel.addView(buttonRow)

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.72f)
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }


    private fun confirmDeleteConversation(conversation: Conversation) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(20))
            background = roundedBackground("#161B22", 28)
        }

        panel.addView(TextView(this).apply {
            text = "Delete this chat?"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        panel.addView(TextView(this).apply {
            text = "\"${conversation.title}\" and all its messages will be permanently deleted."
            textSize = 16f
            setTextColor(Color.parseColor("#AAB2BF"))
            setLineSpacing(0f, 1.12f)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
            bottomMargin = dp(24)
        })

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .create()

        val cancelButton = TextView(this).apply {
            text = "Cancel"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBackground("#30363D", 24)
            setOnClickListener { dialog.dismiss() }
        }

        val deleteButton = TextView(this).apply {
            text = "Delete"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = roundedBackground("#A33131", 24)
            setOnClickListener {
                dialog.dismiss()
                Thread {
                    chopperDao.deleteMessages(conversation.id)
                    chopperDao.deleteConversation(conversation.id)
                    setConversationPinned(conversation.id, false)
                    setConversationProject(conversation.id, null)

                    runOnUiThread {
                        if (currentConversationId == conversation.id) {
                            createNewConversation()
                        } else {
                            showConversationHistory()
                        }
                    }
                }.start()
            }
        }

        buttonRow.addView(cancelButton, LinearLayout.LayoutParams(
            0,
            dp(50),
            1f
        ).apply { marginEnd = dp(10) })

        buttonRow.addView(deleteButton, LinearLayout.LayoutParams(
            0,
            dp(50),
            1f
        ))

        panel.addView(buttonRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.72f)
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        dialog.show()
    }


    private fun getProjects(): MutableSet<String> {
        return chatPreferences.getStringSet("projects", emptySet())?.toMutableSet()
            ?: mutableSetOf()
    }


    private fun showProjects() {
        val projects = getProjects().sorted()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(8))
            background = roundedBackground("#161B22", 28)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = "Projects"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, dp(52), 1f))

        val dialog = AlertDialog.Builder(this).setView(panel).create()

        val close = TextView(this).apply {
            text = "✕"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground("#30363D", 22)
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(close, LinearLayout.LayoutParams(dp(44), dp(44)))
        panel.addView(header)

        fun addProjectRow(label: String, action: () -> Unit) {
            panel.addView(TextView(this).apply {
                text = label
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(20), 0, dp(20), 0)
                background = roundedBackground("#21262D", 22)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            ).apply { bottomMargin = dp(10) })
        }

        addProjectRow("＋  Create project") { createProject() }

        if (projects.isEmpty()) {
            panel.addView(TextView(this).apply {
                text = "No projects yet"
                textSize = 14f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(dp(10), dp(8), dp(10), dp(16))
            })
        } else {
            projects.forEach { project ->
                addProjectRow("🗂  $project") { showProjectChats(project) }
            }
        }

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }


    private fun createProject(afterCreate: ((String) -> Unit)? = null) {
        showChopperInputDialog(
            title = "Create project",
            hint = "Project name",
            confirmText = "Create"
        ) { enteredText ->
            val name = enteredText.trim().take(50)
            val projects = getProjects()
            projects.add(name)
            chatPreferences.edit().putStringSet("projects", projects).apply()
            afterCreate?.invoke(name)
        }
    }


    private fun chooseProjectForConversation(conversationId: Long) {
        val projects = getProjects().sorted()
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(12))
            background = roundedBackground("#161B22", 28)
        }

        panel.addView(TextView(this).apply {
            text = "Add to project"
            textSize = 23f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, dp(4), dp(16))
        })

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val dialog = AlertDialog.Builder(this).setView(panel).create()

        fun addProjectAction(label: String, color: String = "#21262D", action: () -> Unit) {
            actions.addView(TextView(this).apply {
                text = label
                textSize = 17f
                gravity = Gravity.CENTER_VERTICAL
                setTextColor(Color.WHITE)
                setPadding(dp(20), 0, dp(20), 0)
                background = roundedBackground(color, 24)
                setOnClickListener {
                    dialog.dismiss()
                    action()
                }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54)
            ).apply { bottomMargin = dp(10) })
        }

        addProjectAction("＋  Create project", "#6F52B5") {
            createProject { name ->
                setConversationProject(conversationId, name)
                showConversationHistory()
            }
        }
        addProjectAction("Remove from project", "#6E2B2B") {
            setConversationProject(conversationId, null)
            showConversationHistory()
        }
        projects.forEach { project ->
            addProjectAction("📁  $project") {
                setConversationProject(conversationId, project)
                showConversationHistory()
            }
        }
        addProjectAction("Cancel", "#30363D") { }

        val scroll = ScrollView(this).apply { addView(actions) }
        panel.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dialog.window?.setDimAmount(0.72f)
            dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            dialog.window?.setLayout(
                (resources.displayMetrics.widthPixels * 0.90f).toInt(),
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }


    private fun showProjectChats(project: String) {
        Thread {
            val conversations = chopperDao.getAllConversations().filter {
                getConversationProject(it.id) == project && chopperDao.getMessages(it.id)
                    .any { message -> message.isUser }
            }

            runOnUiThread {
                if (conversations.isEmpty()) {
                    Toast.makeText(this, "No chats in $project", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this).setTitle(project)
                        .setItems(conversations.map { it.title }.toTypedArray()) { _, position ->
                            openConversation(conversations[position].id)
                        }.setNegativeButton("Close", null).show()
                }
            }
        }.start()
    }


    private fun getPinnedConversationIds(): MutableSet<Long> {
        return chatPreferences.getStringSet("pinned_conversations", emptySet())
            ?.mapNotNull { it.toLongOrNull() }?.toMutableSet() ?: mutableSetOf()
    }


    private fun setConversationPinned(conversationId: Long, pinned: Boolean) {
        val ids = getPinnedConversationIds()
        if (pinned) ids.add(conversationId) else ids.remove(conversationId)
        chatPreferences.edit()
            .putStringSet("pinned_conversations", ids.map { it.toString() }.toSet()).apply()
    }


    private fun getConversationProject(conversationId: Long): String? {
        return chatPreferences.getString("project_$conversationId", null)
    }


    private fun setConversationProject(conversationId: Long, project: String?) {
        val editor = chatPreferences.edit()
        if (project == null) editor.remove("project_$conversationId")
        else editor.putString("project_$conversationId", project)
        editor.apply()
    }


    private fun createNewConversation() {
        Thread {
            val newConversationId = chopperDao.insertConversation(
                Conversation(
                    title = "New Chat"
                )
            )

            runOnUiThread {
                currentConversationId = newConversationId

                chatPreferences.edit().putLong("last_conversation_id", newConversationId).apply()

                conversationTitleSet = false

                restoringMessages = false

                binding.chatContainer.removeAllViews()

                showWelcomeMessage()
            }
        }.start()
    }


    private fun openConversation(
        conversationId: Long
    ) {
        Thread {
            val messages = chopperDao.getMessages(
                conversationId
            )

            runOnUiThread {
                currentConversationId = conversationId

                chatPreferences.edit().putLong("last_conversation_id", conversationId).apply()

                conversationTitleSet = messages.any {
                    it.isUser
                }

                binding.chatContainer.removeAllViews()

                restoringMessages = true

                messages.forEach { message ->
                    if (message.isUser) {
                        addUserMessage(message.text)
                    } else if (message.text.startsWith(GENERATED_IMAGE_MARKER)) {
                        restoreGeneratedImageMessage(message.text)
                    } else {
                        addAssistantMessage(message.text)
                    }
                }

                restoringMessages = false

                if (messages.isEmpty()) {
                    showWelcomeMessage()
                }
            }
        }.start()
    }

    private fun loadOrCreateConversation(
        savedInstanceState: Bundle?
    ) {
        Thread {
            val restoredConversationId = savedInstanceState?.getLong(
                "current_conversation_id", 0L
            ) ?: 0L

            val sidebarConversationId = intent.getLongExtra(
                EXTRA_SIDEBAR_CONVERSATION_ID,
                0L
            )
            intent.removeExtra(EXTRA_SIDEBAR_CONVERSATION_ID)

            val requestedConversationId = when {
                sidebarConversationId != 0L -> sidebarConversationId
                restoredConversationId != 0L -> restoredConversationId
                else -> 0L
            }

            val requestedConversationExists = requestedConversationId != 0L &&
                    chopperDao.getAllConversations().any {
                        it.id == requestedConversationId
                    }

            currentConversationId = if (requestedConversationExists) {
                requestedConversationId
            } else {
                chopperDao.insertConversation(Conversation(title = "New Chat"))
            }

            chatPreferences.edit()
                .putLong("last_conversation_id", currentConversationId)
                .apply()

            val savedMessages = chopperDao.getMessages(
                currentConversationId
            )
            conversationTitleSet = savedMessages.any {
                it.isUser
            }

            runOnUiThread {
                binding.chatContainer.removeAllViews()

                if (savedMessages.isEmpty()) {
                    showWelcomeMessage()
                } else {
                    restoringMessages = true

                    savedMessages.forEach { message ->
                        if (message.isUser) {
                            addUserMessage(message.text)
                        } else if (message.text.startsWith(GENERATED_IMAGE_MARKER)) {
                            restoreGeneratedImageMessage(message.text)
                        } else {
                            addAssistantMessage(message.text)
                        }
                    }

                    restoringMessages = false
                }
            }
        }.start()
    }


    // =====================================================
    // SCROLL
    // =====================================================

    private fun scrollDown() {
        binding.chatScroll.post {
            binding.chatScroll.fullScroll(
                View.FOCUS_DOWN
            )
        }
    }


    private fun roundedBackground(
        color: String,
        radiusDp: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Color.parseColor(color))
            cornerRadius = dp(radiusDp).toFloat()
        }
    }


    private fun showFullScreenDialog(dialog: AlertDialog) {
        dialog.setOnShowListener {
            dialog.window?.decorView?.setPadding(0, 0, 0, 0)
            dialog.window?.setBackgroundDrawable(
                ColorDrawable(Color.parseColor("#0D1117"))
            )
            dialog.window?.setLayout(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }
        dialog.show()
    }


    // =====================================================
    // DP
    // =====================================================

    private fun dp(
        value: Int
    ): Int {

        return (value * resources.displayMetrics.density).toInt()
    }


    override fun onDestroy() {
        unregisterUnlockReceiver()
        logoAnimator?.cancel()
        logoAnimator = null
        manualVoiceStop = true
        conversationModeEnabled = false
        voiceHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.cancel()
        speechRecognizer?.destroy()
        speechRecognizer = null
        dismissListeningDialog()
        dismissWakeLogoScreen()
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        resumeWakeWordService()
        super.onDestroy()
    }
}