package com.btcozum.btvault

import android.util.Base64
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Conforming with CryptoJS AES method (bepeak / vidmixi player).
 * see https://gist.github.com/thackerronak/554c985c3001b16810af5fc0eb5c358f
 */
@Suppress("unused", "FunctionName", "SameParameterValue")
object BepeakCrypto {

    private const val KEY_SIZE    = 256
    private const val IV_SIZE     = 128
    private const val HASH_CIPHER = "AES/CBC/PKCS5Padding"
    private const val AES         = "AES"
    private const val KDF_DIGEST  = "MD5"

    fun decrypt(password: String, cipherText: String, iv: String, s: String): String {
        val ctBytes   = Base64.decode(cipherText.toByteArray(), Base64.DEFAULT)
        val saltBytes = hexToBytes(s)

        val key = ByteArray(KEY_SIZE / 8)
        val ivb = hexToBytes(iv)
        evpkdf(password.toByteArray(), KEY_SIZE, IV_SIZE, saltBytes, key, ivb)

        val cipher = Cipher.getInstance(HASH_CIPHER)
        val keyS = SecretKeySpec(key, AES)
        cipher.init(Cipher.DECRYPT_MODE, keyS, IvParameterSpec(ivb))

        val plainText = cipher.doFinal(ctBytes)
        return String(plainText)
    }

    private fun evpkdf(password: ByteArray, keySize: Int, ivSize: Int, salt: ByteArray, resultKey: ByteArray, resultIv: ByteArray) {
        val keySizeWords = keySize / 32
        val ivSizeWords  = ivSize / 32
        val targetWords  = keySizeWords + ivSizeWords
        val derivedBytes = ByteArray(targetWords * 4)
        var derivedWords = 0
        var block: ByteArray? = null
        val hash = MessageDigest.getInstance(KDF_DIGEST)

        while (derivedWords < targetWords) {
            if (block != null) hash.update(block)
            hash.update(password)
            block = hash.digest(salt)
            hash.reset()

            System.arraycopy(block!!, 0, derivedBytes, derivedWords * 4, min(block.size, (targetWords - derivedWords) * 4))
            derivedWords += block.size / 4
        }

        System.arraycopy(derivedBytes, 0, resultKey, 0, keySizeWords * 4)
        System.arraycopy(derivedBytes, keySizeWords * 4, resultIv, 0, ivSizeWords * 4)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }
}
