package com.example.choppermobile.sidebar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.choppermobile.MainActivity
import com.example.choppermobile.R
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
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
    }

    private lateinit var windowManager: WindowManager
    private var handleView: View? = null
    private var sidebarView: View? = null
    private val sidebarMessages = mutableListOf<SidebarMessage>()
    private var conversationGeneration = 0
    private var panelLayoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null
    private var panelListenerRoot: View? = null

    private data class SidebarMessage(val isUser: Boolean, val text: String)


    override fun onCreate() {
        super.onCreate()

        windowManager = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        createNotificationChannel()
        loadSidebarHistory()
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
        var userWidth = preferences.getInt("sidebar_width", dp(310)).coerceIn(minWidth, maxWidth)
        var userHeight = preferences.getInt("sidebar_height", dp(480)).coerceIn(minHeight, maxHeight)
        var userX = preferences.getInt("sidebar_x", screenWidth - userWidth)
            .coerceIn(0, (screenWidth - userWidth).coerceAtLeast(0))
        var userY = preferences.getInt("sidebar_y", dp(48))
            .coerceIn(0, (screenHeight - userHeight).coerceAtLeast(0))

        val params = WindowManager.LayoutParams(
            userWidth, userHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
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
            setMinWidth(dp(82))
            contentDescription = "Drag to move Chopper"
        }
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

        fun addBubble(message: SidebarMessage): TextView {
            val bubble = TextView(this).apply {
                text = message.text
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(dp(12), dp(9), dp(12), dp(9))
                background = roundedBackground(if (message.isUser) "#3658C5" else "#21262D", 17)
            }
            chatContainer.addView(bubble, LinearLayout.LayoutParams(
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
        if (sidebarMessages.isEmpty()) {
            addBubble(SidebarMessage(false, "Ask Chopper anything."))
        } else sidebarMessages.forEach(::addBubble)

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

        val bottomResizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val bottomLeftHandle = resizeCorner("⌞", "Resize from bottom-left corner")
        val bottomRightHandle = resizeCorner("⌟", "Resize from bottom-right corner")
        bottomResizeRow.addView(bottomLeftHandle, LinearLayout.LayoutParams(dp(34), dp(22)))
        bottomResizeRow.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        bottomResizeRow.addView(bottomRightHandle, LinearLayout.LayoutParams(dp(34), dp(22)))
        root.addView(bottomResizeRow)
        sendButton.setOnClickListener { button ->
            val message = messageInput.text.toString().trim()
            if (message.isEmpty()) return@setOnClickListener
            val requestHistory = sidebarMessages.takeLast(10).toList()
            val requestGeneration = conversationGeneration
            sidebarMessages.add(SidebarMessage(true, message))
            trimAndSaveSidebarHistory()
            addBubble(SidebarMessage(true, message))
            messageInput.text.clear()
            val thinking = addBubble(SidebarMessage(false, "Chopper is thinking..."))
            button.isEnabled = false
            Thread {
                val reply = try {
                    chatWithCloud(message, requestHistory)
                } catch (error: Exception) {
                    "Chopper error: ${error.message ?: "Unable to connect."}"
                }
                root.post {
                    if (requestGeneration != conversationGeneration) {
                        return@post
                    }
                    thinking.text = reply
                    sidebarMessages.add(SidebarMessage(false, reply))
                    trimAndSaveSidebarHistory()
                    button.isEnabled = true
                    chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
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
            panelLayoutListener = layoutListener
            panelListenerRoot = root
            root.viewTreeObserver.addOnGlobalLayoutListener(layoutListener)
            chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
        } catch (error: Exception) {
            Toast.makeText(this, "Unable to open Chopper sidebar: ${error.message}", Toast.LENGTH_LONG).show()
            sidebarView = null
            showFloatingHandle()
        }
    }


    private fun hideSidebarPanel() {
        hideKeyboard(sidebarView)
        removePanelLayoutListener()
        removeViewSafely(sidebarView)
        sidebarView = null
        showFloatingHandle()
    }


    private fun startNewSidebarConversation() {
        conversationGeneration += 1
        sidebarMessages.clear()
        saveSidebarHistory()
        hideSidebarPanel()
    }


    private fun openFullChopper() {
        val intent = Intent(
            this,
            MainActivity::class.java
        ).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        }

        startActivity(intent)
        hideSidebarPanel()
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
                    put(JSONObject().apply {
                        put("role", if (item.isUser) "user" else "assistant")
                        put("content", item.text)
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


    private fun loadSidebarHistory() {
        sidebarMessages.clear()
        val saved = getSharedPreferences(
            "chopper_sidebar_settings",
            Context.MODE_PRIVATE
        ).getString("sidebar_history", null) ?: return

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
        ).edit().putString("sidebar_history", array.toString()).apply()
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


    private fun resizeCorner(
        symbol: String,
        description: String
    ): TextView {
        return TextView(this).apply {
            text = symbol
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = description
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


    private fun dp(value: Int): Int {
        return (
                value * resources.displayMetrics.density
                ).toInt()
    }
}
