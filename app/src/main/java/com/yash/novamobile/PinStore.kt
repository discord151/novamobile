package com.yash.novamobile

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores the unlock PIN encrypted with a key kept in the Android Keystore. */
object PinStore {
    private const val ALIAS = "nova_pin_key"
    private const val PREF = "pin_blob"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    fun save(ctx: Context, pin: String) {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(pin.toByteArray())
        val blob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
        ctx.getSharedPreferences(ListenerService.PREFS, Context.MODE_PRIVATE).edit().putString(PREF, blob).apply()
    }

    fun load(ctx: Context): String? {
        val blob = ctx.getSharedPreferences(ListenerService.PREFS, Context.MODE_PRIVATE).getString(PREF, null) ?: return null
        return try {
            val (iv, ct) = blob.split(":")
            val c = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
            String(c.doFinal(Base64.decode(ct, Base64.NO_WRAP)))
        } catch (e: Exception) { null }
    }

    fun has(ctx: Context) = ctx.getSharedPreferences(ListenerService.PREFS, Context.MODE_PRIVATE).contains(PREF)
}
