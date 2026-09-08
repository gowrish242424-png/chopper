package com.example.choppermobile.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.choppermobile.MainActivity
import com.example.choppermobile.R
import com.example.choppermobile.WakeWordService
import com.example.choppermobile.data.ChatMessage
import com.example.choppermobile.data.ChopperDao
import com.example.choppermobile.data.ChopperDatabase
import com.example.choppermobile.data.Conversation
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs


class ChopperSidebarService : Service() {

    companion object {
        const val ACTION_START =
            "com.example.choppermobile.sidebar.START"

        const val ACTION_STOP =
            "com.example.choppermobile.sidebar.STOP"

        private const val CHANNEL_ID =
            "chopper_sidebar_channel"

        private const val NOTIFICATION_ID = 2402

        private const val DEFAULT_WIDTH_DP = 310
        private const val DEFAULT_HEIGHT_DP = 480
        private const val DEFAULT_TOP_DP = 48
    }

    private lateinit var windowManager: WindowManager
    private var handleView: View? = null
    private var sidebarView: View? = null
    private val sidebarMessages = mutableListOf<SidebarMessage>()
    private lateinit var chopperDao: ChopperDao
    @Volatile
    private var currentConversationId = 0L
    private val conversationLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var conversationGeneration = 0
    private var panelLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var panelListenerRoot: View? = null
    private var textToSpeech: TextToSpeech? = null
    private var textToSpeechReady = false
    private var pendingSpeech: String? = null
    private var pendingReplyCount = 0
    private var refreshActiveConversationUi: (() -> Unit)? = null
    private var updateActiveInputBusyState: ((Boolean) -> Unit)? = null

    private data class SidebarMessage(val isUser: Boolean, val text: String)

    private data class GeneratedImageResult(
        val file: File,
        val notice: String
    )

    private data class StoredGeneratedImage(
        val file: File,
        val prompt: String,
        val notice: String
    )

    private data class SidebarHistoryEntry(
        val conversationId: Long,
        val title: String,
        val preview: String
    )


    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        chopperDao = ChopperDatabase
            .getInstance(applicationContext)
            .chopperDao()

        createNotificationChannel()
        loadSidebarHistory()
        initializeTextToSpeech()
        synchronizeSidebarHistoryWithDatabase()
        startAsForegroundService()

