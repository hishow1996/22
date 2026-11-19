package com.moye.pdfink

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v46：用 Android Keystore 里不可导出的 AES-GCM 密钥加密 WebDAV 密码再存进偏好。
 * 好处：应用数据被自动备份 / 换机恢复后，密文解不开（密钥不跟着走），只会让用户重输一次，
 * 不会把明文密码带出设备。任何一步失败都按「没存」处理，不影响其他功能。
 */
object SecretBox {
    private const val ALIAS = "moye_webdav_key"
    private const val PROVIDER = "AndroidKeyStore"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    /** 密文 = base64(IV + 密文)；失败返回 null（调用方就不存）。 */
    fun seal(plain: String): String? = try {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    } catch (t: Throwable) { null }

    /** 解不开（换机 / 密钥丢失 / 数据损坏）返回空串。 */
    fun open(blob: String): String = try {
        if (blob.isEmpty()) ""
        else {
            val raw = Base64.decode(blob, Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, 12)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(c.doFinal(raw, 12, raw.size - 12), Charsets.UTF_8)
        }
    } catch (t: Throwable) { "" }
}
