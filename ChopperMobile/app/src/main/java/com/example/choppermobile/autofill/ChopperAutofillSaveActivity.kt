package com.example.choppermobile.autofill

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.choppermobile.memory.MemoryLifetime
import com.example.choppermobile.memory.PrivateMemory
import com.example.choppermobile.memory.PrivateMemoryVault

class ChopperAutofillSaveActivity : AppCompatActivity() {

    private val privateMemoryVault by lazy {
        PrivateMemoryVault(applicationContext)
    }

    private var authenticationStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (savedInstanceState == null) {
            authenticateAndSave()
        }
    }

    private fun authenticateAndSave() {
        if (authenticationStarted) return
        authenticationStarted = true

        val keys = intent.getStringArrayListExtra(
            ChopperAutofillService.EXTRA_SAVE_KEYS
        ) ?: arrayListOf()

        val values = intent.getStringArrayListExtra(
            ChopperAutofillService.EXTRA_SAVE_VALUES
        ) ?: arrayListOf()

        if (keys.isEmpty() || keys.size != values.size) {
            finish()
            return
        }

        val authenticators =
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.DEVICE_CREDENTIAL

        if (
            BiometricManager.from(this).canAuthenticate(authenticators) !=
            BiometricManager.BIOMETRIC_SUCCESS
        ) {
            Toast.makeText(
                this,
                "Set a fingerprint, face lock or device PIN first.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(
                    result: BiometricPrompt.AuthenticationResult
                ) {
                    super.onAuthenticationSucceeded(result)
                    saveApprovedValues(keys, values)
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errorMessage: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errorMessage)
                    finish()
                }
            }
        )

        val readableFields = keys
            .take(3)
            .joinToString(", ") { key ->
                key.removePrefix("custom_").replace('_', ' ')
            }

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Save in Chopper")
                .setSubtitle("Confirm saving: $readableFields")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun saveApprovedValues(
        keys: ArrayList<String>,
        values: ArrayList<String>
    ) {
        try {
            keys.indices.forEach { index ->
                privateMemoryVault.save(
                    PrivateMemory(
                        canonicalKey = keys[index],
                        value = values[index],
                        lifetime = MemoryLifetime.PERMANENT
                    )
                )
            }

            Toast.makeText(
                this,
                "Saved securely in Chopper.",
                Toast.LENGTH_LONG
            ).show()
        } catch (error: Exception) {
            Toast.makeText(
                this,
                "Chopper could not save this information.",
                Toast.LENGTH_LONG
            ).show()
        }

        finish()
    }
}