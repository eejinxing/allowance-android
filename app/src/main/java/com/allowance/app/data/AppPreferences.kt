package com.allowance.app.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

private val Context.preferenceStore by preferencesDataStore(name = "allowance_preferences")

/**
 * [lastBackup] is the ISO date of the last JSON backup the person saved, or empty if they never have.
 * [ai] is "ollama" when the assistant should connect to Ollama on the person's computer, "" when it uses the built-in rules only;
 * [ollamaUrl], [ollamaModel] and [ollamaVision] remember the address and which models were picked there.
 */
data class Preferences(val theme: String = "SYSTEM", val pinEnabled: Boolean = false, val lastBackup: String = "",
    val ai: String = "", val ollamaUrl: String = "", val ollamaModel: String = "", val ollamaVision: String = "")

class AppPreferences(context: Context) {
    private val store = context.applicationContext.preferenceStore
    private val theme = stringPreferencesKey("theme")
    private val pinHash = stringPreferencesKey("pin_hash")
    private val pinSalt = stringPreferencesKey("pin_salt")
    private val failures = intPreferencesKey("pin_failures")
    private val retryAt = longPreferencesKey("pin_retry_at")
    private val lastBackup = stringPreferencesKey("last_backup")
    private val ai = stringPreferencesKey("ai")
    private val ollamaUrl = stringPreferencesKey("ollama_url")
    private val ollamaModel = stringPreferencesKey("ollama_model")
    private val ollamaVision = stringPreferencesKey("ollama_vision")
    val preferences = store.data.map {
        Preferences(it[theme] ?: "SYSTEM", it[pinHash] != null, it[lastBackup] ?: "", it[ai] ?: "", it[ollamaUrl] ?: "", it[ollamaModel] ?: "", it[ollamaVision] ?: "")
    }

    /** Which AI the assistant uses ("ollama", or "" for the rules only) and, for Ollama, where it is and which models it picked. */
    suspend fun setAi(kind: String, url: String? = null, model: String? = null, vision: String? = null) {
        store.edit { p -> p[ai] = kind; url?.let { p[ollamaUrl] = it }; model?.let { p[ollamaModel] = it }; vision?.let { p[ollamaVision] = it } }
    }

    suspend fun markBackup(isoDate: String) { store.edit { it[lastBackup] = isoDate } }

    suspend fun theme(value: String) { require(value in setOf("SYSTEM", "LIGHT", "DARK")); store.edit { it[theme] = value } }

    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    suspend fun setPin(pin: String) = withContext(Dispatchers.Default) {
        require(pin.matches(Regex("[0-9]{6}"))) { "Enter a 6-digit PIN" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val hashed = hash(pin, salt)
        store.edit {
            it[pinSalt] = Base64.encodeToString(salt, Base64.NO_WRAP)
            it[pinHash] = Base64.encodeToString(hashed, Base64.NO_WRAP)
            it[failures] = 0; it[retryAt] = 0
        }
    }

    suspend fun verify(pin: String): Boolean = withContext(Dispatchers.Default) {
        val values = store.data.first()
        val remaining = ((values[retryAt] ?: 0) - System.currentTimeMillis()) / 1000
        require(remaining <= 0) { "Wait ${remaining + 1} seconds and try again" }
        val stored = values[pinHash] ?: return@withContext true
        val salt = Base64.decode(requireNotNull(values[pinSalt]), Base64.NO_WRAP)
        val valid = MessageDigest.isEqual(hash(pin, salt), Base64.decode(stored, Base64.NO_WRAP))
        store.edit {
            val attempts = if (valid) 0 else (it[failures] ?: 0) + 1
            it[failures] = attempts
            it[retryAt] = if (attempts >= 5) System.currentTimeMillis() + 30_000 else 0L
        }
        valid
    }

    suspend fun removePin(pin: String) {
        require(verify(pin)) { "Incorrect PIN" }
        store.edit { it.remove(pinHash); it.remove(pinSalt); it.remove(failures); it.remove(retryAt) }
    }
}
