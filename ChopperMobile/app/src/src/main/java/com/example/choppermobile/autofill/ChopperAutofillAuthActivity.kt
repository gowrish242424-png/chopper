package com.example.choppermobile.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.autofill.Dataset
import android.util.Log
import android.view.WindowManager
import android.view.autofill.AutofillId
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.example.choppermobile.memory.PrivateMemoryVault

class ChopperAutofillAuthActivity : AppCompatActivity() {

    private val privateMemoryVault by lazy {
        PrivateMemoryVault(applicationContext)
    }

    private var authenticationStarted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        if (savedInstanceState == null) {
            startAuthentication()
        }
    }

    private fun startAuthentication() {
        if (authenticationStarted) return
        authenticationStarted = true

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
            cancelAutofill()
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
                    createAutofillDataset()
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errorMessage: CharSequence
                ) {
                    super.onAuthenticationError(errorCode, errorMessage)
                    Log.d(TAG, "Authentication cancelled: $errorMessage")
                    cancelAutofill()
                }
            }
        )

        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Chopper Autofill")
                .setSubtitle("Confirm before filling your personal information")
                .setAllowedAuthenticators(authenticators)
                .build()
        )
    }

    private fun createAutofillDataset() {
        try {
            val autofillIds = getAutofillIds()
            val memoryKeys = intent.getStringArrayListExtra(
                ChopperAutofillService.EXTRA_MEMORY_KEYS
            ) ?: arrayListOf()

            Log.d(TAG, "Authentication received keys: $memoryKeys")

            if (autofillIds.isEmpty() || autofillIds.size != memoryKeys.size) {
                Log.e(
                    TAG,
                    "Invalid field data: ids=${autofillIds.size}, keys=${memoryKeys.size}"
                )
                cancelAutofill()
                return
            }

            val presentation = RemoteViews(
                packageName,
                android.R.layout.simple_list_item_1
            ).apply {
                setTextViewText(android.R.id.text1, "Filled securely by Chopper")
            }

            val datasetBuilder = Dataset.Builder()
            var filledFieldCount = 0

            autofillIds.indices.forEach { index ->
                val memory = privateMemoryVault.find(memoryKeys[index])

                Log.d(
                    TAG,
                    "Memory ${memoryKeys[index]} available: ${memory != null}"
                )

                if (memory != null && memory.value.isNotBlank()) {
                    datasetBuilder.setValue(
                        autofillIds[index],
                        AutofillValue.forText(memory.value),
                        presentation
                    )
                    filledFieldCount++
                }
            }

            if (filledFieldCount == 0) {
                Toast.makeText(
                    this,
                    "No matching information is saved in Chopper.",
                    Toast.LENGTH_LONG
                ).show()
                cancelAutofill()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                datasetBuilder.setId("chopper_unlocked_private_profile")
            }

            val resultIntent = Intent().apply {
                putExtra(
                    AutofillManager.EXTRA_AUTHENTICATION_RESULT,
                    datasetBuilder.build()
                )
            }

            Log.d(TAG, "Returning authenticated dataset with $filledFieldCount fields")
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } catch (error: Exception) {
            Log.e(TAG, "Could not create authenticated dataset", error)
            Toast.makeText(
                this,
                "Autofill error: ${error.message ?: "unknown error"}",
                Toast.LENGTH_LONG
            ).show()
            cancelAutofill()
        }
    }

    @Suppress("DEPRECATION")
    private fun getAutofillIds(): ArrayList<AutofillId> {
        return intent.getParcelableArrayListExtra(
            ChopperAutofillService.EXTRA_AUTOFILL_IDS
        ) ?: arrayListOf()
    }

    private fun cancelAutofill() {
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    companion object {
        private const val TAG = "ChopperAutofill"
    }
}