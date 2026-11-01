package com.shegrowsdev.mail.backup

import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.MailFailure
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Backup file = header + AES-256-GCM ciphertext of the gzip-compressed JSON.
 *
 *   "CMBK1" | iterations (4 bytes) | salt (16) | iv (12) | ciphertext+tag
 *
 * The key comes from a passphrase (PBKDF2-HMAC-SHA256, random salt per file), so the key itself is never
 * stored anywhere and a backup can be opened on another device by whoever knows the passphrase.
 * The header is authenticated (AAD): any change to the file, header included, makes decryption fail.
 */
object BackupCrypto {
    private val MAGIC = "CMBK1".toByteArray(Charsets.US_ASCII)
    private const val SALT = 16
    private const val IV = 12
    private const val TAG_BITS = 128
    const val DEFAULT_ITERATIONS = 310_000
    private const val MIN_ITERATIONS = 1_000
    private const val MAX_ITERATIONS = 5_000_000
    const val MIN_PASSPHRASE_LENGTH = 10

    private val headerSize = MAGIC.size + 4 + SALT + IV

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            val raw = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(raw, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }

    fun encrypt(
        plain: ByteArray,
        passphrase: CharArray,
        iterations: Int = DEFAULT_ITERATIONS,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        if (passphrase.size < MIN_PASSPHRASE_LENGTH) throw MailFailure(FailureKind.BACKUP_NO_PASSPHRASE)
        val salt = ByteArray(SALT).also(random::nextBytes)
        val iv = ByteArray(IV).also(random::nextBytes)
        val prefix = ByteBuffer.allocate(MAGIC.size + 4 + SALT)
            .put(MAGIC).putInt(iterations).put(salt).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(prefix)
        val body = cipher.doFinal(plain)
        return ByteBuffer.allocate(headerSize + body.size).put(prefix).put(iv).put(body).array()
    }

    /** Wrong passphrase and a damaged/modified file are indistinguishable by design (GCM authentication). */
    fun decrypt(file: ByteArray, passphrase: CharArray): ByteArray {
        if (file.size < headerSize + 16 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw MailFailure(FailureKind.BACKUP_BAD_FILE)
        }
        val buf = ByteBuffer.wrap(file)
        buf.position(MAGIC.size)
        val iterations = buf.int
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw MailFailure(FailureKind.BACKUP_BAD_FILE)
        val salt = ByteArray(SALT).also(buf::get)
        val iv = ByteArray(IV).also(buf::get)
        val prefix = file.copyOfRange(0, MAGIC.size + 4 + SALT)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(prefix)
            cipher.doFinal(file, headerSize, file.size - headerSize)
        } catch (e: GeneralSecurityException) {
            throw MailFailure(FailureKind.BACKUP_WRONG_PASSPHRASE, cause = e)
        }
    }
}
