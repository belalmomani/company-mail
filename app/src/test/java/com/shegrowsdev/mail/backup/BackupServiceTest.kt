package com.shegrowsdev.mail.backup

import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.shegrowsdev.mail.admin.AdminDb
import com.shegrowsdev.mail.admin.AdminRepository
import com.shegrowsdev.mail.admin.AdminSnapshot
import com.shegrowsdev.mail.admin.AdminSnapshots
import com.shegrowsdev.mail.admin.Direction
import com.shegrowsdev.mail.admin.RefDraft
import com.shegrowsdev.mail.admin.TaskFilter
import com.shegrowsdev.mail.admin.Viewer
import com.shegrowsdev.mail.data.AccountRole
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MailFailure
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Real SQLite + real encryption: create -> verify -> restore, and every way it can go wrong. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupServiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var helper: AdminDb
    private lateinit var repo: AdminRepository
    private lateinit var dir: File
    private lateinit var service: BackupService
    private var now = 1_700_000_000_000L
    private var emailToId = mutableMapOf("heba@shegrowsdev.com" to "acc-heba", "info@shegrowsdev.com" to "acc-info")
    private val pass = "a long passphrase".toCharArray()

    private val heba = Viewer("acc-heba", "heba@shegrowsdev.com", AccountRole.EMPLOYEE)
    private val boss = Viewer("acc-info", "info@shegrowsdev.com", AccountRole.ADMIN)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(AdminDb.NAME)
        helper = AdminDb(context)
        repo = AdminRepository(helper) { now }
        dir = File(tmp.root, "backups")
        service = newService(dir)
    }

    @After fun tearDown() {
        helper.close()
    }

    private fun newService(d: File) = BackupService(
        dir = d,
        database = { helper.writableDatabase },
        accountEmails = { mapOf("acc-heba" to "heba@shegrowsdev.com", "acc-info" to "info@shegrowsdev.com") },
        emailToId = { emailToId },
        clock = { now++ },
        iterations = 1_000,
    )

    private fun draft(id: String, uid: Long, dir: Direction = Direction.INCOMING) = RefDraft(
        accountId = heba.accountId, accountEmail = heba.email, messageId = id, inReplyTo = null, references = null,
        folderRole = FolderRole.INBOX, uid = uid, subject = "Contract $uid", counterpart = "client@gmail.com",
        direction = dir, dateMillis = now, attachmentNames = listOf("a.pdf"), external = true,
    )

    private fun count(table: String): Long =
        helper.writableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { it.moveToFirst(); it.getLong(0) }

    private fun seed() = runBlocking {
        val t = repo.createTask(heba, draft("<a@x>", 1), "Reply to client", heba.email, now + 5_000)
        repo.register(heba, draft("<a@x>", 1), heba.email)
        repo.register(heba, draft("<b@x>", 2), heba.email)
        repo.createFollowUp(heba, draft("<c@x>", 3), now + 9_000, "chase", taskId = t)
        t
    }

    private fun kindOf(block: () -> Unit): FailureKind {
        try {
            block()
        } catch (e: MailFailure) {
            return e.kind
        }
        fail("expected a MailFailure")
        throw AssertionError()
    }

    // ------------------------------------------------------------ round trip

    @Test fun createVerifyRestoreBringsBackExactlyTheBackedUpData() = runBlocking<Unit> {
        seed()
        val before = listOf("transactions", "mail_refs", "tasks", "task_events", "register_entries", "follow_ups").associateWith(::count)
        val result = service.create(BackupKind.MANUAL, pass, boss)
        assertEquals(before.mapValues { it.value.toInt() }, result.counts)

        // changes made after the backup
        repo.createTask(heba, draft("<z@x>", 9), "Later", heba.email, null)
        repo.register(heba, draft("<z@x>", 9), heba.email)
        assertEquals(before.getValue("tasks") + 1, count("tasks"))

        val restored = service.restore(boss, service.read(boss, result.info), pass)
        assertEquals(before.mapValues { it.value.toInt() }, restored)
        for ((table, n) in before) assertEquals(table, n, count(table))
        assertEquals(1, repo.listTasks(heba, TaskFilter.ALL, now).size)
    }

    @Test fun restoreOnAnotherInstallWorksFromTheFileAlone() = runBlocking<Unit> {
        seed()
        val bytes = service.read(boss, service.create(BackupKind.MANUAL, pass, boss).info)
        // "another device": a service with its own empty folder gets only the exported bytes
        val other = newService(File(tmp.root, "other"))
        val counts = other.restore(boss, bytes, pass)
        assertEquals(2, counts.getValue("register_entries"))
    }

    @Test fun serialNumbersContinueAfterARestore() = runBlocking<Unit> {
        seed() // serials 1 and 2
        val info = service.create(BackupKind.MANUAL, pass, boss).info
        repo.register(heba, draft("<n3@x>", 5), heba.email) // 3, made after the backup
        service.restore(boss, service.read(boss, info), pass)
        val next = repo.register(heba, draft("<n4@x>", 6), heba.email).entry.seq
        assertEquals(3L, next) // continues from the backup's counter, never reusing 1 or 2
    }

    @Test fun registerProtectionIsBackAfterARestore() = runBlocking<Unit> {
        seed()
        val info = service.create(BackupKind.MANUAL, pass, boss).info
        service.restore(boss, service.read(boss, info), pass)
        val db = helper.writableDatabase
        try {
            db.execSQL("DELETE FROM register_entries")
            fail("delete should be refused")
        } catch (expected: SQLiteException) { }
        try {
            db.execSQL("UPDATE register_entries SET seq = seq + 100")
            fail("renumbering should be refused")
        } catch (expected: SQLiteException) { }
    }

    @Test fun rowsFollowTheirAccountWhenItWasAddedAgainWithANewId() = runBlocking<Unit> {
        seed()
        val info = service.create(BackupKind.MANUAL, pass, boss).info
        emailToId["heba@shegrowsdev.com"] = "acc-heba-NEW" // same mailbox, re-added on a new device
        service.restore(boss, service.read(boss, info), pass)
        val ids = helper.writableDatabase.rawQuery("SELECT DISTINCT account_id FROM tasks", null).use { c ->
            generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
        }
        assertEquals(listOf("acc-heba-NEW"), ids)
        val newViewer = Viewer("acc-heba-NEW", "heba@shegrowsdev.com", AccountRole.EMPLOYEE)
        assertEquals(1, repo.listTasks(newViewer, TaskFilter.ALL, now).size)
    }

    // ------------------------------------------------------------ bad input leaves data alone

    @Test fun wrongPassphraseChangesNothingAndMakesNoSafetyCopy() = runBlocking<Unit> {
        seed()
        val info = service.create(BackupKind.MANUAL, pass, boss).info
        repo.createTask(heba, draft("<z@x>", 9), "Later", heba.email, null)
        val tasks = count("tasks")
        val k = kindOf { service.restore(boss, service.read(boss, info), "not the passphrase".toCharArray()) }
        assertEquals(FailureKind.BACKUP_WRONG_PASSPHRASE, k)
        assertEquals(tasks, count("tasks"))
        assertTrue(service.list(boss).none { it.kind == BackupKind.SAFETY })
    }

    @Test fun aCorruptedFileChangesNothing() = runBlocking<Unit> {
        seed()
        val bytes = service.read(boss, service.create(BackupKind.MANUAL, pass, boss).info)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x55).toByte()
        val tasks = count("tasks")
        assertEquals(FailureKind.BACKUP_WRONG_PASSPHRASE, kindOf { service.restore(boss, bytes, pass) })
        assertEquals(FailureKind.BACKUP_BAD_FILE, kindOf { service.restore(boss, "garbage".toByteArray(), pass) })
        assertEquals(tasks, count("tasks"))
    }

    @Test fun aRestoreThatFailsHalfWayIsRolledBackCompletely() = runBlocking<Unit> {
        seed()
        val good = AdminSnapshots.export(helper.writableDatabase, mapOf("acc-heba" to "heba@shegrowsdev.com"), now)
        // a task that points at a message reference that does not exist: the database refuses it
        val badTasks = good.tables.getValue("tasks").map { it + ("ref_id" to 987_654L) }
        val bad = AdminSnapshot(good.format, good.createdAt, good.registerCounter, good.accountEmails,
            good.tables + ("tasks" to badTasks))
        val before = listOf("transactions", "mail_refs", "tasks", "register_entries", "follow_ups").associateWith(::count)
        try {
            AdminSnapshots.restore(helper.writableDatabase, bad, emailToId)
            fail("restore should fail")
        } catch (expected: SQLiteException) { }
        for ((t, n) in before) assertEquals(t, n, count(t)) // nothing was deleted or half-inserted
        try {
            helper.writableDatabase.execSQL("DELETE FROM register_entries")
            fail("register protection must still be in place after a failed restore")
        } catch (expected: SQLiteException) { }
    }

    @Test fun snapshotWithUnknownColumnsOrWrongCountsIsRejected() {
        val ok = AdminSnapshots.toJson(AdminSnapshots.export(helper.writableDatabase, emptyMap(), now))
        AdminSnapshots.fromJson(ok) // sanity: a valid one parses
        val badFormat = ok.replace("\"format\":1", "\"format\":99")
        assertEquals(FailureKind.BACKUP_BAD_FILE, kindOf { AdminSnapshots.fromJson(badFormat) })
        assertEquals(FailureKind.BACKUP_BAD_FILE, kindOf { AdminSnapshots.fromJson("{}") })
        assertEquals(FailureKind.BACKUP_BAD_FILE, kindOf { AdminSnapshots.fromJson("not json") })
    }

    // ------------------------------------------------------------ files, retention, permissions

    @Test fun retentionKeepsOnlyTheNewestOfEachKind() {
        repeat(12) { service.create(BackupKind.MANUAL, pass, boss) }
        repeat(9) { service.create(BackupKind.AUTO, pass, null, system = true) }
        val list = service.list(boss)
        assertEquals(BackupKind.MANUAL.keep, list.count { it.kind == BackupKind.MANUAL })
        assertEquals(BackupKind.AUTO.keep, list.count { it.kind == BackupKind.AUTO })
        assertEquals(list.sortedByDescending { it.createdAt }, list) // newest first
    }

    @Test fun aRestoreAlwaysLeavesASafetyCopyOfTheDataItReplaced() = runBlocking<Unit> {
        seed()
        val info = service.create(BackupKind.MANUAL, pass, boss).info
        service.restore(boss, service.read(boss, info), pass)
        assertEquals(1, service.list(boss).count { it.kind == BackupKind.SAFETY })
    }

    @Test fun incompleteFilesAreNeverListedAndAreCleanedUp() {
        dir.mkdirs()
        File(dir, "cmbk-manual-20240101-000000-000.cmbk.tmp").writeText("half written")
        File(dir, "random.txt").writeText("x")
        assertTrue(service.list(boss).isEmpty())
        service.create(BackupKind.MANUAL, pass, boss)
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".tmp") })
        assertEquals(1, service.list(boss).size)
    }

    @Test fun aWriteProblemIsReportedAndLeavesNoFile() {
        val notADirectory = File(tmp.root, "file-in-the-way").also { it.writeText("x") }
        val broken = newService(File(notADirectory, "backups"))
        assertEquals(FailureKind.BACKUP_WRITE, kindOf { broken.create(BackupKind.MANUAL, pass, boss) })
    }

    @Test fun shortPassphraseWritesNothing() {
        assertEquals(FailureKind.BACKUP_NO_PASSPHRASE, kindOf { service.create(BackupKind.MANUAL, "short".toCharArray(), boss) })
        assertTrue(!dir.exists() || dir.listFiles().isNullOrEmpty())
    }

    @Test fun onlyAdministratorsCanUseBackups() {
        assertEquals(FailureKind.NOT_PERMITTED, kindOf { service.create(BackupKind.MANUAL, pass, heba) })
        assertEquals(FailureKind.NOT_PERMITTED, kindOf { service.create(BackupKind.MANUAL, pass, null) })
        assertEquals(FailureKind.NOT_PERMITTED, kindOf { service.list(heba) })
        val info = service.create(BackupKind.MANUAL, pass, boss).info
        assertEquals(FailureKind.NOT_PERMITTED, kindOf { service.read(heba, info) })
        assertEquals(FailureKind.NOT_PERMITTED, kindOf { service.restore(heba, ByteArray(10), pass) })
        assertEquals(FailureKind.NOT_PERMITTED, kindOf { service.delete(heba, info) })
    }

    @Test fun fileNamesCannotEscapeTheBackupFolder() {
        File(tmp.root, "cmbk-manual-20240101-000000-000.cmbk").writeText("outside")
        val evil = BackupInfo("../cmbk-manual-20240101-000000-000.cmbk", BackupKind.MANUAL, 0, 0)
        assertEquals(FailureKind.BACKUP_BAD_FILE, kindOf { service.read(boss, evil) })
        service.delete(boss, evil)
        assertTrue(File(tmp.root, "cmbk-manual-20240101-000000-000.cmbk").exists())
    }

    @Test fun backupFileHoldsNoClearTextOfTheData() = runBlocking<Unit> {
        seed()
        val bytes = service.read(boss, service.create(BackupKind.MANUAL, pass, boss).info)
        val text = String(bytes, Charsets.ISO_8859_1)
        assertFalse(text.contains("Reply to client"))
        assertFalse(text.contains("shegrowsdev"))
    }
}
