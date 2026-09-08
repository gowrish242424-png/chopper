package com.example.choppermobile.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.Dataset
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.SaveCallback
import android.service.autofill.SaveInfo
import android.service.autofill.SaveRequest
import android.util.Log
import android.text.InputType
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi

class ChopperAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        try {
            if (cancellationSignal.isCanceled) {
                callback.onSuccess(null)
                return
            }

            val fillContext =
                request.fillContexts.lastOrNull()

            if (fillContext == null) {
                callback.onSuccess(null)
                return
            }

            val structure =
                fillContext.structure

            // Never autofill or collect Chopper's own chat input.
            if (structure.activityComponent?.packageName == packageName) {
                callback.onSuccess(null)
                return
            }

            val autofillIds =
                arrayListOf<AutofillId>()

            val memoryKeys =
                arrayListOf<String>()

            val focusedSaveIds =
                arrayListOf<AutofillId>()

            for (
            windowIndex in
            0 until structure.windowNodeCount
            ) {
                val rootNode =
                    structure
                        .getWindowNodeAt(windowIndex)
                        .rootViewNode

                collectSupportedFields(
                    node = rootNode,
                    autofillIds = autofillIds,
                    memoryKeys = memoryKeys,
                    focusedSaveIds = focusedSaveIds
                )
            }

            Log.d(TAG, "Detected ${memoryKeys.size} supported autofill fields")

            if (
                autofillIds.isEmpty() ||
                cancellationSignal.isCanceled
            ) {
                callback.onSuccess(null)
                return
            }

            // Keep the complete personal profile together so selecting
            // Chopper once can fill every matching field on the form.
            val selectedIndices = autofillIds.indices.toList()

            val selectedAutofillIds = ArrayList(
                selectedIndices.map { index -> autofillIds[index] }
            )

            val selectedMemoryKeys = ArrayList(
                selectedIndices.map { index -> memoryKeys[index] }
            )

            Log.d(TAG, "Prepared ${selectedMemoryKeys.size} autofill fields for authentication")

            val authenticationIntent =
                Intent(
                    this,
                    ChopperAutofillAuthActivity::class.java
                ).apply {
                    putParcelableArrayListExtra(
                        EXTRA_AUTOFILL_IDS,
                        selectedAutofillIds
                    )

                    putStringArrayListExtra(
                        EXTRA_MEMORY_KEYS,
                        selectedMemoryKeys
                    )
                }