        if (Settings.canDrawOverlays(this)) {
            showFloatingHandle()
        } else {
            stopSelf()
        }
    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                if (
                    Settings.canDrawOverlays(this) &&
                    handleView == null &&
                    sidebarView == null
                ) {
                    showFloatingHandle()
                }
            }
        }

        return START_STICKY
    }


    override fun onBind(intent: Intent?): IBinder? = null


    override fun onDestroy() {
        conversationGeneration += 1
        pendingReplyCount = 0
        refreshActiveConversationUi = null
        updateActiveInputBusyState = null
        stopSidebarSpeech()
        textToSpeech?.shutdown()
        textToSpeech = null
        textToSpeechReady = false
        mainHandler.removeCallbacksAndMessages(null)
        removePanelLayoutListener()
        removeViewSafely(sidebarView)
        removeViewSafely(handleView)
        sidebarView = null
        handleView = null
        super.onDestroy()
    }


    private fun startAsForegroundService() {
        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Chopper Sidebar",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps the user-enabled Chopper sidebar available"
            setShowBadge(false)
        }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }


    private fun createNotification(): Notification {
        val openIntent = Intent(
            this,
            MainActivity::class.java
        )

        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Chopper sidebar is active")
            .setContentText("Tap the floating handle to open Chopper")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }


    private fun showFloatingHandle() {
        if (handleView != null || sidebarView != null) {
            return
        }

        val handle = View(this).apply {
            contentDescription = "Swipe inward to open Chopper sidebar"
            background = roundedBackground("#B36F52B5", 6)
            elevation = dp(6).toFloat()
        }

        val params = WindowManager.LayoutParams(
            dp(8),
            dp(112),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = 0
            y = 0
        }

        var initialY = 0
        var touchStartX = 0f
        var touchStartY = 0f
        var verticalDrag = false

        handle.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialY = params.y
                    touchStartX = event.rawX
                    touchStartY = event.rawY
                    verticalDrag = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val horizontalMovement = event.rawX - touchStartX
                    val verticalMovement = event.rawY - touchStartY

                    if (
                        abs(verticalMovement) > dp(6) &&
                        abs(verticalMovement) > abs(horizontalMovement)
                    ) {
                        verticalDrag = true
                    }

                    if (verticalDrag) {
                        params.y = initialY + verticalMovement.toInt()
                    }

                    try {
                        windowManager.updateViewLayout(handle, params)
                    } catch (_: Exception) {
                        // The overlay may have been removed while dragging.
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val inwardSwipe =
                        touchStartX - event.rawX > dp(24)
                    val simpleTap =
                        abs(event.rawX - touchStartX) < dp(6) &&
                                abs(event.rawY - touchStartY) < dp(6)

                    if (inwardSwipe || simpleTap) {
                        handle.performClick()
                        showSidebarPanel()
                    }
                    true
                }

                else -> false
            }
        }

        try {
            windowManager.addView(handle, params)
            handleView = handle
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Unable to show Chopper sidebar: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
        }
    }


    private fun showSidebarPanel() {
        if (sidebarView != null) return
        removeViewSafely(handleView)
        handleView = null

        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        val minWidth = dp(250)
        val minHeight = dp(280)
        val maxWidth = (screenWidth - dp(8)).coerceAtLeast(minWidth)
        val maxHeight = (screenHeight - dp(24)).coerceAtLeast(minHeight)
        val preferences = getSharedPreferences("chopper_sidebar_settings", Context.MODE_PRIVATE)
        var userWidth = preferences.getInt(
            "sidebar_width",
            dp(DEFAULT_WIDTH_DP)
        ).coerceIn(minWidth, maxWidth)
        var userHeight = preferences.getInt(
            "sidebar_height",
            dp(DEFAULT_HEIGHT_DP)
        ).coerceIn(minHeight, maxHeight)
        var userX = preferences.getInt("sidebar_x", screenWidth - userWidth)
            .coerceIn(0, (screenWidth - userWidth).coerceAtLeast(0))
        var userY = preferences.getInt("sidebar_y", dp(DEFAULT_TOP_DP))
            .coerceIn(0, (screenHeight - userHeight).coerceAtLeast(0))

        val params = WindowManager.LayoutParams(
            userWidth, userHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = userX
            y = userY
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        var keyboardAdjusted = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), 0)
            background = roundedBackground("#F20D1117", 24)
            elevation = dp(14).toFloat()
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val title = TextView(this).apply {
            text = "Chopper"
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(4), 0, 0, 0)
            isSingleLine = true
            setMinWidth(0)
            contentDescription = "Drag to move Chopper"
        }
        val menuButton = compactHeaderButton("☰").apply {
            contentDescription = "Open Chopper menu"
        }
        header.addView(menuButton)
        header.addView(title, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(compactHeaderButton("—").apply {
            contentDescription = "Collapse Chopper sidebar"
            setOnClickListener { hideSidebarPanel() }
        })
        header.addView(compactHeaderButton("↗").apply {
            contentDescription = "Open full Chopper"
            setOnClickListener { openFullChopper() }
        })
        header.addView(compactHeaderButton("✕").apply {
            contentDescription = "Close and start a new conversation"
            setOnClickListener { startNewSidebarConversation() }
        })
        root.addView(header)

        val chatContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(4))
        }
        val chatScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(chatContainer)
        }
        root.addView(chatScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(6) })

        fun copySidebarMessage(text: String) {
            val clipboard = getSystemService(
                Context.CLIPBOARD_SERVICE
            ) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Chopper response", text)
            )
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show()
        }

        fun addBubble(
            message: SidebarMessage,
            allowCopy: Boolean = !message.isUser
        ): TextView {
            val generatedImage = parseGeneratedImageMarker(message.text)
            if (!message.isUser && generatedImage != null) {
                val file = generatedImage.file
                val prompt = generatedImage.prompt
                val notice = generatedImage.notice
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(9), dp(9), dp(9), dp(9))
                    background = roundedBackground("#21262D", 18)
                }
                card.addView(TextView(this).apply {
                    text = "Chopper Image"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(dp(3), dp(2), dp(3), dp(7))
                })

                if (file.exists()) {
                    card.addView(ImageView(this).apply {
                        setImageURI(Uri.fromFile(file))
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        adjustViewBounds = true
                        contentDescription = "Image generated by Chopper"
                        background = roundedBackground("#0D1117", 15)
                    }, LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(210)
                    ))
                } else {
                    card.addView(TextView(this).apply {
                        text = "This generated image is no longer available on the phone."
                        textSize = 14f
                        setTextColor(Color.parseColor("#F0A0A0"))
                        setPadding(dp(3), dp(10), dp(3), dp(10))
                    })
                }

                card.addView(TextView(this).apply {
                    text = prompt
                    textSize = 13f
                    setTextColor(Color.parseColor("#C9D1D9"))
                    setPadding(dp(3), dp(8), dp(3), dp(6))
                    setTextIsSelectable(true)
                })

                if (notice.isNotBlank()) {
                    card.addView(TextView(this).apply {
                        text = "ℹ $notice"
                        textSize = 11f
                        setTextColor(Color.parseColor("#D6B4FF"))
                        setPadding(dp(3), dp(1), dp(3), dp(7))
                        setTextIsSelectable(true)
                    })
                }

                if (file.exists()) {
                    val actions = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                    }
                    actions.addView(actionButton("Save").apply {
                        setOnClickListener { saveGeneratedImageToGallery(file) }
                    }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        marginEnd = dp(3)
                    })
                    actions.addView(actionButton("Share").apply {
                        setOnClickListener { shareGeneratedImage(file) }
                    }, LinearLayout.LayoutParams(0, dp(40), 1f).apply {
                        marginStart = dp(3)
                    })
                    card.addView(actions)
                }

                chatContainer.addView(card, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.START
                    topMargin = dp(6)
                    marginEnd = dp(12)
                })
                chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                return TextView(this)
            }

            val wrapper = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            val bubble = TextView(this).apply {
                text = if (message.isUser) {
                    message.text
                } else {
                    cleanMarkdownForDisplay(message.text)
                }
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = roundedBackground(
                    if (message.isUser) "#3658C5" else "#21262D",
                    17
                )
                setTextIsSelectable(true)
            }
            wrapper.addView(bubble)

            if (!message.isUser && allowCopy) {
                wrapper.addView(TextView(this).apply {
                    text = "Copy"
                    textSize = 12f
                    setTextColor(Color.parseColor("#8B949E"))
                    setPadding(dp(10), dp(3), dp(10), dp(2))
                    contentDescription = "Copy Chopper response"
                    setOnClickListener { copySidebarMessage(message.text) }
                })
            }

            chatContainer.addView(wrapper, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = if (message.isUser) Gravity.END else Gravity.START
                topMargin = dp(6)
                marginStart = if (message.isUser) dp(30) else 0
                marginEnd = if (message.isUser) 0 else dp(30)
            })
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
            return bubble
        }

        fun renderConversation() {
            chatContainer.removeAllViews()
            if (sidebarMessages.isEmpty() && pendingReplyCount == 0) {
                addBubble(
                    SidebarMessage(false, "Ask Chopper anything."),
                    allowCopy = false
                )
            } else {
                sidebarMessages.forEach { addBubble(it) }
                if (pendingReplyCount > 0) {
                    addBubble(
                        SidebarMessage(false, "Chopper is thinking..."),
                        allowCopy = false
                    )
                }
            }
        }

        renderConversation()

        val menuContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(4), dp(2), dp(8))
        }
        val menuScroll = ScrollView(this).apply {
            visibility = View.GONE
            isFillViewport = true
            addView(menuContent)
        }
        root.addView(menuScroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = dp(6) })

        val inputRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(4), dp(4))
            background = roundedBackground("#21262D", 24)
        }
        val messageInput = EditText(this).apply {
            hint = "Message Chopper"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#8B949E"))
            setPadding(dp(6), dp(6), dp(4), dp(6))
            minLines = 1
            maxLines = 3
            background = null
        }
        inputRow.addView(messageInput, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val sendButton = actionButton("↑", "#6F52B5").apply {
            textSize = 22f
            contentDescription = "Send message"
        }
        inputRow.addView(sendButton, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(inputRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })

        fun showConversationPage() {
            menuScroll.visibility = View.GONE
            chatScroll.visibility = View.VISIBLE
            inputRow.visibility = View.VISIBLE
            menuButton.text = "☰"
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        }

        fun addMenuRow(
            label: String,
            description: String? = null,
            action: () -> Unit
        ): View {
            return LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                background = roundedBackground("#21262D", 18)
                isClickable = true
                isFocusable = true
                contentDescription = description ?: label
                addView(TextView(this@ChopperSidebarService).apply {
                    text = label
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                })
                if (!description.isNullOrBlank()) {
                    addView(TextView(this@ChopperSidebarService).apply {
                        text = description
                        textSize = 12f
                        setTextColor(Color.parseColor("#8B949E"))
                        setPadding(0, dp(3), 0, 0)
                    })
                }
                setOnClickListener { action() }
            }
        }

        fun addMenuView(view: View) {
            menuContent.addView(
                view,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(7) }
            )
        }

        var menuPage = "conversation"
        lateinit var renderMenu: () -> Unit
        lateinit var renderSettings: () -> Unit
        lateinit var renderVoiceSettings: () -> Unit
        lateinit var renderProjects: () -> Unit
        lateinit var renderProjectChats: (String) -> Unit

        fun showContainedPage() {
            chatScroll.visibility = View.GONE
            inputRow.visibility = View.GONE
            menuScroll.visibility = View.VISIBLE
            menuButton.text = "‹"
            hideKeyboard(messageInput)
        }

        renderVoiceSettings = {
            menuPage = "voice_settings"
            showContainedPage()
            menuContent.removeAllViews()
            addMenuView(addMenuRow("‹ Back to Settings") { renderSettings() })
            menuContent.addView(TextView(this).apply {
                text = "Voice changer"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(4), dp(4), dp(10))
            })

            val voicePreferences = getSharedPreferences(
                "chopper_voice_settings",
                Context.MODE_PRIVATE
            )
            val selectedPreset = voicePreferences.getString(
                "voice_preset",
                "Natural"
            ) ?: "Natural"
            val presets = listOf(
                Triple("Natural", 0.96f, 1.0f),
                Triple("Male AI 01 — Synth Fast", 1.12f, 0.75f),
                Triple("Male AI 02 — Assistant Energy", 1.09f, 0.82f),
                Triple("Female – Natural", 0.98f, 1.12f),
                Triple("Quick", 1.18f, 1.0f)
            )
            presets.forEach { preset ->
                val selected = selectedPreset == preset.first
                addMenuView(addMenuRow(
                    if (selected) "${preset.first}  ✓" else preset.first,
                    if (selected) "Currently selected" else "Use this Chopper voice"
                ) {
                    voicePreferences.edit()
                        .putString("voice_preset", preset.first)
                        .putFloat("speech_rate", preset.second)
                        .putFloat("speech_pitch", preset.third)
                        .remove("system_voice_name")
                        .apply()
                    applySavedSidebarVoiceSettings()
                    Toast.makeText(
                        this,
                        "${preset.first} voice selected",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderVoiceSettings()
                })
            }
        }

        renderSettings = {
            menuPage = "settings"
            showContainedPage()
            menuContent.removeAllViews()
            addMenuView(addMenuRow("‹ Back to Menu") { renderMenu() })
            menuContent.addView(TextView(this).apply {
                text = "Settings"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(4), dp(4), dp(10))
            })

            val voiceEnabled = isSidebarSpeechEnabled()
            addMenuView(addMenuRow(
                if (voiceEnabled) "🔊 Spoken replies: ON" else "🔇 Spoken replies: OFF",
                "Read Chopper replies aloud in both views"
            ) {
                setSidebarSpeechEnabled(!voiceEnabled)
                renderSettings()
            })

            val wakeEnabled = isHelloChopperEnabledInSidebar()
            addMenuView(addMenuRow(
                if (wakeEnabled) "🎙 Hello Chopper: ON" else "🎙 Hello Chopper: OFF",
                "Listen for the Chopper wake phrase"
            ) {
                setHelloChopperEnabledFromSidebar(!wakeEnabled)
                renderSettings()
            })

            val voiceName = getSharedPreferences(
                "chopper_voice_settings",
                Context.MODE_PRIVATE
            ).getString("voice_preset", "Natural") ?: "Natural"
            addMenuView(addMenuRow(
                "Voice changer",
                "Current voice: $voiceName"
            ) { renderVoiceSettings() })

            addMenuView(addMenuRow(
                "Disable Chopper Sidebar",
                "Remove the floating handle until it is enabled in full Chopper"
            ) {
                getSharedPreferences(
                    "chopper_sidebar_settings",
                    Context.MODE_PRIVATE
                ).edit().putBoolean("sidebar_enabled", false).apply()
                stopSelf()
            })
        }

        renderProjectChats = { project ->
            menuPage = "project_chats"
            showContainedPage()
            menuContent.removeAllViews()
            addMenuView(addMenuRow("‹ Back to Projects") { renderProjects() })
            menuContent.addView(TextView(this).apply {
                text = "🗂 $project"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(4), dp(4), dp(10))
            })

            if (currentConversationId != 0L) {
                val alreadyInProject = getSidebarConversationProject(
                    currentConversationId
                ) == project
                addMenuView(addMenuRow(
                    if (alreadyInProject) "✓ Current chat is in this project"
                    else "＋ Add current chat to this project",
                    if (alreadyInProject) "Conversation already added"
                    else "Keep this conversation inside $project"
                ) {
                    setSidebarConversationProject(currentConversationId, project)
                    Toast.makeText(
                        this,
                        "Current chat added to $project",
                        Toast.LENGTH_SHORT
                    ).show()
                    renderProjectChats(project)
                })
            }

            val loading = TextView(this).apply {
                text = "Loading project chats…"
                textSize = 13f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            menuContent.addView(loading)
            Thread {
                val entries = loadSidebarConversationEntries().filter {
                    getSidebarConversationProject(it.conversationId) == project
                }
                mainHandler.post {
                    if (
                        sidebarView !== root ||
                        menuPage != "project_chats"
                    ) return@post
                    menuContent.removeView(loading)
                    if (entries.isEmpty()) {
                        menuContent.addView(TextView(this).apply {
                            text = "No chats in this project yet"
                            textSize = 13f
                            setTextColor(Color.parseColor("#8B949E"))
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                        })
                    } else {
                        entries.forEach { entry ->
                            addMenuView(addMenuRow(
                                entry.title,
                                entry.preview.ifBlank { "Open conversation" }
                            ) {
                                Thread {
                                    val messages = loadSidebarConversation(
                                        entry.conversationId
                                    )
                                    mainHandler.post {
                                        if (sidebarView !== root) return@post
                                        conversationGeneration += 1
                                        pendingReplyCount = 0
                                        currentConversationId = entry.conversationId
                                        sidebarMessages.clear()
                                        sidebarMessages.addAll(messages)
                                        saveSidebarHistory()
                                        renderConversation()
                                        showConversationPage()
                                    }
                                }.start()
                            })
                        }
                    }
                }
            }.start()
        }

        renderProjects = {
            menuPage = "projects"
            showContainedPage()
            menuContent.removeAllViews()
            addMenuView(addMenuRow("‹ Back to Menu") { renderMenu() })
            menuContent.addView(TextView(this).apply {
                text = "Projects"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(4), dp(4), dp(10))
            })

            val projectNameInput = EditText(this).apply {
                hint = "New project name"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.parseColor("#8B949E"))
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = roundedBackground("#21262D", 18)
                maxLines = 1
            }
            addMenuView(projectNameInput)
            addMenuView(actionButton("Create Project", "#6F52B5").apply {
                setOnClickListener {
                    val name = projectNameInput.text.toString().trim().take(50)
                    if (name.isBlank()) {
                        Toast.makeText(
                            this@ChopperSidebarService,
                            "Enter a project name",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        addSidebarProject(name)
                        hideKeyboard(projectNameInput)
                        renderProjects()
                    }
                }
            })

            val projects = getSidebarProjects().sorted()
            if (projects.isEmpty()) {
                menuContent.addView(TextView(this).apply {
                    text = "No projects yet"
                    textSize = 13f
                    setTextColor(Color.parseColor("#8B949E"))
                    setPadding(dp(8), dp(9), dp(8), dp(8))
                })
            } else {
                projects.forEach { project ->
                    addMenuView(addMenuRow(
                        "🗂 $project",
                        "View conversations in this project"
                    ) { renderProjectChats(project) })
                }
            }
        }

        renderMenu = {
            menuPage = "menu"
            showContainedPage()
            menuContent.removeAllViews()
            menuContent.addView(TextView(this).apply {
                text = "Chopper menu"
                textSize = 19f
                setTextColor(Color.WHITE)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(2), dp(4), dp(10))
            })

            addMenuView(addMenuRow("＋ New Chat") {
                Thread {
                    val conversationId = createSidebarConversation()
                    mainHandler.post {
                        if (sidebarView !== root) return@post
                        conversationGeneration += 1
                        pendingReplyCount = 0
                        currentConversationId = conversationId
                        sidebarMessages.clear()
                        saveSidebarHistory()
                        renderConversation()
                        showConversationPage()
                    }
                }.start()
            })
            addMenuView(addMenuRow(
                "🗂 Projects",
                "Open projects inside the sidebar"
            ) {
                renderProjects()
            })
            addMenuView(addMenuRow(
                "⚙ Settings",
                "Open settings inside the sidebar"
            ) {
                renderSettings()
            })

            val voiceEnabled = isSidebarSpeechEnabled()
            addMenuView(addMenuRow(
                if (voiceEnabled) "🔊 Voice replies: ON" else "🔇 Voice replies: OFF",
                "Use the same voice setting in the sidebar and full Chopper"
            ) {
                setSidebarSpeechEnabled(!voiceEnabled)
                renderMenu()
            })

            menuContent.addView(TextView(this).apply {
                text = "Conversation history"
                textSize = 16f
                setTextColor(Color.parseColor("#A78BFA"))
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            })

            val loadingHistory = TextView(this).apply {
                text = "Loading chats…"
                textSize = 13f
                setTextColor(Color.parseColor("#8B949E"))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            menuContent.addView(loadingHistory)

            Thread {
                val entries = loadSidebarConversationEntries()
                mainHandler.post {
                    if (
                        sidebarView !== root ||
                        menuScroll.visibility != View.VISIBLE ||
                        menuPage != "menu"
                    ) {
                        return@post
                    }
                    menuContent.removeView(loadingHistory)
                    if (entries.isEmpty()) {
                        menuContent.addView(TextView(this).apply {
                            text = "No saved conversations yet"
                            textSize = 13f
                            setTextColor(Color.parseColor("#8B949E"))
                            setPadding(dp(8), dp(8), dp(8), dp(8))
                        })
                    } else {
                        entries.forEach { entry ->
                            addMenuView(addMenuRow(
                                entry.title,
                                entry.preview.ifBlank { "Open conversation" }
                            ) {
                                Thread {
                                    val messages = loadSidebarConversation(
                                        entry.conversationId
                                    )
                                    mainHandler.post {
                                        if (sidebarView !== root) return@post
                                        conversationGeneration += 1
                                        pendingReplyCount = 0
                                        currentConversationId = entry.conversationId
                                        sidebarMessages.clear()
                                        sidebarMessages.addAll(messages)
                                        saveSidebarHistory()
                                        renderConversation()
                                        showConversationPage()
                                    }
                                }.start()
                            })
                        }
                    }
                }
            }.start()
        }

        menuButton.setOnClickListener {
            if (menuScroll.visibility == View.VISIBLE) {
                if (menuPage == "menu") {
                    showConversationPage()
                } else {
                    renderMenu()
                }
            } else {
                renderMenu()
            }
        }

        val bottomResizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bottomLeftHandle = SidebarResizeHandleView(this, true).apply {
            contentDescription = "Resize from bottom-left curved handle"
        }
        val bottomRightHandle = SidebarResizeHandleView(this, false).apply {
            contentDescription = "Resize from bottom-right curved handle"
        }
        bottomResizeRow.addView(bottomLeftHandle, LinearLayout.LayoutParams(dp(40), dp(28)))
        bottomResizeRow.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        bottomResizeRow.addView(bottomRightHandle, LinearLayout.LayoutParams(dp(40), dp(28)))
        root.addView(bottomResizeRow)
        sendButton.setOnClickListener { button ->
            val message = messageInput.text.toString().trim()
            if (message.isEmpty() || pendingReplyCount > 0) {
                return@setOnClickListener
            }
            val requestHistory = sidebarMessages.takeLast(10).toList()
            val requestGeneration = conversationGeneration
            val userMessage = SidebarMessage(true, message)
            sidebarMessages.add(userMessage)
            pendingReplyCount += 1
            trimAndSaveSidebarHistory()
            messageInput.text.clear()
            button.isEnabled = false
            renderConversation()
            Thread {
                val conversationId = ensureSidebarConversation()
                persistSidebarMessage(conversationId, userMessage)
                val assistantMessage = try {
                    if (isImageGenerationRequest(message)) {
                        val prompt = extractImageGenerationPrompt(message)
                        val generated = generateImageWithCloud(prompt)
                        SidebarMessage(
                            false,
                            generatedImageMarker(
                                generated.file,
                                prompt,
                                generated.notice
                            )
                        )
                    } else {
                        SidebarMessage(
                            false,
                            chatWithCloud(message, requestHistory)
                        )
                    }
                } catch (error: Exception) {
                    SidebarMessage(
                        false,
                        "Chopper error: ${error.message ?: "Unable to connect."}"
                    )
                }
                if (requestGeneration == conversationGeneration) {
                    persistSidebarMessage(conversationId, assistantMessage)
                }
                mainHandler.post {
                    pendingReplyCount = (pendingReplyCount - 1).coerceAtLeast(0)
                    updateActiveInputBusyState?.invoke(pendingReplyCount > 0)
                    if (requestGeneration != conversationGeneration) {
                        return@post
                    }
                    sidebarMessages.add(assistantMessage)
                    trimAndSaveSidebarHistory()
                    refreshActiveConversationUi?.invoke()
                    if (sidebarView != null) {
                        if (assistantMessage.text.startsWith(
                                MainActivity.GENERATED_IMAGE_MARKER
                            )
                        ) {
                            speakSidebarReply("Your image is ready.")
                        } else {
                            speakSidebarReply(assistantMessage.text)
                        }
                    }
                }
            }.start()
        }

        var touchX = 0f
        var touchY = 0f
        var startX = 0
        var startY = 0
        title.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchX = event.rawX; touchY = event.rawY
                    startX = userX; startY = userY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    userX = (startX + (event.rawX - touchX).toInt())
                        .coerceIn(0, (screenWidth - userWidth).coerceAtLeast(0))
                    userY = (startY + (event.rawY - touchY).toInt())
                        .coerceIn(0, (screenHeight - userHeight).coerceAtLeast(0))
                    params.x = userX; params.y = userY
                    params.width = userWidth; params.height = userHeight
                    keyboardAdjusted = false
                    updatePanelLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePanelBounds(preferences, userX, userY, userWidth, userHeight)
                    true
                }
                else -> false
            }
        }

        fun attachBottomResize(handle: View, resizeFromLeft: Boolean) {
            var downX = 0f
            var downY = 0f
            var originalX = 0
            var originalWidth = 0
            var originalHeight = 0
            var originalRight = 0

            handle.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        originalX = userX
                        originalWidth = userWidth
                        originalHeight = userHeight
                        originalRight = userX + userWidth
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val horizontalChange = (event.rawX - downX).toInt()
                        userWidth = if (resizeFromLeft) {
                            (originalWidth - horizontalChange).coerceIn(minWidth, maxWidth)
                        } else {
                            (originalWidth + horizontalChange).coerceIn(minWidth, maxWidth)
                        }
                        userX = if (resizeFromLeft) {
                            (originalRight - userWidth).coerceIn(
                                0,
                                (screenWidth - userWidth).coerceAtLeast(0)
                            )
                        } else {
                            originalX.coerceIn(
                                0,
                                (screenWidth - userWidth).coerceAtLeast(0)
                            )
                        }
                        userHeight = (
                                originalHeight + (event.rawY - downY).toInt()
                                ).coerceIn(minHeight, maxHeight)
                        userY = userY.coerceIn(
                            0,
                            (screenHeight - userHeight).coerceAtLeast(0)
                        )
                        params.x = userX
                        params.y = userY
                        params.width = userWidth
                        params.height = userHeight
                        keyboardAdjusted = false
                        updatePanelLayout(root, params)
                        true
                    }

                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        savePanelBounds(
                            preferences,
                            userX,
                            userY,
                            userWidth,
                            userHeight
                        )
                        true
                    }

                    else -> false
                }
            }
        }

        attachBottomResize(bottomLeftHandle, true)
        attachBottomResize(bottomRightHandle, false)

        val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            if (sidebarView !== root) return@OnGlobalLayoutListener
            val visible = Rect()
            root.getWindowVisibleDisplayFrame(visible)
            val keyboardVisible = screenHeight - visible.bottom > screenHeight * 0.15f
            if (keyboardVisible && messageInput.hasFocus()) {
                val availableBottom = visible.bottom - dp(8)
                val safeHeight = userHeight.coerceAtMost(
                    (availableBottom - dp(8)).coerceAtLeast(dp(160))
                )
                val safeX = userX.coerceIn(
                    0,
                    (screenWidth - userWidth).coerceAtLeast(0)
                )
                val safeY = userY.coerceAtMost(
                    (availableBottom - safeHeight).coerceAtLeast(0)
                )
                val layoutChanged =
                    params.width != userWidth ||
                            params.height != safeHeight ||
                            params.x != safeX ||
                            params.y != safeY
                params.width = userWidth
                params.height = safeHeight
                params.x = safeX
                params.y = safeY
                keyboardAdjusted = true
                if (layoutChanged) updatePanelLayout(root, params)
            } else if (keyboardAdjusted) {
                params.width = userWidth; params.height = userHeight
                params.x = userX
                params.y = userY.coerceIn(0, (screenHeight - userHeight).coerceAtLeast(0))
                keyboardAdjusted = false
                updatePanelLayout(root, params)
            }
        }
        try {
            windowManager.addView(root, params)
            sidebarView = root
            refreshActiveConversationUi = {
                if (sidebarView === root) {
                    renderConversation()
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
                }
            }
            updateActiveInputBusyState = { busy ->
                if (sidebarView === root) {
                    sendButton.isEnabled = !busy
                }
            }
            sendButton.isEnabled = pendingReplyCount == 0
            panelLayoutListener = layoutListener
            panelListenerRoot = root
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }

            val conversationId = currentConversationId
            if (conversationId != 0L) {
                val stateSizeAtLoadStart = sidebarMessages.size
                Thread {
                    val restored = loadSidebarConversation(conversationId)
                    mainHandler.post {
                        if (
                            sidebarView === root &&
                            currentConversationId == conversationId &&
                            sidebarMessages.size == stateSizeAtLoadStart &&
                            restored != sidebarMessages
                        ) {
                            sidebarMessages.clear()
                            sidebarMessages.addAll(restored)
                            saveSidebarHistory()
                            renderConversation()
                        }
                    }
                }.start()
            }
        } catch (error: Exception) {
            Toast.makeText(this, "Unable to open Chopper sidebar: ${error.message}", Toast.LENGTH_LONG).show()
            refreshActiveConversationUi = null
            updateActiveInputBusyState = null
            sidebarView = null
            showFloatingHandle()
        }
    }


    private fun hideSidebarPanel() {
        stopSidebarSpeech()
        hideKeyboard(sidebarView)
        refreshActiveConversationUi = null
        updateActiveInputBusyState = null
        removePanelLayoutListener()
        removeViewSafely(sidebarView)
        sidebarView = null
        showFloatingHandle()
    }


    private fun startNewSidebarConversation() {
        conversationGeneration += 1
        pendingReplyCount = 0
        val conversationToDelete = synchronized(conversationLock) {
            val activeConversation = currentConversationId
            currentConversationId = 0L
            activeConversation
        }
        sidebarMessages.clear()
        resetSidebarConversationAndBounds()
        hideSidebarPanel()

        if (conversationToDelete != 0L) {
            Thread {
                chopperDao.deleteMessages(conversationToDelete)
                chopperDao.deleteConversation(conversationToDelete)
            }.start()
        }
    }


    private fun openFullChopper(section: String? = null) {
        Thread {
            val conversationId = ensureSidebarConversation()
            mainHandler.post {
                val intent = Intent(
                    this,
                    MainActivity::class.java
                ).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                    putExtra(
                        MainActivity.EXTRA_SIDEBAR_CONVERSATION_ID,
                        conversationId
                    )
                    if (!section.isNullOrBlank()) {
                        putExtra(MainActivity.EXTRA_OPEN_SECTION, section)
                    }
                }

                startActivity(intent)
                hideSidebarPanel()
            }
        }.start()
    }


    private fun chatWithCloud(
        message: String,
        history: List<SidebarMessage>
    ): String {
        val connection = URL(
            "https://chopper-du01.onrender.com/chat"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            connection.connectTimeout = 20000
            connection.readTimeout = 90000
            connection.doOutput = true

            val historyJson = JSONArray().apply {
                history.forEach { item ->
                    val generatedImage = parseGeneratedImageMarker(item.text)
                    put(JSONObject().apply {
                        put("role", if (item.isUser) "user" else "assistant")
                        put(
                            "content",
                            generatedImage?.let {
                                "[Chopper generated an image: ${it.prompt}]"
                            } ?: item.text
                        )
                    })
                }
            }

            val body = JSONObject().apply {
                put("message", message)
                put("history", historyJson)
                put("mode", "Auto")
            }

            connection.outputStream.use { output ->
                output.write(
                    body.toString().toByteArray(Charsets.UTF_8)
                )
                output.flush()
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseText = stream
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (responseText.isBlank()) {
                throw Exception("The server returned an empty response.")
            }

            val responseJson = JSONObject(responseText)
            if (responseCode !in 200..299) {
                throw Exception(
                    responseJson.optString(
                        "error",
                        "Server returned $responseCode"
                    )
                )
            }
            if (!responseJson.optBoolean("success", false)) {
                throw Exception(
                    responseJson.optString(
                        "error",
                        "Cloud chat failed."
                    )
                )
            }

            return responseJson.optString(
                "result",
                ""
            ).trim().ifEmpty {
                throw Exception("Cloud AI returned an empty response.")
            }
        } finally {
            connection.disconnect()
        }
    }


    private fun isImageGenerationRequest(message: String): Boolean {
        val text = message
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
            .trim()

        if (text.isBlank()) return false

        val generationAction = Regex(
            "\\b(generate|create|make|draw|paint|render|design|produce|illustrate)\\b"
        )
        val imageNoun = Regex(
            "\\b(image|picture|photo|artwork|illustration|wallpaper|poster)\\b"
        )
        val actionMatch = generationAction.find(text)
        val nounMatch = imageNoun.find(text)

        if (
            actionMatch != null &&
            nounMatch != null &&
            actionMatch.range.first < nounMatch.range.first
        ) {
            return true
        }

        return text.startsWith("image of ") ||
                text.startsWith("picture of ") ||
                text.startsWith("photo of ")
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


    private fun generateImageWithCloud(prompt: String): GeneratedImageResult {
        val connection = URL(
            "https://chopper-du01.onrender.com/generate-image"
        ).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty(
                "Content-Type",
                "application/json; charset=UTF-8"
            )
            connection.connectTimeout = 30_000
            connection.readTimeout = 240_000
            connection.doOutput = true

            val requestBody = JSONObject()
                .put("prompt", prompt)
                .toString()
            connection.outputStream.use { output ->
                output.write(requestBody.toByteArray(Charsets.UTF_8))
                output.flush()
            }

            val responseCode = connection.responseCode
            val stream = if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }
            val responseText = stream?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            if (responseText.isBlank()) {
                throw Exception("The image server returned an empty response.")
            }

            val responseJson = JSONObject(responseText)
            if (
                responseCode !in 200..299 ||
                !responseJson.optBoolean("success", false)
            ) {
                throw Exception(
                    responseJson.optString(
                        "error",
                        "Image generation failed ($responseCode)"
                    )
                )
            }

            val encodedImage = responseJson.optString("image_base64")
            if (encodedImage.isBlank()) {
                throw Exception("The image server returned no image data.")
            }
            val imageBytes = Base64.decode(encodedImage, Base64.DEFAULT)
            if (
                BitmapFactory.decodeByteArray(
                    imageBytes,
                    0,
                    imageBytes.size
                ) == null
            ) {
                throw Exception("The generated image could not be decoded.")
            }

            val directory = File(
                getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                "chopper_generated"
            )
            if (!directory.exists() && !directory.mkdirs()) {
                throw Exception("Could not create the generated-image folder.")
            }
            val generatedFile = File(
                directory,
                "chopper_${System.currentTimeMillis()}.png"
            ).also { file ->
                FileOutputStream(file).use { it.write(imageBytes) }
            }
            return GeneratedImageResult(
                file = generatedFile,
                notice = responseJson.optString("notice", "").trim()
            )
        } finally {
            connection.disconnect()
        }
    }


    private fun generatedImageMarker(
        file: File,
        prompt: String,
        notice: String
    ): String {
        val encodedPrompt = Base64.encodeToString(
            prompt.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        val encodedNotice = Base64.encodeToString(
            notice.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP or Base64.URL_SAFE
        )
        return "${MainActivity.GENERATED_IMAGE_MARKER}${file.absolutePath}|" +
                "$encodedPrompt|$encodedNotice"
    }


    private fun parseGeneratedImageMarker(text: String): StoredGeneratedImage? {
        if (!text.startsWith(MainActivity.GENERATED_IMAGE_MARKER)) return null
        val parts = text.removePrefix(MainActivity.GENERATED_IMAGE_MARKER)
            .split('|', limit = 3)
        if (parts.size < 2) return null
        val prompt = try {
            String(
                Base64.decode(parts[1], Base64.URL_SAFE),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            "Generated by Chopper"
        }
        val notice = if (parts.size >= 3) {
            try {
                String(
                    Base64.decode(parts[2], Base64.URL_SAFE),
                    Charsets.UTF_8
                )
            } catch (_: Exception) {
                ""
            }
        } else {
            ""
        }
        return StoredGeneratedImage(File(parts[0]), prompt, notice)
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
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    values
                ) ?: throw Exception("Gallery could not create the image.")
                contentResolver.openOutputStream(uri)?.use { output ->
                    file.inputStream().use { input -> input.copyTo(output) }
                } ?: throw Exception("Gallery could not save the image.")
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                Toast.makeText(
                    this,
                    "Saved to Pictures/Chopper",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                shareGeneratedImage(file)
            }
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Save failed: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun shareGeneratedImage(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/png"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                },
                "Share Chopper image"
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Share failed: ${error.message}",
                Toast.LENGTH_LONG
            ).show()
        }
    }


    private fun loadSidebarHistory() {
        sidebarMessages.clear()
        val preferences = getSharedPreferences(
            "chopper_sidebar_settings",
            Context.MODE_PRIVATE
        )
        currentConversationId = preferences.getLong(
            "sidebar_conversation_id",
            0L
        )
        val saved = preferences.getString("sidebar_history", null) ?: return

        try {
            val array = JSONArray(saved)
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val text = item.optString("text").trim()
                if (text.isNotEmpty()) {
                    sidebarMessages.add(
                        SidebarMessage(item.optBoolean("is_user"), text)
                    )
                }
            }
        } catch (_: Exception) {
            sidebarMessages.clear()
        }
    }


    private fun synchronizeSidebarHistoryWithDatabase() {
        val generationAtStart = conversationGeneration
        val savedMessages = sidebarMessages.toList()

        Thread {
            var conversationId = currentConversationId
            val existingConversationIds = chopperDao
                .getAllConversations()
                .map { it.id }
                .toSet()

            val synchronizationCancelled = synchronized(conversationLock) {
                if (generationAtStart != conversationGeneration) {
                    true
                } else {
                    if (conversationId !in existingConversationIds) {
                        conversationId = createSidebarConversation()
                        currentConversationId = conversationId
                    }
                    false
                }
            }

            if (!synchronizationCancelled) {
                var databaseMessages = chopperDao.getMessages(conversationId)

                if (databaseMessages.isEmpty() && savedMessages.isNotEmpty()) {
                    savedMessages.forEach { message ->
                        persistSidebarMessage(conversationId, message)
                    }
                    databaseMessages = chopperDao.getMessages(conversationId)
                }

                val restored = databaseMessages.map { message ->
                    SidebarMessage(message.isUser, message.text)
                }

                mainHandler.post {
                    if (generationAtStart != conversationGeneration) return@post
                    currentConversationId = conversationId
                    if (restored.isNotEmpty()) {
                        sidebarMessages.clear()
                        sidebarMessages.addAll(restored)
                    }
                    saveSidebarHistory()
                }
            }
        }.start()
    }


    private fun ensureSidebarConversation(): Long {
        synchronized(conversationLock) {
            if (currentConversationId != 0L) {
                return currentConversationId
            }

            val conversationId = createSidebarConversation()
            currentConversationId = conversationId
            getSharedPreferences(
                "chopper_sidebar_settings",
                Context.MODE_PRIVATE
            ).edit()
                .putLong("sidebar_conversation_id", conversationId)
                .apply()
            return conversationId
        }
    }


    private fun createSidebarConversation(): Long {
        return chopperDao.insertConversation(
            Conversation(title = "New Chat")
        )
    }


    private fun persistSidebarMessage(
        conversationId: Long,
        message: SidebarMessage
    ) {
        if (conversationId == 0L || message.text.isBlank()) return

        if (message.isUser) {
            val existingMessages = chopperDao.getMessages(conversationId)
            if (existingMessages.none { it.isUser }) {
                chopperDao.updateConversation(
                    conversationId,
                    message.text.replace("\n", " ").trim().take(45)
                )
            }
        }

        chopperDao.insertMessage(
            ChatMessage(
                conversationId = conversationId,
                text = message.text,
                isUser = message.isUser
            )
        )
    }


    private fun loadSidebarConversation(
        conversationId: Long
    ): List<SidebarMessage> {
        return chopperDao.getMessages(conversationId).map { message ->
            SidebarMessage(message.isUser, message.text)
        }
    }


    private fun loadSidebarConversationEntries(): List<SidebarHistoryEntry> {
        return chopperDao.getAllConversations().mapNotNull { conversation ->
            val messages = chopperDao.getMessages(conversation.id)
            val firstUserMessage = messages.firstOrNull { it.isUser }
                ?: return@mapNotNull null
            val title = conversation.title
                .takeUnless { it.equals("New Chat", ignoreCase = true) }
                ?: firstUserMessage.text.replace("\n", " ").trim().take(45)
            val lastText = messages.lastOrNull()?.text.orEmpty()
            val preview = parseGeneratedImageMarker(lastText)
                ?.let { "Generated image: ${it.prompt}" }
                ?: lastText.replace("\n", " ")
                    .trim()
                    .take(72)

            SidebarHistoryEntry(
                conversationId = conversation.id,
                title = title,
                preview = preview
            )
        }
    }


    private fun trimAndSaveSidebarHistory() {
        while (sidebarMessages.size > 40) sidebarMessages.removeAt(0)
        saveSidebarHistory()
    }


    private fun saveSidebarHistory() {
        val array = JSONArray()
        sidebarMessages.forEach { message ->
            array.put(JSONObject().apply {
                put("is_user", message.isUser)
                put("text", message.text)
            })
        }
        getSharedPreferences(
            "chopper_sidebar_settings",
            Context.MODE_PRIVATE
        ).edit()
            .putString("sidebar_history", array.toString())
            .putLong("sidebar_conversation_id", currentConversationId)
            .apply()
    }


    private fun resetSidebarConversationAndBounds() {
        getSharedPreferences(
            "chopper_sidebar_settings",
            Context.MODE_PRIVATE
        ).edit()
            .remove("sidebar_history")
            .remove("sidebar_conversation_id")
            .remove("sidebar_x")
            .remove("sidebar_y")
            .remove("sidebar_width")
            .remove("sidebar_height")
            .apply()
    }


    private fun getSidebarProjects(): MutableSet<String> {
        return getSharedPreferences(
            "chopper_chat_manager",
            Context.MODE_PRIVATE
        ).getStringSet("projects", emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
    }


    private fun addSidebarProject(name: String) {
        val projects = getSidebarProjects()
        projects.add(name)
        getSharedPreferences(
            "chopper_chat_manager",
            Context.MODE_PRIVATE
        ).edit().putStringSet("projects", projects).apply()
    }


    private fun getSidebarConversationProject(conversationId: Long): String? {
        if (conversationId == 0L) return null
        return getSharedPreferences(
            "chopper_chat_manager",
            Context.MODE_PRIVATE
        ).getString("project_$conversationId", null)
    }


    private fun setSidebarConversationProject(
        conversationId: Long,
        project: String?
    ) {
        if (conversationId == 0L) return
        val editor = getSharedPreferences(
            "chopper_chat_manager",
            Context.MODE_PRIVATE
        ).edit()
        if (project.isNullOrBlank()) {
            editor.remove("project_$conversationId")
        } else {
            editor.putString("project_$conversationId", project)
        }
        editor.apply()
    }


    private fun isHelloChopperEnabledInSidebar(): Boolean {
        return getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).getBoolean("hello_chopper_enabled", true)
    }


    private fun setHelloChopperEnabledFromSidebar(enabled: Boolean) {
        getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).edit().putBoolean("hello_chopper_enabled", enabled).apply()

        try {
            if (enabled) {
                val intent = Intent(this, WakeWordService::class.java).apply {
                    action = WakeWordService.ACTION_START
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }
            } else {
                stopService(Intent(this, WakeWordService::class.java))
            }
        } catch (error: Exception) {
            getSharedPreferences(
                "chopper_voice_settings",
                Context.MODE_PRIVATE
            ).edit().putBoolean("hello_chopper_enabled", false).apply()
            Toast.makeText(
                this,
                "Open full Chopper once to allow microphone access.",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(
            this,
            if (enabled) "Hello Chopper is ON" else "Hello Chopper is OFF",
            Toast.LENGTH_SHORT
        ).show()
    }


    private fun initializeTextToSpeech() {
        textToSpeech = TextToSpeech(this) { status ->
            textToSpeechReady = status == TextToSpeech.SUCCESS
            if (textToSpeechReady) {
                applySavedSidebarVoiceSettings()
                pendingSpeech?.let { message ->
                    pendingSpeech = null
                    speakSidebarReply(message)
                }
            } else {
                pendingSpeech = null
            }
        }
    }


    private fun isSidebarSpeechEnabled(): Boolean {
        return getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).getBoolean("spoken_replies_enabled", true)
    }


    private fun setSidebarSpeechEnabled(enabled: Boolean) {
        getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        ).edit()
            .putBoolean("spoken_replies_enabled", enabled)
            .apply()

        if (enabled) {
            applySavedSidebarVoiceSettings()
        } else {
            stopSidebarSpeech()
        }
    }


    private fun applySavedSidebarVoiceSettings() {
        val engine = textToSpeech ?: return
        val preferences = getSharedPreferences(
            "chopper_voice_settings",
            Context.MODE_PRIVATE
        )

        engine.setSpeechRate(preferences.getFloat("speech_rate", 0.96f))
        engine.setPitch(preferences.getFloat("speech_pitch", 1.0f))

        val languageTag = preferences.getString(
            "speech_language_tag",
            null
        )
        val locale = languageTag
            ?.takeIf { it.isNotBlank() }
            ?.let { Locale.forLanguageTag(it) }
            ?: Locale.getDefault()
        val languageResult = engine.setLanguage(locale)
        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            engine.setLanguage(Locale.US)
        }

        val savedVoiceName = preferences.getString("system_voice_name", null)
        if (!savedVoiceName.isNullOrBlank()) {
            engine.voices
                ?.firstOrNull { it.name == savedVoiceName }
                ?.let { engine.voice = it }
        }
    }


    private fun speakSidebarReply(message: String) {
        if (!isSidebarSpeechEnabled() || message.isBlank()) return

        if (!textToSpeechReady) {
            pendingSpeech = message
            return
        }

        val engine = textToSpeech ?: return
        applySavedSidebarVoiceSettings()

        if (message.any { it.code in 0x0B80..0x0BFF }) {
            val tamilVoice = engine.voices
                ?.filter { it.locale.language.equals("ta", ignoreCase = true) }
                ?.sortedWith(
                    compareBy<Voice> { it.isNetworkConnectionRequired }
                        .thenByDescending { it.quality }
                )
                ?.firstOrNull()
            val tamilResult = engine.setLanguage(Locale("ta", "IN"))
            if (
                tamilResult != TextToSpeech.LANG_MISSING_DATA &&
                tamilResult != TextToSpeech.LANG_NOT_SUPPORTED &&
                tamilVoice != null
            ) {
                engine.voice = tamilVoice
            }
        }

        val spokenText = message
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_#`]"), "")
            .trim()
        if (spokenText.isBlank()) return

        engine.speak(
            spokenText,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "chopper_sidebar_${System.currentTimeMillis()}"
        )
    }


    private fun stopSidebarSpeech() {
        pendingSpeech = null
        textToSpeech?.stop()
    }


    private fun savePanelBounds(
        preferences: android.content.SharedPreferences,
        x: Int,
        y: Int,
        width: Int,
        height: Int
    ) {
        preferences.edit()
            .putInt("sidebar_x", x)
            .putInt("sidebar_y", y)
            .putInt("sidebar_width", width)
            .putInt("sidebar_height", height)
            .apply()
    }


    private fun updatePanelLayout(
        root: View,
        params: WindowManager.LayoutParams
    ) {
        try {
            windowManager.updateViewLayout(root, params)
        } catch (_: Exception) {
            // The overlay may close during a drag or keyboard transition.
        }
    }


    private fun removePanelLayoutListener() {
        val root = panelListenerRoot
        val listener = panelLayoutListener
        if (root != null && listener != null && root.viewTreeObserver.isAlive) {
            root.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        panelListenerRoot = null
        panelLayoutListener = null
    }


    private fun showKeyboard(view: View) {
        val inputMethodManager = getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager

        inputMethodManager.showSoftInput(
            view,
            InputMethodManager.SHOW_IMPLICIT
        )
    }


    private fun hideKeyboard(view: View?) {
        val token = view?.windowToken ?: return
        val inputMethodManager = getSystemService(
            Context.INPUT_METHOD_SERVICE
        ) as InputMethodManager

        inputMethodManager.hideSoftInputFromWindow(
            token,
            0
        )
    }


    private fun removeViewSafely(view: View?) {
        if (view == null) {
            return
        }

        try {
            windowManager.removeView(view)
        } catch (_: Exception) {
            // The view was already removed by Android.
        }
    }


    private fun compactHeaderButton(label: String): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 17f
            minWidth = 0
            minHeight = 0
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 0)
            background = roundedBackground("#30363D", 18)
            layoutParams = LinearLayout.LayoutParams(
                dp(36),
                dp(36)
            ).apply {
                marginStart = dp(4)
            }
        }
    }


    private fun actionButton(
        label: String,
        color: String = "#30363D"
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 13f
            minWidth = 0
            minHeight = 0
            setTextColor(Color.WHITE)
            setPadding(dp(4), 0, dp(4), 0)
            background = roundedBackground(color, 20)
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


    private fun cleanMarkdownForDisplay(response: String): String {
        return response
            .replace("\r\n", "\n")
            .replace("**", "")
            .replace("*", "")
            .replace("```", "")
            .replace("`", "")
            .replace(
                Regex("(?m)^\\s*#{1,6}\\s*"),
                ""
            )
            .replace(
                Regex("(?m)^\\s*[-=_]{3,}\\s*$"),
                ""
            )
            .replace("|", "")
            .lines()
            .joinToString("\n") { it.trimEnd() }
            .replace(
                Regex("\n{3,}"),
                "\n\n"
            )
            .trim()
    }


    private fun dp(value: Int): Int {
        return (
                value * resources.displayMetrics.density
                ).toInt()
    }
}
