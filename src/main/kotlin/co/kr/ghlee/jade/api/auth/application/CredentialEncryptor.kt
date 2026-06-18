package co.kr.ghlee.jade.api.auth.application

import co.kr.ghlee.jade.api.auth.infrastructure.config.JadeAuthProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

@Component
class CredentialEncryptor(
    properties: JadeAuthProperties,
) {
    private val key = MessageDigest.getInstance("SHA-256")
        .digest(properties.cryptoSecret.toByteArray(Charsets.UTF_8))
        .let { SecretKeySpec(it, "AES") }

    private val random = SecureRandom()

    fun encrypt(plainText: String): String {
        val iv = ByteArray(IV_SIZE).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))

        return Base64.getEncoder().encodeToString(iv + encrypted)
    }

    fun decrypt(cipherText: String): String {
        val payload = Base64.getDecoder().decode(cipherText)
        val iv = payload.copyOfRange(0, IV_SIZE)
        val encrypted = payload.copyOfRange(IV_SIZE, payload.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))

        return cipher.doFinal(encrypted).toString(Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_LENGTH_BITS = 128
    }
}
