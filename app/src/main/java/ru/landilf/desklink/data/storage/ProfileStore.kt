package ru.landilf.desklink.data.storage

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import ru.landilf.desklink.domain.model.SshProfile
import ru.landilf.desklink.domain.repository.ConnectionRepository
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Context.dataStore by preferencesDataStore(name = "remote_terminal")

class ProfileStore(private val context: Context) : ConnectionRepository {
    private val profilesKey = stringPreferencesKey("profiles")
    private val fingerprintsKey = stringPreferencesKey("host_fingerprints")
    override val profiles: Flow<List<SshProfile>> = context.dataStore.data.map { pref ->
        runCatching {
            val array = JSONArray(pref[profilesKey] ?: "[]")
            (0 until array.length()).map { i -> array.getJSONObject(i).toProfile() }
        }.getOrDefault(emptyList())
    }

    override suspend fun save(
        profile: SshProfile,
        password: String,
        privateKey: String,
        passphrase: String
    ) {
        val normalized = profile.copy(id = profile.id.ifBlank { UUID.randomUUID().toString() })
        context.dataStore.edit { prefs ->
            val list = runCatching {
                val old = JSONArray(prefs[profilesKey] ?: "[]")
                (0 until old.length()).map { old.getJSONObject(it).toProfile() }
                    .filterNot { it.id == normalized.id }
            }.getOrDefault(emptyList()) + normalized
            prefs[profilesKey] =
                JSONArray().also { a -> list.forEach { a.put(it.toJson()) } }.toString()
        }
        val vault = SecretVault(context)
        if (password.isNotEmpty()) {
            vault.put("password:${normalized.id}", password)
            vault.delete("key:${normalized.id}")
            vault.delete("passphrase:${normalized.id}")
        }
        if (privateKey.isNotEmpty()) {
            vault.put("key:${normalized.id}", privateKey)
            vault.delete("password:${normalized.id}")
        }
        if (passphrase.isNotEmpty()) SecretVault(context).put(
            "passphrase:${normalized.id}",
            passphrase
        )
    }

    override suspend fun remove(profile: SshProfile) {
        context.dataStore.edit { prefs ->
            val list = runCatching {
                val a = JSONArray(
                    prefs[profilesKey] ?: "[]"
                ); (0 until a.length()).map { a.getJSONObject(it).toProfile() }
            }.getOrDefault(emptyList())
            prefs[profilesKey] = JSONArray().also { a ->
                list.filterNot { it.id == profile.id }.forEach { a.put(it.toJson()) }
            }.toString()
        }
        SecretVault(context).delete("password:${profile.id}")
        SecretVault(context).delete("key:${profile.id}")
        SecretVault(context).delete("passphrase:${profile.id}")
    }

    override suspend fun password(profile: SshProfile): String =
        SecretVault(context).get("password:${profile.id}").orEmpty()

    override suspend fun privateKey(profile: SshProfile): String =
        SecretVault(context).get("key:${profile.id}").orEmpty()

    override suspend fun passphrase(profile: SshProfile): String =
        SecretVault(context).get("passphrase:${profile.id}").orEmpty()

    override suspend fun fingerprint(host: String, port: Int): String? = runCatching {
        JSONObject(context.dataStore.data.first()[fingerprintsKey] ?: "{}").optString("$host:$port")
            .ifBlank { null }
    }.getOrNull()

    override suspend fun saveFingerprint(host: String, port: Int, fingerprint: String) {
        context.dataStore.edit { pref ->
            val json =
                runCatching { JSONObject(pref[fingerprintsKey] ?: "{}") }.getOrElse { JSONObject() }
            json.put("$host:$port", fingerprint)
            pref[fingerprintsKey] = json.toString()
        }
    }

    private fun SshProfile.toJson() =
        JSONObject().put("id", id).put("name", name).put("host", host).put("port", port)
            .put("username", username).put("tmux", tmux).put("tmuxName", tmuxName)
            .put("vncHost", vncHost).put("vncPort", vncPort)
            .put("vncUsername", vncUsername).put("vncFingerprint", vncFingerprint)

    private fun JSONObject.toProfile() = SshProfile(
        optString("id"),
        optString("name"),
        optString("host"),
        optInt("port", 22),
        optString("username"),
        optBoolean("tmux"),
        optString("tmuxName", "codex"),
        optString("vncHost", ""),
        optInt("vncPort", 5900),
        optString("vncUsername", ""),
        optString("vncFingerprint", "")
    )
}

private class SecretVault(private val context: Context) {
    private val prefs = context.getSharedPreferences("encrypted_secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()
        )
        return generator.generateKey()
    }

    fun put(name: String, value: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encoded = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(
            name,
            android.util.Base64.encodeToString(encoded, android.util.Base64.NO_WRAP)
        ).apply()
    }

    fun get(name: String): String? = runCatching {
        val all =
            android.util.Base64.decode(prefs.getString(name, null), android.util.Base64.NO_WRAP)
        val iv = all.copyOfRange(0, 12);
        val encrypted = all.copyOfRange(12, all.size)
        Cipher.getInstance("AES/GCM/NoPadding")
            .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            .doFinal(encrypted).toString(Charsets.UTF_8)
    }.getOrNull()

    fun delete(name: String) {
        prefs.edit().remove(name).apply()
    }

    companion object {
        private const val ALIAS = "remote-terminal-data-key"
    }
}
