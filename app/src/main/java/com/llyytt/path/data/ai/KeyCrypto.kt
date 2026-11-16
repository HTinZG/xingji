package com.llyytt.path.data.ai

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AI Key 的本地加密。
 *
 * 用 Android Keystore 里的 AES-256-GCM：
 * - 密钥永远不出 TEE/StrongBox，App 只能"用"它，读不到明文
 * - 每次加密随机 IV，IV 拼在密文前面一起 Base64
 * - 不需要用户认证（setUserAuthenticationRequired(false)），锁屏也能用
 *
 * 刻意不用 EncryptedSharedPreferences —— 那个库已经停止维护，且行为不透明。
 */
object KeyCrypto {

    private const val ALIAS = "xingji_ai_key"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        val store = keyStore()
        val existing = (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    /** 加密成 Base64(IV || 密文)。失败抛异常，调用方自己处理。 */
    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        val combined = ByteArray(iv.size + cipherText.size)
        iv.copyInto(combined, 0)
        cipherText.copyInto(combined, iv.size)
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** 解密失败返回 null（比如 Keystore 里密钥被清掉）。 */
    fun decrypt(blob: String): String? = runCatching {
        val combined = Base64.decode(blob, Base64.NO_WRAP)
        if (combined.size <= IV_LENGTH) return null
        val iv = combined.copyOfRange(0, IV_LENGTH)
        val cipherText = combined.copyOfRange(IV_LENGTH, combined.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()

    /** 清空设置时把密钥也删掉。 */
    fun deleteKey() {
        runCatching {
            val store = keyStore()
            if (store.containsAlias(ALIAS)) store.deleteEntry(ALIAS)
        }
    }
}