            val pendingIntent =
                PendingIntent.getActivity(
                    this,
                    AUTHENTICATION_REQUEST_CODE,
                    authenticationIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT or
                            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                PendingIntent.FLAG_MUTABLE
                            } else {
                                0
                            })
                )

            val presentation =
                RemoteViews(
                    packageName,
                    android.R.layout.simple_list_item_1
                ).apply {
                    setTextViewText(
                        android.R.id.text1,
                        "Autofill with Chopper"
                    )
                }

            val lockedDatasetBuilder =
                Dataset.Builder()

            val inlinePresentation =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val inlineRequest = request.inlineSuggestionsRequest
                    val specification = inlineRequest
                        ?.inlinePresentationSpecs
                        ?.firstOrNull()

                    if (specification != null) {
                        val content: UiVersions.Content = InlineSuggestionUi
                            .newContentBuilder(pendingIntent)
                            .setTitle("Chopper")
                            .setSubtitle("Autofill private profile")
                            .setStartIcon(
                                Icon.createWithResource(
                                    this,
                                    applicationInfo.icon
                                )
                            )
                            .setContentDescription(
                                "Autofill with Chopper"
                            )
                            .build()

                        val slice = content.slice

                        InlinePresentation(
                            slice,
                            specification,
                            false
                        )
                    } else {
                        null
                    }
                } else {
                    null
                }

            Log.d(
                TAG,
                "Inline suggestion available: ${inlinePresentation != null}"
            )

            selectedAutofillIds.forEach { autofillId ->
                @Suppress("DEPRECATION")
                if (inlinePresentation != null &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ) {
                    lockedDatasetBuilder.setValue(
                        autofillId,
                        null as AutofillValue?,
                        presentation,
                        inlinePresentation
                    )
                } else {
                    lockedDatasetBuilder.setValue(
                        autofillId,
                        null as AutofillValue?,
                        presentation
                    )
                }
            }

            lockedDatasetBuilder.setAuthentication(
                pendingIntent.intentSender
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                lockedDatasetBuilder.setId(
                    "chopper_private_profile"
                )
            }

            val responseBuilder = FillResponse.Builder()
                .addDataset(lockedDatasetBuilder.build())

            val requiredSaveIds = if (focusedSaveIds.isNotEmpty()) {
                focusedSaveIds.distinct()
            } else {
                listOf(autofillIds.first())
            }

            val optionalSaveIds = autofillIds
                .filterNot { requiredSaveIds.contains(it) }
                .distinct()

            val saveInfoBuilder = SaveInfo.Builder(
                SaveInfo.SAVE_DATA_TYPE_GENERIC,
                requiredSaveIds.toTypedArray()
            ).setDescription(
                "Save this information securely in Chopper?"
            )

            if (optionalSaveIds.isNotEmpty()) {
                saveInfoBuilder.setOptionalIds(optionalSaveIds.toTypedArray())
            }

            responseBuilder.setSaveInfo(saveInfoBuilder.build())

            val response = responseBuilder.build()

            callback.onSuccess(response)

        } catch (error: Exception) {
            Log.e(
                TAG,
                "Autofill request failed",
                error
            )

            callback.onSuccess(null)
        }
    }

    override fun onSaveRequest(
        request: SaveRequest,
        callback: SaveCallback
    ) {
        try {
            val keys = arrayListOf<String>()
            val values = arrayListOf<String>()

            request.fillContexts.lastOrNull()
                ?.structure
                ?.let { structure ->
                    // Never save text entered into Chopper itself.
                    if (structure.activityComponent?.packageName == packageName) {
                        callback.onSuccess()
                        return
                    }

                    for (windowIndex in 0 until structure.windowNodeCount) {
                        collectEnteredValues(
                            node = structure
                                .getWindowNodeAt(windowIndex)
                                .rootViewNode,
                            keys = keys,
                            values = values
                        )
                    }
                }

            if (keys.isNotEmpty()) {
                startActivity(
                    Intent(
                        this,
                        ChopperAutofillSaveActivity::class.java
                    ).apply {
                        putStringArrayListExtra(EXTRA_SAVE_KEYS, keys)
                        putStringArrayListExtra(EXTRA_SAVE_VALUES, values)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }

            callback.onSuccess()
        } catch (error: Exception) {
            Log.e(TAG, "Could not prepare information for saving", error)
            callback.onFailure("Chopper could not save this information.")
        }
    }

    private fun collectSupportedFields(
        node: AssistStructure.ViewNode,
        autofillIds: ArrayList<AutofillId>,
        memoryKeys: ArrayList<String>,
        focusedSaveIds: ArrayList<AutofillId>
    ) {
        val editableField = isEditableField(node)
        val acceptsText = acceptsTextAutofillValue(node)
        val autofillId = if (editableField && acceptsText) node.autofillId else null
        val memoryKey = if (editableField && acceptsText) identifyMemoryKey(node) else null

        /*
         * Do not require AUTOFILL_TYPE_TEXT here.
         * Some browsers and WebViews expose HTML inputs
         * without that value.
         */
        if (
            autofillId != null &&
            memoryKey != null &&
            !autofillIds.contains(autofillId)
        ) {
            autofillIds.add(autofillId)
            memoryKeys.add(memoryKey)

            if (node.isFocused) {
                focusedSaveIds.add(autofillId)
            }
        }

        if (editableField && !acceptsText) {
            Log.d(
                TAG,
                "Skipping non-text field: key=${identifyMemoryKey(node)}, " +
                        "autofillType=${node.autofillType}"
            )
        }

        for (
        childIndex in
        0 until node.childCount
        ) {
            collectSupportedFields(
                node = node.getChildAt(childIndex),
                autofillIds = autofillIds,
                memoryKeys = memoryKeys,
                focusedSaveIds = focusedSaveIds
            )
        }
    }

    /**
     * AutofillValue.forText() may only be sent to text fields. Chrome exposes
     * some HTML controls (for example country/state dropdowns) as LIST fields.
     * Sending a text value to any one of those makes Chrome reject the whole
     * dataset, so keep them out of this text-profile dataset.
     */
    private fun acceptsTextAutofillValue(
        node: AssistStructure.ViewNode
    ): Boolean {
        return node.autofillType == View.AUTOFILL_TYPE_TEXT ||
                node.autofillType == View.AUTOFILL_TYPE_NONE
    }

    private fun collectEnteredValues(
        node: AssistStructure.ViewNode,
        keys: ArrayList<String>,
        values: ArrayList<String>
    ) {
        val editableField = isEditableField(node)
        val key = if (editableField) identifyMemoryKey(node) else null
        val value = if (editableField) {
            node.autofillValue
                ?.takeIf { it.isText }
                ?.textValue
                ?.toString()
                ?.trim()
        } else {
            null
        }

        if (
            key != null &&
            !value.isNullOrBlank() &&
            value.length <= MAX_SAVED_VALUE_LENGTH
        ) {
            val existingIndex = keys.indexOf(key)

            if (existingIndex >= 0) {
                values[existingIndex] = value
            } else {
                keys.add(key)
                values.add(value)
            }
        }

        for (childIndex in 0 until node.childCount) {
            collectEnteredValues(
                node = node.getChildAt(childIndex),
                keys = keys,
                values = values
            )
        }
    }

    private fun identifyMemoryKey(
        node: AssistStructure.ViewNode
    ): String? {
        if (isSensitiveInputType(node.inputType)) {
            return null
        }

        val standardTokens = mutableListOf<String>()

        node.autofillHints
            ?.forEach { hint ->
                standardTokens.add(normalizeFieldToken(hint))
            }

        node.htmlInfo
            ?.attributes
            ?.filter { attribute ->
                attribute.first.equals("autocomplete", ignoreCase = true)
            }
            ?.flatMap { attribute ->
                attribute.second.split(Regex("\\s+"))
            }
            ?.forEach { token ->
                standardTokens.add(normalizeFieldToken(token))
            }

        // HTML autocomplete values and Android autofill hints are standard,
        // unambiguous identifiers. Always trust them before reading labels.
        standardTokens.forEach { token ->
            standardMemoryKey(token)?.let { return it }
        }

        val information =
            buildString {
                node.autofillHints
                    ?.forEach { value ->
                        append(value)
                        append(' ')
                    }

                append(node.hint ?: "")
                append(' ')

                append(node.idEntry ?: "")
                append(' ')

                append(node.className ?: "")
                append(' ')

                /*
                 * Read HTML attributes such as:
                 * name, id, type and autocomplete.
                 */
                node.htmlInfo
                    ?.attributes
                    ?.forEach { attribute ->
                        // Attribute keys such as "name" exist on almost
                        // every HTML input. Only their values describe the
                        // actual field (for example: email, tel or address).
                        append(attribute.second)
                        append(' ')
                    }
            }
                .lowercase()
                .replace(
                    Regex("[^a-z0-9]+"),
                    " "
                )
                .trim()

        if (information.isBlank()) {
            return null
        }

        Log.d(
            TAG,
            "Inspecting field: $information"
        )

        val blockedTerms =
            listOf(
                "password",
                "passcode",
                "otp",
                "one time password",
                "credit card",
                "debit card",
                "card number",
                "cvv",
                "cvc",
                "bank account",
                "upi",
                "aadhaar",
                "aadhar",
                "pan number",
                "passport",
                "security code"
            )

        if (
            blockedTerms.any {
                information.contains(it)
            }
        ) {
            return null
        }

        val inputClass =
            node.inputType and InputType.TYPE_MASK_CLASS

        val inputVariation =
            node.inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass == InputType.TYPE_CLASS_PHONE) {
            return "phone_number"
        }

        if (
            inputVariation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
            inputVariation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        ) {
            return "email_address"
        }

        if (
            inputVariation == InputType.TYPE_TEXT_VARIATION_POSTAL_ADDRESS
        ) {
            return "home_address"
        }

        return when {
            containsAny(information, "current password", "new password") -> null

            containsAny(information, "your age", "age in years") ||
                    Regex("(^| )age( |$)").containsMatchIn(information) ->
                "age"

            containsAny(
                information,
                "date of birth",
                "birth date",
                "birthdate",
                "birthday",
                "bday",
                "dob"
            ) ->
                "date_of_birth"

            containsAny(information, "email address", "emailaddress", "e mail", "email") ->
                "email_address"

            containsAny(information, "phone number", "mobile number", "telephone", "phone", "mobile") ->
                "phone_number"

            containsAny(information, "postal code", "postcode", "zip code", "zipcode") ->
                "postal_code"

            containsAny(information, "country name", "country code", "country") ->
                "country"

            containsAny(information, "street address", "postal address", "address line", "home address") ->
                "home_address"

            containsAny(information, "user name", "username", "login name") ->
                "username"

            containsAny(information, "nick name", "nickname") ->
                "nickname"

            containsAny(information, "organization title", "job title", "position title") ->
                "job_title"

            containsAny(information, "organization", "company", "employer") ->
                "organization"

            containsAny(information, "additional name", "middle name", "middlename") ->
                "middle_name"

            containsAny(
                information,
                "given name",
                "givenname",
                "given-name",
                "first name",
                "firstname",
                "first-name",
                "fname"
            ) ->
                "first_name"

            containsAny(
                information,
                "family name",
                "familyname",
                "family-name",
                "last name",
                "lastname",
                "last-name",
                "surname",
                "lname"
            ) ->
                "last_name"

            containsAny(
                information,
                "full name",
                "fullname",
                "full-name",
                "your name",
                "person name",
                "complete name"
            ) ->
                "name"

            containsAny(
                information,
                "blood group",
                "blood type"
            ) ->
                "blood_group"

            containsAny(
                information,
                "college name",
                "institution name",
                "university name",
                "college",
                "institution",
                "university"
            ) ->
                "college"

            containsAny(
                information,
                "department",
                "course",
                "branch"
            ) ->
                "department"

            else -> customMemoryKey(node)
        }
    }

    private fun isEditableField(node: AssistStructure.ViewNode): Boolean {
        if (node.autofillType == View.AUTOFILL_TYPE_TEXT) {
            return true
        }

        if (
            node.className
                ?.toString()
                ?.contains("EditText", ignoreCase = true) == true
        ) {
            return true
        }

        val htmlTag = node.htmlInfo?.tag?.lowercase()
        return htmlTag == "input" || htmlTag == "textarea"
    }

    private fun customMemoryKey(node: AssistStructure.ViewNode): String? {
        val candidates = mutableListOf<String>()

        node.hint?.let { candidates.add(it) }
        node.idEntry?.let { candidates.add(it) }
        node.autofillHints?.let { candidates.addAll(it) }

        node.htmlInfo
            ?.attributes
            ?.filter { attribute ->
                attribute.first.equals("name", true) ||
                        attribute.first.equals("id", true) ||
                        attribute.first.equals("autocomplete", true)
            }
            ?.forEach { attribute -> candidates.add(attribute.second) }

        return candidates
            .asSequence()
            .map { candidate ->
                candidate
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
            }
            .firstOrNull { candidate ->
                candidate.length in 2..50 &&
                        candidate !in GENERIC_FIELD_NAMES &&
                        GENERIC_FIELD_TERMS.none { term -> candidate.contains(term) } &&
                        !containsAny(candidate, "password", "otp", "cvv", "card")
            }
            ?.let { candidate -> "custom_$candidate" }
    }

    private fun standardMemoryKey(token: String): String? {
        return when (token) {
            "name" -> "name"
            "given-name", "first-name" -> "first_name"
            "additional-name", "middle-name" -> "middle_name"
            "family-name", "last-name" -> "last_name"
            "honorific-prefix" -> "honorific_prefix"
            "honorific-suffix" -> "honorific_suffix"
            "nickname" -> "nickname"
            "username" -> "username"
            "organization" -> "organization"
            "organization-title" -> "job_title"
            "street-address", "address-line1", "address-line2", "address-line3" ->
                "home_address"
            "address-level1" -> "state"
            "address-level2" -> "city"
            "address-level3" -> "district"
            "country", "country-name" -> "country"
            "postal-code" -> "postal_code"
            "tel", "tel-national", "tel-local" -> "phone_number"
            "email" -> "email_address"
            "bday", "bday-day", "bday-month", "bday-year" -> "date_of_birth"
            "current-password", "new-password", "one-time-code", "cc-number",
            "cc-csc", "cc-exp", "cc-exp-month", "cc-exp-year" -> null
            else -> null
        }
    }

    private fun normalizeFieldToken(value: String): String {
        return value
            .trim()
            .lowercase()
            .replace(Regex("[_\\s]+"), "-")
    }

    private fun isSensitiveInputType(inputType: Int): Boolean {
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (inputClass == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun containsAny(
        text: String,
        vararg values: String
    ): Boolean {
        return values.any { value ->
            text.contains(value)
        }
    }

    companion object {
        const val EXTRA_AUTOFILL_IDS =
            "chopper_autofill_ids"

        const val EXTRA_MEMORY_KEYS =
            "chopper_memory_keys"

        const val EXTRA_SAVE_KEYS =
            "chopper_save_keys"

        const val EXTRA_SAVE_VALUES =
            "chopper_save_values"

        private const val AUTHENTICATION_REQUEST_CODE =
            7041

        private const val TAG =
            "ChopperAutofill"

        private const val MAX_SAVED_VALUE_LENGTH = 300

        private val GENERIC_FIELD_NAMES = setOf(
            "text",
            "input",
            "field",
            "edittext",
            "entry",
            "search"
        )

        private val GENERIC_FIELD_TERMS = setOf(
            "message",
            "chat",
            "comment",
            "search",
            "query",
            "prompt",
            "reply",
            "caption"
        )
    }
}
