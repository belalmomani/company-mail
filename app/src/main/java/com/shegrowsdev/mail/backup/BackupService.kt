package com.shegrowsdev.mail.backup

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.shegrowsdev.mail.admin.AdminSnapshot
import com.shegrowsdev.mail.admin.AdminSnapshots
import com.shegrowsdev.mail.admin.Permissions
import com.shegrowsdev.mail.admin.Viewer
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.MailFailure
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

enum class BackupKind(val tag: String, val keep: Int) {
    /** Made by the daily background job. */
    AUTO("auto", 7),
    /** Made by the administrator with "Back up now". */
    MANUAL("manual", 10),
    /** Made automatically right before a restore, so a restore can itself be undone. */
    SAFETY("safety", 3),
}

data class BackupInfo(val name: String, val kind: BackupKind, val createdAt: Long, val sizeBytes: Long)

data class BackupResult(val info: BackupInfo, val counts: Map<String, Int>)

/**
 * Encrypted backup and restore of the ADMINISTRATIVE data only (tasks, official register, follow-ups and the
 * message references they use). Mail itself stays on the mail server; passwords and account settings are
 * never written to a backup.
 *
 * Every file is written to a temporary name, read back and fully verified (decrypt, decompress, parse, counts)
 * and only then renamed into place, so a half-written or unreadable backup is never listed or counted.
 */
class BackupService(
    private val dir: File,
    private val database: () -> SQLiteDatabase,
    /** account id -> e-mail of every account on this device. */
    private val accountEmails: () -> Map<String, String>,
    /** e-mail (lower case) -> account id, used to reconnect restored rows to accounts that were added again. */
    private val emailToId: () -> Map<String, String>,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val iterations: Int = BackupCrypto.DEFAULT_ITERATIONS,
) {
    private fun requireAdmin(actor: Viewer?) {
        if (!Permissions.canManageBackups(actor)) throw MailFailure(FailureKind.NOT_PERMITTED)
    }

    private fun stamp(millis: Long): String =
        SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun parse(file: File): BackupInfo? {
        val m = NAME.matchEntire(file.name) ?: return null
        val kind = BackupKind.values().firstOrNull { it.tag == m.groupValues[1] } ?: return null
        val at: Long = try {
            SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(m.groupValues[2])?.time
        } catch (e: java.text.ParseException) {
            null
        } ?: return null
        return BackupInfo(file.name, kind, at, file.length())
    }

    /** Verified backups, newest first. Temporary and unrecognised files are ignored. */
    fun list(actor: Viewer?): List<BackupInfo> {
        requireAdmin(actor)
        return listInternal()
    }

    private fun listInternal(): List<BackupInfo> =
        (dir.listFiles() ?: emptyArray()).mapNotNull(::parse).sortedByDescending { it.createdAt }

    /**
     * Creates, verifies and stores a backup. [actor] is null only for the background job, which runs for an
     * administrator who switched automatic backups on (checked by the caller).
     */
    fun create(kind: BackupKind, passphrase: CharArray, actor: Viewer?, system: Boolean = false): BackupResult {
        if (!system) requireAdmin(actor)
        return createInternal(kind, passphrase)
    }

    private fun createInternal(kind: BackupKind, passphrase: CharArray): BackupResult {
        val now = clock()
        val snapshot = AdminSnapshots.export(database(), accountEmails(), now)
        val json = AdminSnapshots.toJson(snapshot).toByteArray(Charsets.UTF_8)
        val encrypted = BackupCrypto.encrypt(BackupCrypto.gzip(json), passphrase, iterations)

        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create backup directory")
            cleanTemporary()
            val name = "$PREFIX${kind.tag}-${stamp(now)}$SUFFIX"
            val tmp = File(dir, "$name$TMP")
            val target = File(dir, name)
            try {
                tmp.outputStream().use { it.write(encrypted); it.fd.sync() }
                // Read back what is really on disk, not what we think we wrote.
                val back = verify(tmp.readBytes(), passphrase)
                if (back.counts() != snapshot.counts() || back.registerCounter != snapshot.registerCounter) {
                    throw MailFailure(FailureKind.BACKUP_WRITE, detail = "verify")
                }
                if (!tmp.renameTo(target)) throw IOException("rename failed")
            } finally {
                if (tmp.exists()) tmp.delete()
            }
            prune(kind)
            return BackupResult(BackupInfo(name, kind, now, target.length()), snapshot.counts())
        } catch (e: MailFailure) {
            throw e
        } catch (e: IOException) {
            throw MailFailure(FailureKind.BACKUP_WRITE, cause = e)
        } catch (e: SecurityException) {
            throw MailFailure(FailureKind.BACKUP_WRITE, cause = e)
        }
    }

    /** Decrypts and fully parses a backup without touching any data. */
    fun verify(file: ByteArray, passphrase: CharArray): AdminSnapshot {
        val plain = BackupCrypto.decrypt(file, passphrase)
        val json = try {
            BackupCrypto.gunzip(plain).toString(Charsets.UTF_8)
        } catch (e: IOException) {
            throw MailFailure(FailureKind.BACKUP_BAD_FILE, cause = e)
        }
        return AdminSnapshots.fromJson(json)
    }

    fun read(actor: Viewer?, info: BackupInfo): ByteArray {
        requireAdmin(actor)
        val f = File(dir, info.name)
        // Only files this service lists are readable (no path tricks through the name).
        if (parse(f) == null || f.parentFile?.canonicalPath != dir.canonicalPath || !f.isFile) {
            throw MailFailure(FailureKind.BACKUP_BAD_FILE)
        }
        return try {
            f.readBytes()
        } catch (e: IOException) {
            throw MailFailure(FailureKind.BACKUP_BAD_FILE, cause = e)
        }
    }

    /**
     * Replaces the administrative data with the backup's. The file is verified first, a safety backup of the
     * current data is made, and the replacement itself is one database transaction: any failure leaves the
     * current data exactly as it was.
     */
    fun restore(actor: Viewer?, file: ByteArray, passphrase: CharArray): Map<String, Int> {
        requireAdmin(actor)
        val snapshot = verify(file, passphrase)
        createInternal(BackupKind.SAFETY, passphrase)
        try {
            AdminSnapshots.restore(database(), snapshot, emailToId())
        } catch (e: MailFailure) {
            throw e
        } catch (e: SQLiteException) {
            throw MailFailure(FailureKind.BACKUP_RESTORE, cause = e)
        }
        return snapshot.counts()
    }

    fun delete(actor: Viewer?, info: BackupInfo) {
        requireAdmin(actor)
        val f = File(dir, info.name)
        if (parse(f) != null && f.parentFile?.canonicalPath == dir.canonicalPath) f.delete()
    }

    private fun prune(kind: BackupKind) {
        listInternal().filter { it.kind == kind }.drop(kind.keep).forEach { File(dir, it.name).delete() }
    }

    private fun cleanTemporary() {
        (dir.listFiles() ?: return).filter { it.name.endsWith(TMP) }.forEach { it.delete() }
    }

    companion object {
        const val PREFIX = "cmbk-"
        const val SUFFIX = ".cmbk"
        private const val TMP = ".tmp"
        private val NAME = Regex("""cmbk-(auto|manual|safety)-(\d{8}-\d{6}-\d{3})\.cmbk""")
    }
}
