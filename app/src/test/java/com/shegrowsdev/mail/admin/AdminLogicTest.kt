package com.shegrowsdev.mail.admin

import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.data.AccountRole
import com.shegrowsdev.mail.mail.AttachmentInfo
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MailAddress
import com.shegrowsdev.mail.mail.MessageDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class AdminLogicTest {

    // ---------------------------------------------------------------- response time

    private fun ref(
        id: Long, dir: Direction, at: Long?, msgId: String? = "<m$id@x>", replyTo: String? = null,
        tx: Long = 1, account: String = "a", external: Boolean = true,
    ) = StatRef(id, tx, account, dir, msgId, replyTo, at, external)

    @Test fun directReplyWinsOverEarlierGenericReply() {
        val refs = listOf(
            ref(1, Direction.INCOMING, 1_000),
            ref(2, Direction.OUTGOING, 5_000),
            ref(3, Direction.OUTGOING, 9_000, replyTo = "<M1@x>"),
        )
        val samples = Stats.responseSamples(refs, 0, 100_000)
        assertEquals(1, samples.size)
        assertEquals(8_000L, samples[0].millis)
    }

    @Test fun firstLaterReplyIsUsedWhenNoneAnswersDirectly() {
        val refs = listOf(
            ref(1, Direction.INCOMING, 1_000),
            ref(2, Direction.OUTGOING, 7_000),
            ref(3, Direction.OUTGOING, 5_000),
        )
        assertEquals(4_000L, Stats.responseSamples(refs, 0, 100_000).single().millis)
    }

    @Test fun unansweredMessagesAreNotCounted() {
        val refs = listOf(ref(1, Direction.INCOMING, 1_000))
        val summary = Stats.summarize(Stats.responseSamples(refs, 0, 100_000))
        assertEquals(0, summary.overall.count)
        assertNull(summary.overall.averageMillis)
    }

    @Test fun repliesBeforeTheMessageAndOtherAccountsDoNotCount() {
        val refs = listOf(
            ref(1, Direction.INCOMING, 5_000),
            ref(2, Direction.OUTGOING, 2_000),
            ref(3, Direction.OUTGOING, 9_000, account = "b"),
        )
        assertTrue(Stats.responseSamples(refs, 0, 100_000).isEmpty())
    }

    @Test fun periodFiltersByReceiveTimeAndAveragesAreSplitByOrigin() {
        val refs = listOf(
            ref(1, Direction.INCOMING, 1_000, external = true, tx = 1),
            ref(2, Direction.OUTGOING, 3_000, tx = 1),
            ref(3, Direction.INCOMING, 10_000, external = false, tx = 2),
            ref(4, Direction.OUTGOING, 16_000, tx = 2),
            ref(5, Direction.INCOMING, 500_000, tx = 3),
            ref(6, Direction.OUTGOING, 600_000, tx = 3),
        )
        val summary = Stats.summarize(Stats.responseSamples(refs, 0, 100_000))
        assertEquals(2, summary.overall.count)
        assertEquals(4_000L, summary.overall.averageMillis)
        assertEquals(2_000L, summary.external.averageMillis)
        assertEquals(6_000L, summary.internal.averageMillis)
    }

    // ---------------------------------------------------------------- transactions

    @Test fun overdueNeedsADueTimeInThePast() {
        val now = 10_000L
        assertFalse(Stats.isOverdue(OpenItem(1, listOf(null), emptyList()), now))
        assertTrue(Stats.isOverdue(OpenItem(1, listOf(5_000L), emptyList()), now))
        assertTrue(Stats.isOverdue(OpenItem(1, emptyList(), listOf(9_999L)), now))
        assertFalse(Stats.isOverdue(OpenItem(1, emptyList(), listOf(10_000L)), now))
    }

    @Test fun transactionCountsCountEachTransactionOnce() {
        val counts = Stats.transactionCounts(
            listOf(
                OpenItem(1, listOf(1L, 2L), listOf(3L)),  // many items, still one transaction
                OpenItem(2, listOf(null), emptyList()),   // open, never overdue
                OpenItem(3, emptyList(), emptyList()),    // nothing keeps it open
            ),
            now = 100L
        )
        assertEquals(2, counts.open)
        assertEquals(1, counts.overdue)
    }

    // ---------------------------------------------------------------- reminders / serials / periods / durations

    @Test fun reminderStages() {
        assertEquals(0, ReminderRules.stageFor(dueAt = 1000, now = 800, leadMillis = 100))
        assertEquals(1, ReminderRules.stageFor(dueAt = 1000, now = 950, leadMillis = 100))
        assertEquals(2, ReminderRules.stageFor(dueAt = 1000, now = 1000, leadMillis = 100))
        assertTrue(ReminderRules.shouldNotify(1, 0))
        assertFalse(ReminderRules.shouldNotify(1, 1))
        assertTrue(ReminderRules.shouldNotify(2, 1))
        assertFalse(ReminderRules.shouldNotify(2, 2))
    }

    @Test fun serialNumbersArePadded() {
        assertEquals("000007", SerialFormat.format(7))
        assertEquals("1234567", SerialFormat.format(1_234_567))
    }

    @Test fun periods() {
        val today = LocalDate.of(2026, 10, 29)
        val p7 = Periods.preset(PeriodPreset.LAST_7_DAYS, today, ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 23), p7.firstDay)
        assertEquals(today, p7.lastDay)
        assertEquals(LocalDate.of(2026, 10, 30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), p7.toExclusiveMillis)

        val last = Periods.preset(PeriodPreset.LAST_MONTH, today, ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 9, 1), last.firstDay)
        assertEquals(LocalDate.of(2026, 9, 30), last.lastDay)

        val swapped = Periods.of(today, today.minusDays(3), ZoneOffset.UTC)
        assertEquals(today.minusDays(3), swapped.firstDay)
    }

    @Test fun durations() {
        val p = Durations.parts(90_061_000)
        assertEquals(1L, p.days); assertEquals(1L, p.hours); assertEquals(1L, p.minutes)
        assertEquals(0L, Durations.parts(-5).minutes)
    }

    // ---------------------------------------------------------------- permissions

    private fun account(id: String, email: String, role: AccountRole = AccountRole.EMPLOYEE) =
        Account(id, email, email, "h", 993, "h", 465, role = role)

    @Test fun employeeSeesOnlyOwnAccountAndAssignedRows() {
        val all = listOf(account("1", "a@x.com"), account("2", "b@x.com"), account("3", "c@x.com", AccountRole.ADMIN))
        val employee = Viewer("1", "a@x.com", AccountRole.EMPLOYEE)
        assertEquals(listOf("1"), Permissions.visibleAccounts(employee, all).map { it.id })
        assertFalse(Permissions.canSeeAccount(employee, "2"))
        assertTrue(Permissions.canSeeRow(employee, "2", "A@X.com"))
        assertFalse(Permissions.canSeeRow(employee, "2", "b@x.com"))

        val admin = Viewer("3", "c@x.com", AccountRole.ADMIN)
        assertEquals(3, Permissions.visibleAccounts(admin, all).size)
        assertTrue(Permissions.canSeeAccount(admin, "2"))
    }

    // ---------------------------------------------------------------- thread linking / drafts

    @Test fun messageIdsAreNormalised() {
        assertEquals("<abc@x>", ThreadLinker.normalizeId("  <ABC@x> "))
        assertNull(ThreadLinker.normalizeId(null))
        assertEquals(listOf("<a@x>", "<b@x>"), ThreadLinker.relatedIds("<B@x>", "<A@x>", "<a@x>"))
    }

    @Test fun threadKeyUsesRootIdOtherwiseSubject() {
        assertEquals("id:<root@x>", ThreadLinker.threadKey("<m@x>", "<p@x>", "<root@x> <p@x>", "S"))
        assertEquals(ThreadLinker.threadKey(null, null, null, "Re: Hello"), ThreadLinker.threadKey(null, null, null, "Hello"))
        assertNotEquals(ThreadLinker.threadKey(null, null, null, "Hello"), ThreadLinker.threadKey(null, null, null, "Other"))
    }

    @Test fun externalMeansDomainOutsideTheCompanyAccounts() {
        val domains = setOf("shegrowsdev.com")
        assertFalse(ThreadLinker.isExternal("heba@SheGrowsDev.com", domains))
        assertTrue(ThreadLinker.isExternal("x@gmail.com", domains))
        assertTrue(ThreadLinker.isExternal("", domains))
    }

    @Test fun incomingAndOutgoingDraftsPickTheRightCounterpart() {
        val detail = MessageDetail(
            uid = 5, messageId = "<m@x>", inReplyTo = null, references = null, subject = "S",
            from = MailAddress("Ann", "ann@gmail.com"), replyTo = emptyList(),
            to = listOf(MailAddress(null, "heba@shegrowsdev.com")), cc = emptyList(), bcc = emptyList(),
            dateMillis = 1L, bodyText = "", bodyFromHtml = false,
            attachments = listOf(AttachmentInfo(0, "a.pdf", "application/pdf", 10, true)),
        )
        val domains = setOf("shegrowsdev.com")
        val inc = RefDrafts.fromDetail("1", "heba@shegrowsdev.com", FolderRole.INBOX, detail, domains)
        assertEquals(Direction.INCOMING, inc.direction)
        assertEquals("ann@gmail.com", inc.counterpart)
        assertTrue(inc.external)
        assertEquals(listOf("a.pdf"), inc.attachmentNames)

        val out = RefDrafts.fromDetail("1", "heba@shegrowsdev.com", FolderRole.SENT, detail, domains)
        assertEquals(Direction.OUTGOING, out.direction)
        assertEquals("heba@shegrowsdev.com", out.counterpart)
        assertFalse(out.external)
    }
}
