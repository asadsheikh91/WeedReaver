package com.example.andriodfypprototype.data.net

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The session on this handset: tokens, the station's device id and the install id. Values are
 * sealed with an AES-256-GCM key that never leaves the Android Keystore, so a copied
 * preferences file is useless off this phone. The refresh token lasts 60 days, which is how
 * long an operator can work offline without signing in again.
 */
class SecureStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("wr_session", Context.MODE_PRIVATE)

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey ?: KeyGenerator
            .getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
            .apply {
                init(
                    KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }
            .generateKey()
    }

    private fun seal(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key) }
        val body = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(c.iv + body, Base64.NO_WRAP)
    }

    private fun open(sealed: String): String? = try {
        val raw = Base64.decode(sealed, Base64.NO_WRAP)
        val c = Cipher.getInstance(TRANSFORM).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, raw, 0, IV_BYTES))
        }
        String(c.doFinal(raw, IV_BYTES, raw.size - IV_BYTES), Charsets.UTF_8)
    } catch (_: Exception) {
        null // key rotated or the file came from another phone: treat as signed out
    }

    private fun read(name: String): String? = prefs.getString(name, null)?.let(::open)

    private fun write(edit: SharedPreferences.Editor, name: String, value: String?) {
        if (value == null) edit.remove(name) else edit.putString(name, seal(value))
    }

    @get:Synchronized
    val accessToken: String? get() = read("access")

    @get:Synchronized
    val refreshToken: String? get() = read("refresh")

    val deviceId: String? get() = prefs.getString("device", null)
    val userJson: String? get() = read("user")
    val email: String? get() = prefs.getString("email", null)

    val signedIn: Boolean get() = prefs.contains("refresh")

    /** Generated once per install; the station de-duplicates handsets on it. */
    val installId: String
        @Synchronized get() = prefs.getString("install", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("install", it).apply()
        }

    @Synchronized
    fun saveTokens(access: String, refresh: String) {
        prefs.edit().also { write(it, "access", access); write(it, "refresh", refresh) }.commit()
    }

    @Synchronized
    fun saveSession(t: TokenOut, userJson: String) {
        prefs.edit().also {
            write(it, "access", t.accessToken)
            write(it, "refresh", t.refreshToken)
            write(it, "user", userJson)
            it.putString("email", t.user.email)
            if (t.deviceId != null) it.putString("device", t.deviceId)
        }.commit()
    }

    /**
     * Ends the session. The install id, device id and the last operator's profile stay, so the
     * welcome card still names whose records are on this phone.
     */
    @Synchronized
    fun clearSession() {
        prefs.edit().remove("access").remove("refresh").commit()
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "weedreaver.session"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}
