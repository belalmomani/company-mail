package com.shegrowsdev.mail.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationsTest {
    private fun m(uid: Long, subject: String, date: Long?, seen: Boolean = true) =
        MessageSummary(uid, subject, MailAddress(null, "a@b.com"), emptyList(), date, seen, false)

    @Test fun normalizesReplyAndForwardPrefixesInBothLanguages() {
        assertEquals("تقرير", Conversations.normalizeSubject("رد: Re: تقرير"))
        assertEquals("budget", Conversations.normalizeSubject("  FWD: RE: Budget "))
        assertEquals("budget 2026", Conversations.normalizeSubject("Re[2]: Budget   2026"))
        assertEquals("إعادة", Conversations.normalizeSubject("إعادة توجيه: إعادة"))
    }

    @Test fun sortModes() {
        val items = listOf(m(1, "a", 100, seen = false), m(2, "b", 300), m(3, "c", 200, seen = false))
        assertEquals(listOf(2L, 3L, 1L), Conversations.sort(items, SortMode.NEWEST_FIRST).map { it.uid })
        assertEquals(listOf(1L, 3L, 2L), Conversations.sort(items, SortMode.OLDEST_FIRST).map { it.uid })
        assertEquals(listOf(3L, 1L, 2L), Conversations.sort(items, SortMode.UNREAD_FIRST).map { it.uid })
    }

    @Test fun missingDatesSortLastWhenNewestFirst() {
        val items = listOf(m(1, "a", null), m(2, "b", 5))
        assertEquals(listOf(2L, 1L), Conversations.sort(items, SortMode.NEWEST_FIRST).map { it.uid })
    }

    @Test fun groupsBySubjectAndKeepsLatest() {
        val items = listOf(
            m(5, "Re: Project", 500, seen = false), m(4, "Other", 400),
            m(3, "رد: Project", 300), m(2, "Project", 200, seen = false)
        )
        val rows = Conversations.group(items)
        assertEquals(2, rows.size)
        assertEquals(5L, rows[0].latest.uid); assertEquals(3, rows[0].count); assertEquals(2, rows[0].unread)
        assertEquals(4L, rows[1].latest.uid); assertEquals(1, rows[1].count)
    }

    @Test fun messagesWithoutSubjectAreNeverMerged() {
        val rows = Conversations.group(listOf(m(1, "", 1), m(2, "  ", 2), m(3, "Re:", 3)))
        assertEquals(3, rows.size)
    }

    @Test fun singleRowsCountUnread() {
        val rows = Conversations.single(listOf(m(1, "x", 1, seen = false), m(2, "y", 2)))
        assertEquals(listOf(1, 0), rows.map { it.unread })
    }

    @Test fun deviceSearchMatchesSubjectSenderAndRecipients() {
        val msg = MessageSummary(
            1, "عرض سعر", MailAddress("هبة", "heba@shegrowsdev.com"),
            listOf(MailAddress(null, "client@example.com")), 1, true, false
        )
        assertEquals(true, SearchMatch.matches(msg, "سعر"))
        assertEquals(true, SearchMatch.matches(msg, "HEBA@"))
        assertEquals(true, SearchMatch.matches(msg, "هبة"))
        assertEquals(true, SearchMatch.matches(msg, "client"))
        assertEquals(false, SearchMatch.matches(msg, "invoice"))
        assertEquals(false, SearchMatch.matches(msg, "  "))
    }
}
