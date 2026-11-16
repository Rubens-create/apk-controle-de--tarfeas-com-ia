package com.taskai.app.infra

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.security.KeyStore
import java.time.ZoneId
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AppSettings(
    val userName: String = "",
    val theme: String = "AUTO",
    val language: String = "pt-BR",
    val zoneId: String = ZoneId.systemDefault().id,
    val notificationsEnabled: Boolean = true,
    val dailySummaryEnabled: Boolean = true,
    val dailySummaryHour: Int = 8,
    val defaultReminderMinutes: Int = 15,
    val provider: String = "OPENAI",
    val model: String = "gpt-4o-mini",
    val endpoint: String = "https://api.openai.com/v1",
    val personality: String = "Seja objetivo, gentil e fale português brasileiro.",
    val temperature: Double = 0.3,
    val apiKey: String = ""
) {
    // Do not let generated data-class diagnostics leak the credential.
    override fun toString(): String = "AppSettings(theme=$theme, provider=$provider, apiKey=[REDACTED])"
}

class SettingsStore internal constructor(context: Context, private val secrets: SecretCipher) {
    constructor(context: Context) : this(context, SecretCipher())

    private val preferences = context.applicationContext.getSharedPreferences("taskai.settings", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val state = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    @Synchronized fun save(settings: AppSettings) {
        validate(settings)
        val encrypted = if (settings.apiKey.isEmpty()) "" else secrets.encrypt(settings.apiKey)
        check(preferences.edit()
            .putString("configuration", gson.toJson(settings.copy(apiKey = "")))
            .putString("encryptedKey", encrypted)
            .commit()) { "Não foi possível salvar as configurações." }
        state.value = settings
    }

    private fun load(): AppSettings {
        val configuration = preferences.getString("configuration", null)
        val saved = try {
            configuration?.let { gson.fromJson(it, AppSettings::class.java) }?.also(::validate) ?: AppSettings()
        } catch (_: Exception) { AppSettings() }
        val key = try {
            preferences.getString("encryptedKey", null)?.takeIf { it.isNotEmpty() }?.let(secrets::decrypt) ?: ""
        } catch (_: Exception) {
            // A restored installation may no longer possess its Keystore key. Fail closed.
            ""
        }
        return saved.copy(apiKey = key)
    }

    companion object {
        internal fun validate(s: AppSettings) {
            require(s.theme in setOf("AUTO", "LIGHT", "DARK")) { "Tema inválido." }
            require(s.language.isNotBlank() && s.language.length <= 35) { "Idioma inválido." }
            try { ZoneId.of(s.zoneId) } catch (_: Exception) { throw IllegalArgumentException("Fuso horário inválido.") }
            require(s.dailySummaryHour in 0..23) { "Horário inválido." }
            require(s.defaultReminderMinutes in 0..525600) { "Antecedência inválida." }
            require(s.provider in setOf("OPENAI", "COMPATIBLE", "ANTHROPIC", "GEMINI")) { "Provedor inválido." }
            require(s.model.isNotBlank() && s.model.length <= 200) { "Modelo inválido." }
            require(s.temperature.isFinite() && s.temperature in 0.0..2.0) { "Temperatura inválida." }
            require(s.userName.length <= 200 && s.personality.length <= 16000 && s.apiKey.length <= 8192) { "Configuração muito longa." }
            val endpoint = try { URI(s.endpoint) } catch (_: Exception) { throw IllegalArgumentException("Endpoint HTTPS inválido.") }
            require(endpoint.scheme.equals("https", ignoreCase = true) && !endpoint.host.isNullOrBlank() &&
                endpoint.rawUserInfo == null && endpoint.rawFragment == null && endpoint.rawQuery == null &&
                (endpoint.port == -1 || endpoint.port in 1..65535) && s.endpoint.length <= 2048) { "Use um endpoint HTTPS sem credenciais, consulta ou fragmento." }
        }
    }
}

internal class SecretCipher(private val key: () -> SecretKey = ::androidKey) {
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(AAD)
        return "v1:" + Base64.encodeToString(cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String {
        require(encoded.startsWith("v1:")) { "Formato de chave inválido." }
        val bytes = Base64.decode(encoded.substring(3), Base64.NO_WRAP)
        require(bytes.size >= 28) { "Chave inválida." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }

    companion object {
        private val AAD = "com.taskai.app.settings.apiKey.v1".toByteArray(Charsets.UTF_8)
        private const val ALIAS = "com.taskai.app.apiKey.v1"
        @Synchronized private fun androidKey(): SecretKey {
            val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .setKeySize(256).build())
            }.generateKey()
        }
    }
}
