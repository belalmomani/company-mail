package com.shegrowsdev.mail.admin

import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import com.shegrowsdev.mail.data.AccountRole
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MailFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real SQLite schema and queries (Robolectric) to check the relations between messages, tasks,
 * register entries and follow-ups, the serial numbers and the permission scoping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AdminRepositoryTest {

    private lateinit var helper: AdminDb
    private lateinit var repo: AdminRepository
    private var now = 1_000_000L

    private val heba = Viewer("acc-heba", "heba@shegrowsdev.com", AccountRole.EMPLOYEE)
    private val lilian = Viewer("acc-lilian", "lilian@shegrowsdev.com", AccountRole.EMPLOYEE)
    private val boss = Viewer("acc-info", "info@shegrowsdev.com", AccountRole.ADMIN)

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(AdminDb.NAME)
        helper = AdminDb(context)
        repo = AdminRepository(helper) { now }
    }

    @After fun tearDown() {
        helper.close()
    }

    private fun draft(
        viewer: Viewer, id: String?, dir: Direction = Direction.INCOMING, inReplyTo: String? = null,
        subject: String = "Contract", uid: Long = 1, date: Long = now,
    ) = RefDraft(
        accountId = viewer.accountId, accountEmail = viewer.email, messageId = id, inReplyTo = inReplyTo,
        references = inReplyTo, folderRole = if (dir == Direction.INCOMING) FolderRole.INBOX else FolderRole.SENT,
        uid = uid, subject = subject, counterpart = "client@gmail.com", direction = dir, dateMillis = date,
        attachmentNames = listOf("a.pdf"), external = true,
    )

    // ---------------------------------------------------------------- register

    @Test fun registeringTheSameMessageTwiceKeepsOneSerialNumber() = runBlocking<Unit> {
        val d = draft(heba, "<m1@x>")
        val first = repo.register(heba, d, heba.email)
        val again = repo.register(heba, d, heba.email)
        assertTrue(first.created)
        assertFalse(again.created)
        assertEquals(first.entry.seq, again.entry.seq)
    }

    @Test fun serialNumbersAreSequentialAndSeparateFromMessageIds() = runBlocking<Unit> {
        val a = repo.register(heba, draft(heba, "<a@x>"), heba.email).entry
        val b = repo.register(heba, draft(heba, "<b@x>", uid = 2), heba.email).entry
        assertEquals(a.seq + 1, b.seq)
        val row = repo.listRegister(heba, "", null).first { it.entry.seq == a.seq }
        assertEquals("<a@x>", row.ref.messageId) // the mail Message-ID is stored apart from the serial
    }

    @Test fun parallelRegistrationNeverRepeatsANumber() = runBlocking<Unit> {
        val results = (1..20).map { i ->
            async(Dispatchers.IO) { repo.register(heba, draft(heba, "<p$i@x>", uid = i.toLong()), heba.email).entry.seq }
        }.awaitAll()
        assertEquals(20, results.toSet().size)
        assertEquals((1L..20L).toSet(), results.toSet())
    }

    @Test fun registerEntriesCannotBeDeletedOrRenumbered() = runBlocking<Unit> {
        val e = repo.register(heba, draft(heba, "<m@x>"), heba.email).entry
        val db = helper.writableDatabase
        try {
            db.execSQL("DELETE FROM register_entries WHERE id = ${e.id}")
            fail("delete should be refused")
        } catch (expected: SQLiteException) { }
        try {
            db.execSQL("UPDATE register_entries SET seq = 99 WHERE id = ${e.id}")
            fail("renumbering should be refused")
        } catch (expected: SQLiteException) { }
    }

    // ---------------------------------------------------------------- tasks

    @Test fun editingATaskNeverDetachesItFromItsMessage() = runBlocking<Unit> {
        val id = repo.createTask(heba, draft(heba, "<t@x>"), "Reply to client", heba.email, now + 5)
        val before = repo.task(heba, id)!!
        now += 10
        val after = repo.updateTask(
            heba, id,
            TaskPatch(title = "New title", assigneeEmail = "lilian@shegrowsdev.com", setDue = true, dueAt = null, status = TaskStatus.DONE)
        )
        assertEquals(before.ref.id, after.ref.id)
        assertEquals("<t@x>", after.ref.messageId)
        assertEquals("New title", after.task.title)
        assertEquals(TaskStatus.DONE, after.task.status)
        assertEquals(null, after.task.dueAt)
        val kinds = repo.taskEvents(heba, id).map { it.kind }
        assertEquals(listOf("CREATED", "TITLE", "ASSIGNEE", "DUE", "STATUS"), kinds)
        try {
            helper.writableDatabase.execSQL("UPDATE tasks SET ref_id = 999 WHERE id = $id")
            fail("moving a task to another message should be refused")
        } catch (expected: SQLiteException) { }
    }

    @Test fun twoTasksOnOneMessageShareOneReference() = runBlocking<Unit> {
        val d = draft(heba, "<s@x>")
        val t1 = repo.createTask(heba, d, "One", heba.email, null)
        val t2 = repo.createTask(heba, d, "Two", heba.email, null)
        assertEquals(repo.task(heba, t1)!!.ref.id, repo.task(heba, t2)!!.ref.id)
        val link = repo.linksFor(heba, heba.accountId, "<S@x>", Direction.INCOMING)
        assertEquals(2, link.tasks)
    }

    @Test fun overdueFilterNeedsADueDateInThePast() = runBlocking<Unit> {
        repo.createTask(heba, draft(heba, "<o1@x>"), "late", heba.email, now - 1)
        repo.createTask(heba, draft(heba, "<o2@x>", uid = 2), "future", heba.email, now + 1_000)
        repo.createTask(heba, draft(heba, "<o3@x>", uid = 3), "undated", heba.email, null)
        val overdue = repo.listTasks(heba, TaskFilter.OVERDUE, now)
        assertEquals(listOf("late"), overdue.map { it.task.title })
    }

    // ---------------------------------------------------------------- permissions

    @Test fun anEmployeeNeverSeesOrChangesAnotherAccountsData() = runBlocking<Unit> {
        val id = repo.createTask(lilian, draft(lilian, "<l@x>"), "Private", lilian.email, null)
        assertTrue(repo.listTasks(heba, TaskFilter.ALL, now).isEmpty())
        assertEquals(null, repo.task(heba, id))
        try {
            repo.updateTask(heba, id, TaskPatch(title = "hack"))
            fail("should be refused")
        } catch (e: MailFailure) {
            assertEquals(FailureKind.NOT_PERMITTED, e.kind)
        }
        try {
            repo.createTask(heba, draft(lilian, "<x@x>", uid = 9), "forged", heba.email, null)
            fail("should be refused")
        } catch (e: MailFailure) {
            assertEquals(FailureKind.NOT_PERMITTED, e.kind)
        }
        assertEquals(1, repo.listTasks(boss, TaskFilter.ALL, now).size)
    }

    @Test fun aTaskAssignedToAnEmployeeIsVisibleToThem() = runBlocking<Unit> {
        val id = repo.createTask(lilian, draft(lilian, "<as@x>"), "Shared", heba.email, null)
        assertNotNull(repo.task(heba, id))
    }

    @Test fun statisticsInputsAreScopedToTheViewer() = runBlocking<Unit> {
        repo.register(heba, draft(heba, "<h@x>"), heba.email)
        repo.register(lilian, draft(lilian, "<li@x>", uid = 2), lilian.email)
        assertEquals(1, repo.statRefs(heba, null).size)
        // an employee cannot widen the scope by asking for another account
        assertEquals(1, repo.statRefs(heba, "acc-lilian").size)
        assertEquals(2, repo.statRefs(boss, null).size)
        assertEquals(1, repo.statRefs(boss, "acc-lilian").size)
    }

    // ---------------------------------------------------------------- conversations and follow-ups

    @Test fun aReplyJoinsTheTransactionOfTheMessageItAnswers() = runBlocking<Unit> {
        val original = draft(heba, "<q@x>", date = 1_000)
        repo.recordSent(
            heba, original, draft(heba, "<r@x>", Direction.OUTGOING, inReplyTo = "<q@x>", date = 5_000, uid = -1),
            register = false, responsibleEmail = heba.email
        )
        val refs = repo.statRefs(heba, null)
        assertEquals(2, refs.size)
        assertEquals(1, refs.map { it.transactionId }.toSet().size)
        val samples = Stats.responseSamples(refs, 0, 10_000)
        assertEquals(4_000L, samples.single().millis)
    }

    @Test fun sendingAReplyClosesThePendingFollowUpOfTheOriginal() = runBlocking<Unit> {
        val original = draft(heba, "<f@x>")
        repo.createFollowUp(heba, original, now + 1_000, "chase")
        assertEquals(1, repo.listFollowUps(heba, FollowUpSection.UPCOMING, now).size)
        repo.recordSent(
            heba, original, draft(heba, "<fr@x>", Direction.OUTGOING, inReplyTo = "<f@x>", uid = -1),
            register = true, responsibleEmail = heba.email
        )
        assertTrue(repo.listFollowUps(heba, FollowUpSection.UPCOMING, now).isEmpty())
        assertEquals(FollowUpState.DONE, repo.listFollowUps(heba, FollowUpSection.CLOSED, now).single().followUp.state)
        assertEquals(1, repo.listRegister(heba, "", Direction.OUTGOING).size)
    }

    @Test fun remindersAreOfferedOncePerStage() = runBlocking<Unit> {
        repo.createFollowUp(heba, draft(heba, "<n@x>"), now + 60_000, "")
        val lead = 3_600_000L
        val first = repo.followUpsToNotify(now, lead)
        assertEquals(1, first.size)
        assertEquals(1, first[0].second)
        repo.markNotified(listOf(first[0].first.followUp.id), 1)
        assertTrue(repo.followUpsToNotify(now, lead).isEmpty())
        now += 120_000
        val second = repo.followUpsToNotify(now, lead)
        assertEquals(2, second.single().second)
    }

    @Test fun movingAMessageIsFollowedByItsMessageId() = runBlocking<Unit> {
        val id = repo.createTask(heba, draft(heba, "<mv@x>"), "t", heba.email, null)
        val ref = repo.task(heba, id)!!.ref
        repo.updateLocation(ref.id, FolderRole.ARCHIVE, 77)
        val moved = repo.task(heba, id)!!.ref
        assertEquals(FolderRole.ARCHIVE, moved.folderRole)
        assertEquals(77L, moved.uid)
        assertEquals("<mv@x>", moved.messageId)
    }
}
