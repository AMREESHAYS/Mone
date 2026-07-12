package com.example.mone

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the Instagram session cookies encrypted at rest with an AES-256-GCM key held
 * in the Android Keystore (secure hardware where available). yt-dlp needs a real cookie
 * file, so [decryptToTempFile] produces a short-lived plaintext copy that the caller
 * deletes right after the download.
 */
object SecureCookies {
    private const val KEY_ALIAS = "mone_cookies_key"
    private const val ENC_FILE = "cookies.enc"
    private const val LEGACY_FILE = "cookies.txt"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LEN = 12
    private const val TAG_BITS = 128

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun encFile(context: Context) = File(context.filesDir, ENC_FILE)

    fun exists(context: Context): Boolean = encFile(context).exists()

    fun clear(context: Context) {
        encFile(context).delete()
    }

    fun save(context: Context, cookieText: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(cookieText.toByteArray(Charsets.UTF_8))
        encFile(context).outputStream().use { it.write(iv); it.write(ciphertext) }
    }

    /** Decrypts to a temp file in cacheDir. Caller MUST delete it. Null if no login stored. */
    fun decryptToTempFile(context: Context): File? {
        val enc = encFile(context)
        if (!enc.exists()) return null
        return try {
            val bytes = enc.readBytes()
            val iv = bytes.copyOfRange(0, IV_LEN)
            val ciphertext = bytes.copyOfRange(IV_LEN, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(TAG_BITS, iv))
            val plain = cipher.doFinal(ciphertext)
            File.createTempFile("ck", ".txt", context.cacheDir).apply { writeBytes(plain) }
        } catch (e: Exception) {
            null
        }
    }

    /** One-time migration of a pre-encryption plaintext cookies.txt, then delete it. */
    fun migrateLegacy(context: Context) {
        val legacy = File(context.filesDir, LEGACY_FILE)
        if (legacy.exists()) {
            if (!exists(context)) runCatching { save(context, legacy.readText()) }
            legacy.delete()
        }
    }
}
