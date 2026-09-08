package com.example.choppermobile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.example.choppermobile.databinding.FragmentFirstBinding
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File
import java.io.FileOutputStream

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private lateinit var llmInference: LlmInference

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        _binding = FragmentFirstBinding.inflate(
            inflater,
            container,
            false
        )

        return binding.root
    }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?
    ) {
        super.onViewCreated(
            view,
            savedInstanceState
        )

        val modelFile = File(
            requireContext().filesDir,
            "gemma3-270m-it-q8.task"
        )

        try {

            if (!modelFile.exists()) {

                requireContext().assets.open(
                    "gemma3-270m-it-q8.task"
                ).use { input ->

                    FileOutputStream(
                        modelFile
                    ).use { output ->

                        input.copyTo(
                            output
                        )
                    }
                }
            }

            val options =
                LlmInference.LlmInferenceOptions
                    .builder()
                    .setModelPath(
                        modelFile.absolutePath
                    )
                    .setMaxTokens(512)
                    .build()

            llmInference =
                LlmInference.createFromOptions(
                    requireContext(),
                    options
                )

            binding.chatText.text =
                "Chopper: Hello! I'm ready."

        } catch (e: Exception) {

            binding.chatText.text =
                "Model loading error:\n${e.message}"

            return
        }

        binding.sendButton.setOnClickListener {

            val message =
                binding.messageInput.text
                    .toString()
                    .trim()

            if (message.isEmpty()) {
                return@setOnClickListener
            }

            val oldChat =
                binding.chatText.text
                    .toString()

            binding.chatText.text =
                "$oldChat\n\nYou: $message" +
                        "\n\nChopper: Thinking..."

            binding.messageInput.text.clear()

            binding.sendButton.isEnabled = false

            Thread {

                try {

                    val prompt = """
<start_of_turn>user
You are Chopper, a helpful personal AI assistant.

Answer the user's question clearly and in detail.
Explain important points with examples when useful.
Do not give an overly short answer.

User question:
$message
<end_of_turn>
<start_of_turn>model
""".trimIndent()

                    val rawResponse =
                        llmInference.generateResponse(
                            prompt
                        )

                    val response =
                        rawResponse
                            .replace(
                                "<end_of_turn>",
                                ""
                            )
                            .trim()

                    activity?.runOnUiThread {

                        if (_binding == null) {
                            return@runOnUiThread
                        }

                        val currentChat =
                            binding.chatText.text
                                .toString()

                        val cleanChat =
                            currentChat.removeSuffix(
                                "Chopper: Thinking..."
                            )

                        if (response.isNotEmpty()) {

                            binding.chatText.text =
                                "${cleanChat}Chopper: $response"

                        } else {

                            binding.chatText.text =
                                "${cleanChat}Chopper: " +
                                        "I couldn't generate a response. " +
                                        "Please try again."
                        }

                        binding.sendButton.isEnabled = true

                        binding.chatScroll.post {

                            binding.chatScroll.fullScroll(
                                View.FOCUS_DOWN
                            )
                        }
                    }

                } catch (e: Exception) {

                    activity?.runOnUiThread {

                        if (_binding == null) {
                            return@runOnUiThread
                        }

                        val currentChat =
                            binding.chatText.text
                                .toString()

                        val cleanChat =
                            currentChat.removeSuffix(
                                "Chopper: Thinking..."
                            )

                        binding.chatText.text =
                            "${cleanChat}Chopper error: " +
                                    "${e.message}"

                        binding.sendButton.isEnabled = true
                    }
                }

            }.start()
        }
    }

    override fun onDestroyView() {

        _binding = null

        super.onDestroyView()
    }
}