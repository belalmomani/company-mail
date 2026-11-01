package com.shegrowsdev.mail.backup

import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.MailFailure
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupCryptoTest {
    private val pass = "correct horse battery".toCharArray()
    private val fast = 1_000 // few iterations keep the tests quick; the real default is 310,000
    private val plain = "{\"secret\":\"مهمة سرية\"}".toByteArray(Charsets.UTF_8)

    private fun kindOf(block: () -> Unit): FailureKind {
        try {
            block()
        } catch (e: MailFailure) {
            return e.kind
        }
        fail("expected a MailFailure")
        throw AssertionError()
    }

    @Test fun defaultStrengthIsAtLeastOwaspLevel() {
        assertTrue(BackupCrypto.DEFAULT_ITERATIONS >= 310_000)
    }

    @Test fun roundTrip() {
        val file = BackupCrypto.encrypt(plain, pass, fast)
        assertArrayEquals(plain, BackupCrypto.decrypt(file, pass))
    }

    @Test fun gzipRoundTrip() {
        val data = ByteArray(5000) { (it % 7).toByte() }
        assertArrayEquals(data, BackupCrypto.gunzip(BackupCrypto.gzip(data)))
    }

    @Test fun fileDoesNotContainThePlainText() {
        val file = BackupCrypto.encrypt(plain, pass, fast)
        assertFalse(String(file, Charsets.ISO_8859_1).contains("secret"))
    }

    @Test fun everyFileGetsItsOwnSaltAndIv() {
        assertFalse(BackupCrypto.encrypt(plain, pass, fast).contentEquals(BackupCrypto.encrypt(plain, pass, fast)))
    }

    @Test fun wrongPassphraseIsRejected() {
        val file = BackupCrypto.encrypt(plain, pass, fast)
        assertEquals(
            FailureKind.BACKUP_WRONG_PASSPHRASE,
            kindOf { BackupCrypto.decrypt(file, "another passphrase".toCharArray()) }
        )
    }

    @Test fun anyModifiedByteIsDetected() {
        val file = BackupCrypto.encrypt(plain, pass, fast)
        // header (salt, iv), body and tag: flipping one bit anywhere must never decrypt
        for (index in listOf(9, 12, 20, 30, 40, file.size - 1)) {
            val copy = file.copyOf()
            copy[index] = (copy[index].toInt() xor 1).toByte()
            val kind = kindOf { BackupCrypto.decrypt(copy, pass) }
            assertTrue("byte $index: $kind", kind == FailureKind.BACKUP_WRONG_PASSPHRASE || kind == FailureKind.BACKUP_BAD_FILE)
        }
    }

    @Test fun notABackupIsABadFile() {
        assertEquals(FailureKind.BACKUP_BAD_FILE, kindOf { BackupCrypto.decrypt(ByteArray(0), pass) })
        assertEquals(FailureKind.BACKUP_BAD_FILE, kindOf { BackupCrypto.decrypt("hello world, not a backup at all, really".toByteArray(), pass) })
        val file = BackupCrypto.encrypt(plain, pass, fast)
        assertEquals(FailureKind.BACKUP_BAD_FILE, kindOf { BackupCrypto.decrypt(file.copyOf(20), pass) })
    }

    @Test fun truncatedBodyFailsAuthentication() {
        val file = BackupCrypto.encrypt(plain, pass, fast)
        val kind = kindOf { BackupCrypto.decrypt(file.copyOf(file.size - 3), pass) }
        assertEquals(FailureKind.BACKUP_WRONG_PASSPHRASE, kind)
    }

    @Test fun shortPassphraseIsRefusedBeforeAnythingIsWritten() {
        assertEquals(FailureKind.BACKUP_NO_PASSPHRASE, kindOf { BackupCrypto.encrypt(plain, "short".toCharArray(), fast) })
        assertEquals(FailureKind.BACKUP_NO_PASSPHRASE, kindOf { BackupCrypto.encrypt(plain, CharArray(0), fast) })
    }
}
