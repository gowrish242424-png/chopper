package com.example.choppermobile

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream

class ChopperVoiceInteractionSession(
    context: Context
) : VoiceInteractionSession(context) {

    private lateinit var messageInput:
            EditText

    private lateinit var searchBar:
            LinearLayout

    private lateinit var screenshotCropView:
            ScreenshotCropView

    private lateinit var cropButton:
            TextView

    private var capturedScreenshot:
            Bitmap? = null

    private var selectedTextOnly = false

    override fun onCreate() {
        super.onCreate()

        window.window?.setSoftInputMode(
            WindowManager.LayoutParams
                .SOFT_INPUT_ADJUST_RESIZE
        )
    }

    override fun onCreateContentView(): View {

        val root =
            FrameLayout(context).apply {
                layoutParams =
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                setBackgroundColor(
                    Color.TRANSPARENT
                )
            }

        createScreenshotCropView(
            root
        )

        createCropButton(
            root
        )

        createSearchBar(
            root
        )

        /*
         * Added last so that it remains above
         * the screenshot selection view.
         */
        createBackButton(
            root
        )

        setupKeyboardPositioning(
            root
        )

        return root
    }

    private fun setupKeyboardPositioning(
        root: FrameLayout
    ) {
        ViewCompat.setOnApplyWindowInsetsListener(
            root
        ) { _, insets ->
            val keyboardVisible =
                insets.isVisible(
                    WindowInsetsCompat.Type.ime()
                )

            val keyboardInsets =
                insets.getInsets(
                    WindowInsetsCompat.Type.ime()
                )

            val parameters =
                searchBar.layoutParams as
                        FrameLayout.LayoutParams

            parameters.bottomMargin =
                if (keyboardVisible) {
                    keyboardInsets.bottom + dp(8)
                } else {
                    dp(30)
                }

            searchBar.translationY = 0f
            searchBar.layoutParams = parameters

            insets
        }

        ViewCompat.requestApplyInsets(
            root
        )
    }

    private fun createScreenshotCropView(
        root: FrameLayout
    ) {
        screenshotCropView =
            ScreenshotCropView(context).apply {
                visibility =
                    View.GONE

                onTextSelected = { selectedText ->
                    if (selectedText.isNotBlank()) {
                        selectedTextOnly = true
                        messageInput.setText(selectedText)
                        messageInput.setSelection(selectedText.length)
                        messageInput.hint = "Ask about selected text"
                        cropButton.visibility = View.GONE
                    }
                }

                onImageSelectionChanged = {
                    selectedTextOnly = false
                    messageInput.setText("")
                    messageInput.hint = "Ask about selected area"
                    cropButton.visibility = View.GONE
                }
            }

        val cropViewParameters =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ).apply {
                leftMargin = 0
                rightMargin = 0
                topMargin = 0
                bottomMargin = 0
            }

        root.addView(
            screenshotCropView,
            cropViewParameters
        )
    }

    private fun createCropButton(
        root: FrameLayout
    ) {
        cropButton =
            TextView(context).apply {
                text =
                    "Use selection"

                textSize =
                    15f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                setPadding(
                    dp(20),
                    dp(10),
                    dp(20),
                    dp(10)
                )

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.RECTANGLE

                        cornerRadius =
                            dp(22).toFloat()

                        setColor(
                            Color.rgb(
                                32,
                                130,
                                230
                            )
                        )
                    }

                elevation =
                    dp(8).toFloat()

                visibility =
                    View.GONE

                setOnClickListener {
                    confirmCropSelection()
                }
            }

        val buttonParameters =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity =
                    Gravity.BOTTOM or
                            Gravity.CENTER_HORIZONTAL

                bottomMargin =
                    dp(147)
            }

        root.addView(
            cropButton,
            buttonParameters
        )
    }

    private fun createSearchBar(
        root: FrameLayout
    ) {
        searchBar =
            LinearLayout(context).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                gravity =
                    Gravity.CENTER_VERTICAL

                /*
                 * Keeps the search bar above
                 * the keyboard on your phone.
                 */
                translationY =
                    0f

                setPadding(
                    dp(18),
                    dp(6),
                    dp(8),
                    dp(6)
                )

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.RECTANGLE

                        cornerRadius =
                            dp(30).toFloat()

                        setColor(Color.argb(245, 245, 247, 250))

                        setStroke(
                            dp(1),
                            Color.argb(150, 255, 255, 255)
                        )
                    }

                elevation =
                    dp(10).toFloat()
            }

        messageInput =
            EditText(context).apply {
                hint =
                    "Capturing current screen..."

                textSize =
                    17f

                setTextColor(Color.rgb(25, 25, 28))

                setHintTextColor(
                    Color.rgb(125, 128, 134)
                )

                background =
                    null

                isSingleLine =
                    true

                imeOptions =
                    EditorInfo.IME_ACTION_SEND

                setPadding(
                    0,
                    0,
                    dp(8),
                    0
                )

                setOnEditorActionListener {
                        _,
                        actionId,
                        _ ->

                    if (
                        actionId ==
                        EditorInfo.IME_ACTION_SEND
                    ) {
                        submitMessage()
                        true
                    } else {
                        false
                    }
                }

            }

        searchBar.addView(
            messageInput,
            LinearLayout.LayoutParams(
                0,
                dp(52),
                1f
            )
        )

        val sendButton =
            ImageButton(context).apply {
                setImageResource(
                    android.R.drawable.ic_menu_send
                )

                contentDescription =
                    "Search selected screen"

                setColorFilter(
                    Color.WHITE
                )

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.OVAL

                        setColor(
                            Color.rgb(
                                205,
                                20,
                                32
                            )
                        )
                    }

                setPadding(
                    dp(12),
                    dp(12),
                    dp(12),
                    dp(12)
                )

                setOnClickListener {
                    submitMessage()
                }
            }

        searchBar.addView(
            sendButton,
            LinearLayout.LayoutParams(
                dp(46),
                dp(46)
            )
        )

        val searchBarParameters =
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity =
                    Gravity.BOTTOM or
                            Gravity.CENTER_HORIZONTAL

                leftMargin =
                    dp(16)

                rightMargin =
                    dp(16)

                bottomMargin =
                    dp(30)
            }

        root.addView(
            searchBar,
            searchBarParameters
        )
    }

    private fun createBackButton(
        root: FrameLayout
    ) {
        val backButton =
            TextView(context).apply {
                text =
                    "×"

                textSize =
                    28f

                gravity =
                    Gravity.CENTER

                setTextColor(
                    Color.WHITE
                )

                contentDescription =
                    "Close Chopper assistant"

                background =
                    GradientDrawable().apply {
                        shape =
                            GradientDrawable.OVAL

                        setColor(
                            Color.argb(
                                220,
                                17,
                                24,
                                32
                            )
                        )

                        setStroke(
                            dp(1),
                            Color.rgb(
                                65,
                                72,
                                80
                            )
                        )
                    }

                elevation =
                    dp(12).toFloat()

                setOnClickListener {
                    finish()
                }
            }

        val backButtonParameters =
            FrameLayout.LayoutParams(
                dp(48),
                dp(48)
            ).apply {
                gravity =
                    Gravity.TOP or
                            Gravity.START

                leftMargin =
                    dp(16)

                topMargin =
                    dp(24)
            }

        root.addView(
            backButton,
            backButtonParameters
        )

        root.addView(
            TextView(context).apply {
                text = "Chopper"
                textSize = 27f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setShadowLayer(dp(5).toFloat(), 0f, 0f, Color.BLACK)
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(54)
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                topMargin = dp(21)
            }
        )

        val undoButton = TextView(context).apply {
            text = "↶"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = backButton.background.constantState?.newDrawable()
            setOnClickListener {
                screenshotCropView.clearSelection()
                selectedTextOnly = false
                messageInput.setText("")
                messageInput.hint = "Ask about screen"
            }
        }

        root.addView(
            undoButton,
            FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = dp(76)
                topMargin = dp(24)
            }
        )

        val menuButton = TextView(context).apply {
            text = "⋮"
            textSize = 25f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = backButton.background.constantState?.newDrawable()
            setOnClickListener {
                messageInput.hint = "Circle an image or swipe across text"
            }
        }

        root.addView(
            menuButton,
            FrameLayout.LayoutParams(dp(48), dp(48)).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = dp(16)
                topMargin = dp(24)
            }
        )
    }

    override fun onShow(
        arguments: Bundle?,
        showFlags: Int
    ) {
        super.onShow(
            arguments,
            showFlags
        )

        capturedScreenshot =
            null

        selectedTextOnly = false

        if (::messageInput.isInitialized) {
            messageInput.setText("")

            messageInput.hint =
                "Capturing current screen..."
        }

        if (::screenshotCropView.isInitialized) {
            screenshotCropView.visibility =
                View.GONE
        }

        if (::cropButton.isInitialized) {
            cropButton.visibility =
                View.GONE

            cropButton.text =
                "Use selection"
        }
    }

    override fun onHandleScreenshot(
        screenshot: Bitmap?
    ) {
        super.onHandleScreenshot(
            screenshot
        )

        capturedScreenshot =
            screenshot

        if (screenshot == null) {
            messageInput.hint =
                "Screenshot unavailable"

            return
        }

        screenshotCropView.setScreenshot(
            screenshot
        )

        screenshotCropView.visibility =
            View.VISIBLE

        cropButton.visibility = View.GONE

        cropButton.text =
            "Use selection"

        messageInput.hint =
            "Ask about screen"

        scanScreenText(screenshot)
    }

    private fun scanScreenText(screenshot: Bitmap) {
        val recognizer = TextRecognition.getClient(
            TextRecognizerOptions.DEFAULT_OPTIONS
        )

        recognizer.process(InputImage.fromBitmap(screenshot, 0))
            .addOnSuccessListener { result ->
                val words = result.textBlocks
                    .flatMap { it.lines }
                    .flatMap { it.elements }
                    .mapNotNull { element ->
                        val box = element.boundingBox ?: return@mapNotNull null
                        ScreenshotCropView.RecognizedWord(
                            element.text,
                            RectF(box)
                        )
                    }

                screenshotCropView.setRecognizedWords(words)
            }
            .addOnCompleteListener {
                recognizer.close()
            }
    }

    private fun confirmCropSelection() {

        val croppedScreenshot =
            screenshotCropView
                .getCroppedScreenshot()

        if (croppedScreenshot == null) {
            messageInput.hint =
                "Select an area first"

            return
        }

        capturedScreenshot =
            croppedScreenshot

        screenshotCropView.setScreenshot(
            croppedScreenshot
        )

        cropButton.text =
            "Selection ready ✓"

        messageInput.hint =
            "Ask about the selected area..."
    }

    private fun submitMessage() {

        val message =
            messageInput.text
                .toString()
                .trim()

        if (message.isEmpty()) {
            messageInput.error =
                "Enter your question"

            return
        }

        /*
         * If an area is selected, this returns
         * the selected area.
         *
         * If nothing is selected, ScreenshotCropView
         * returns the complete screenshot.
         */
        val selectedScreenshot =
            if (selectedTextOnly) {
                null
            } else if (
                ::screenshotCropView.isInitialized &&
                screenshotCropView.visibility ==
                View.VISIBLE
            ) {
                screenshotCropView
                    .getCroppedScreenshot()
                    ?: capturedScreenshot
            } else {
                capturedScreenshot
            }

        messageInput.hint =
            if (selectedScreenshot != null) {
                "Opening Chopper..."
            } else {
                "Opening Chopper without screenshot..."
            }

        val screenshotPath =
            selectedScreenshot?.let {
                saveTemporaryScreenshot(
                    it
                )
            }

        openMainChopper(
            message,
            screenshotPath
        )
    }

    private fun saveTemporaryScreenshot(
        screenshot: Bitmap
    ): String? {
        return try {
            val screenshotDirectory =
                File(
                    context.cacheDir,
                    "assistant_screenshots"
                ).apply {
                    mkdirs()
                }

            /*
             * Delete older temporary assistant images.
             * They are never stored in the gallery.
             */
            screenshotDirectory
                .listFiles()
                ?.forEach { oldFile ->
                    oldFile.delete()
                }

            val screenshotFile =
                File(
                    screenshotDirectory,
                    "assistant_${System.currentTimeMillis()}.png"
                )

            FileOutputStream(
                screenshotFile
            ).use { outputStream ->
                screenshot.compress(
                    Bitmap.CompressFormat.PNG,
                    100,
                    outputStream
                )
            }

            screenshotFile.absolutePath

        } catch (_: Exception) {
            null
        }
    }

    private fun openMainChopper(
        message: String,
        screenshotPath: String?
    ) {
        val chopperIntent =
            Intent(
                context,
                MainActivity::class.java
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                )

                putExtra(
                    EXTRA_ASSISTANT_QUERY,
                    message
                )

                if (!screenshotPath.isNullOrBlank()) {
                    putExtra(
                        EXTRA_ASSISTANT_SCREENSHOT_PATH,
                        screenshotPath
                    )
                }
            }

        try {
            /*
             * The corrected manifest does not allow
             * MainActivity over the lock screen.
             *
             * When locked, Android keeps authentication
             * in front. After unlocking, Chopper appears.
             */
            startAssistantActivity(
                chopperIntent
            )

            finish()

        } catch (_: Exception) {
            /*
             * Fallback for devices that reject
             * startAssistantActivity().
             */
            try {
                context.startActivity(
                    chopperIntent
                )

                finish()

            } catch (_: Exception) {
                messageInput.hint =
                    "Could not open Chopper"
            }
        }
    }

    override fun onBackPressed() {
        finish()
    }

    private fun dp(
        value: Int
    ): Int {
        return (
                value *
                        context.resources
                            .displayMetrics
                            .density
                ).toInt()
    }

    private companion object {
        const val EXTRA_ASSISTANT_QUERY =
            "chopper_assistant_query"

        const val EXTRA_ASSISTANT_SCREENSHOT_PATH =
            "chopper_assistant_screenshot_path"
    }
}
