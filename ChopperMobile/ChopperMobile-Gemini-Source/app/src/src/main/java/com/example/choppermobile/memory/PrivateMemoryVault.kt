package com.example.choppermobile.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class MemoryLifetime {
    TEMPORARY,
    PERMANENT
}

data class PrivateMemory(
    val id: String = UUID.randomUUID().toString(),
    val canonicalKey: String,
    val value: String,
    val lifetime: MemoryLifetime,
    val createdAt: Long = System.currentTimeMillis(),
    val expiresAt: Long = 0L
)

class PrivateMemoryVault(
    context: Context
) {
    private val appContext = context.applicationContext

    private val legacyPreferences =
        appContext.getSharedPreferences(
            LEGACY_PREFERENCES,
            Context.MODE_PRIVATE
        )

    private val database = MemoryDatabase(appContext)

    private val keyStore =
        KeyStore.getInstance(KEYSTORE_NAME).apply {
            load(null)
        }

    init {
        createKeyIfNecessary()
    }

    @Synchronized
    fun save(memory: PrivateMemory) {
        migrateLegacyMemoriesIfNeeded()

        val encrypted = encrypt(memory.value)
        val values = ContentValues().apply {
            put(COLUMN_ID, memory.id)
            put(COLUMN_KEY, memory.canonicalKey)
            put(COLUMN_VALUE, encrypted.data)
            put(COLUMN_IV, encrypted.iv)
            put(COLUMN_LIFETIME, memory.lifetime.name)
            put(COLUMN_CREATED_AT, memory.createdAt)
            put(COLUMN_EXPIRES_AT, memory.expiresAt)
        }

        database.writableDatabase.insertWithOnConflict(
            TABLE_MEMORIES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    @Synchronized
    fun find(canonicalKey: String): PrivateMemory? {
        migrateLegacyMemoriesIfNeeded()
        removeExpired()

        database.readableDatabase.query(
            TABLE_MEMORIES,
            ALL_COLUMNS,
            "$COLUMN_KEY = ?",
            arrayOf(canonicalKey),
            null,
            null,
            null,
            "1"
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                memoryFromCursor(cursor)
            } else {
                null
            }
        }
    }

    @Synchronized
    fun getAll(): List<PrivateMemory> {
        migrateLegacyMemoriesIfNeeded()
        removeExpired()

        val memories = mutableListOf<PrivateMemory>()

        database.readableDatabase.query(
            TABLE_MEMORIES,
            ALL_COLUMNS,
            null,
            null,
            null,
            null,
            "$COLUMN_CREATED_AT DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                memories.add(memoryFromCursor(cursor))
            }
        }

        return memories
    }

    @Synchronized
    fun delete(id: String) {
        migrateLegacyMemoriesIfNeeded()
        database.writableDatabase.delete(
            TABLE_MEMORIES,
            "$COLUMN_ID = ?",
            arrayOf(id)
        )
    }

    @Synchronized
    fun deleteByKey(canonicalKey: String): Boolean {
        migrateLegacyMemoriesIfNeeded()
        return database.writableDatabase.delete(
            TABLE_MEMORIES,
            "$COLUMN_KEY = ?",
            arrayOf(canonicalKey)
        ) > 0
    }

    fun relevantFor(message: String): List<PrivateMemory> {
        val words = message
            .lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .toSet()

        return getAll()
            .filter { memory ->
                memory.canonicalKey
                    .split("_")
                    .filter { it.length >= 3 }
                    .any { words.contains(it) }
            }
            .take(5)
    }

    private fun removeExpired() {
        val currentTime = System.currentTimeMillis()

        database.writableDatabase.delete(
            TABLE_MEMORIES,
            "$COLUMN_LIFETIME = ? AND $COLUMN_EXPIRES_AT > 0 AND $COLUMN_EXPIRES_AT <= ?",
            arrayOf(
                MemoryLifetime.TEMPORARY.name,
                currentTime.toString()
            )
        )
    }

    private fun memoryFromCursor(
        cursor: android.database.Cursor
    ): PrivateMemory {
        val encryptedValue = cursor.getBlob(
            cursor.getColumnIndexOrThrow(COLUMN_VALUE)
        )
        val iv = cursor.getBlob(
            cursor.getColumnIndexOrThrow(COLUMN_IV)
        )

        return PrivateMemory(
            id = cursor.getString(
                cursor.getColumnIndexOrThrow(COLUMN_ID)
            ),
            canonicalKey = cursor.getString(
                cursor.getColumnIndexOrThrow(COLUMN_KEY)
            ),
            value = decrypt(encryptedValue, iv),
            lifetime = MemoryLifetime.valueOf(
                cursor.getString(
                    cursor.getColumnIndexOrThrow(COLUMN_LIFETIME)
                )
            ),
            createdAt = cursor.getLong(
                cursor.getColumnIndexOrThrow(COLUMN_CREATED_AT)
            ),
            expiresAt = cursor.getLong(
                cursor.getColumnIndexOrThrow(COLUMN_EXPIRES_AT)
            )
        )
    }

    private data class EncryptedValue(
        val data: ByteArray,
        val iv: ByteArray
    )

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())

        return EncryptedValue(
            data = cipher.doFinal(value.toByteArray(Charsets.UTF_8)),
            iv = cipher.iv
        )
    }

    private fun decrypt(
        encryptedValue: ByteArray,
        iv: ByteArray
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getSecretKey(),
            GCMParameterSpec(128, iv)
        )

        return cipher.doFinal(encryptedValue)
            .toString(Charsets.UTF_8)
    }

    private fun migrateLegacyMemoriesIfNeeded() {
        if (legacyPreferences.getBoolean(MIGRATION_COMPLETE, false)) {
            return
        }

        val legacyData = legacyPreferences.getString(
            LEGACY_DATA_KEY,
            null
        )

        if (legacyData.isNullOrBlank()) {
            legacyPreferences.edit()
                .putBoolean(MIGRATION_COMPLETE, true)
                .apply()
            return
        }

        val oldMemories = readLegacyMemories(legacyData)
        val writableDatabase = database.writableDatabase

        writableDatabase.beginTransaction()
        try {
            oldMemories.forEach { memory ->
                val encrypted = encrypt(memory.value)
                val values = ContentValues().apply {
                    put(COLUMN_ID, memory.id)
                    put(COLUMN_KEY, memory.canonicalKey)
                    put(COLUMN_VALUE, encrypted.data)
                    put(COLUMN_IV, encrypted.iv)
                    put(COLUMN_LIFETIME, memory.lifetime.name)
                    put(COLUMN_CREATED_AT, memory.createdAt)
                    put(COLUMN_EXPIRES_AT, memory.expiresAt)
                }

                writableDatabase.insertWithOnConflict(
                    TABLE_MEMORIES,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }

        legacyPreferences.edit()
            .remove(LEGACY_DATA_KEY)
            .putBoolean(MIGRATION_COMPLETE, true)
            .apply()
    }

    private fun readLegacyMemories(
        encryptedData: String
    ): List<PrivateMemory> {
        val encryptedObject = JSONObject(encryptedData)
        val iv = Base64.decode(
            encryptedObject.getString("iv"),
            Base64.NO_WRAP
        )
        val ciphertext = Base64.decode(
            encryptedObject.getString("data"),
            Base64.NO_WRAP
        )

        val plaintext = decrypt(ciphertext, iv)
        val jsonArray = JSONArray(plaintext)
        val memories = mutableListOf<PrivateMemory>()

        for (index in 0 until jsonArray.length()) {
            val item = jsonArray.getJSONObject(index)
            memories.add(
                PrivateMemory(
                    id = item.getString("id"),
                    canonicalKey = item.getString("key"),
                    value = item.getString("value"),
                    lifetime = MemoryLifetime.valueOf(
                        item.getString("lifetime")
                    ),
                    createdAt = item.getLong("createdAt"),
                    expiresAt = item.optLong("expiresAt", 0L)
                )
            )
        }

        return memories
    }

    private fun createKeyIfNecessary() {
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return
        }

        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            KEYSTORE_NAME
        )

        val keyBuilder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(
                KeyProperties.ENCRYPTION_PADDING_NONE
            )
            .setKeySize(256)
            .setUserAuthenticationRequired(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            keyBuilder.setUserAuthenticationParameters(
                AUTHENTICATION_SECONDS,
                KeyProperties.AUTH_BIOMETRIC_STRONG or
                        KeyProperties.AUTH_DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            keyBuilder.setUserAuthenticationValidityDurationSeconds(
                AUTHENTICATION_SECONDS
            )
        }

        keyGenerator.init(keyBuilder.build())
        keyGenerator.generateKey()
    }

    private fun getSecretKey(): SecretKey {
        return keyStore.getKey(KEY_ALIAS, null) as SecretKey
    }

    private class MemoryDatabase(
        context: Context
    ) : SQLiteOpenHelper(
        context,
        DATABASE_NAME,
        null,
        DATABASE_VERSION
    ) {
        override fun onCreate(database: SQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE $TABLE_MEMORIES (
                    $COLUMN_ID TEXT PRIMARY KEY,
                    $COLUMN_KEY TEXT NOT NULL UNIQUE,
                    $COLUMN_VALUE BLOB NOT NULL,
                    $COLUMN_IV BLOB NOT NULL,
                    $COLUMN_LIFETIME TEXT NOT NULL,
                    $COLUMN_CREATED_AT INTEGER NOT NULL,
                    $COLUMN_EXPIRES_AT INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX index_memory_created_at ON $TABLE_MEMORIES($COLUMN_CREATED_AT DESC)"
            )
        }

        override fun onUpgrade(
            database: SQLiteDatabase,
            oldVersion: Int,
            newVersion: Int
        ) {
            // Future schema migrations will be added here.
        }
    }

    companion object {
        private const val KEYSTORE_NAME = "AndroidKeyStore"
        private const val KEY_ALIAS = "chopper_private_memory_key_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val AUTHENTICATION_SECONDS = 30

        private const val LEGACY_PREFERENCES =
            "chopper_encrypted_private_vault"
        private const val LEGACY_DATA_KEY = "encrypted_memory_blob"
        private const val MIGRATION_COMPLETE =
            "sqlite_migration_complete_v1"

        private const val DATABASE_NAME = "chopper_private_memory.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_MEMORIES = "private_memories"
        private const val COLUMN_ID = "id"
        private const val COLUMN_KEY = "canonical_key"
        private const val COLUMN_VALUE = "encrypted_value"
        private const val COLUMN_IV = "initialization_vector"
        private const val COLUMN_LIFETIME = "lifetime"
        private const val COLUMN_CREATED_AT = "created_at"
        private const val COLUMN_EXPIRES_AT = "expires_at"

        private val ALL_COLUMNS = arrayOf(
            COLUMN_ID,
            COLUMN_KEY,
            COLUMN_VALUE,
            COLUMN_IV,
            COLUMN_LIFETIME,
            COLUMN_CREATED_AT,
            COLUMN_EXPIRES_AT
        )
    }
}