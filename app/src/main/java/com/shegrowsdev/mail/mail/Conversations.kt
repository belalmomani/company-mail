package com.shegrowsdev.mail.mail

/** How the message list is ordered. */
enum class SortMode { NEWEST_FIRST, OLDEST_FIRST, UNREAD_FIRST }

/** One row of the list: a single message, or several messages that share a subject. */
data class ConversationRow(
    val latest: MessageSummary,
    val count: Int,
    val unread: Int,
)

/** Sorting and subject-based grouping of already loaded messages. Pure Kotlin (testable). */
object Conversations {
    private val prefix = Regex("^\\s*(re|fwd?|aw|sv|رد|ردا|إعادة توجيه|اعادة توجيه|تحويل)\\s*(\\[\\d+\\])?\\s*:\\s*", RegexOption.IGNORE_CASE)

    fun normalizeSubject(subject: String): String {
        var s = subject.trim()
        while (true) {
            val next = prefix.replaceFirst(s, "")
            if (next == s) break
            s = next
        }
        return s.replace(Regex("\\s+"), " ").trim().lowercase()
    }

    private fun timeOf(m: MessageSummary): Long = m.dateMillis ?: Long.MIN_VALUE

    fun sort(items: List<MessageSummary>, mode: SortMode): List<MessageSummary> {
        val newest = compareByDescending<MessageSummary> { timeOf(it) }.thenByDescending { it.uid }
        return when (mode) {
            SortMode.NEWEST_FIRST -> items.sortedWith(newest)
            SortMode.OLDEST_FIRST -> items.sortedWith(newest.reversed())
            SortMode.UNREAD_FIRST -> items.sortedWith(compareBy<MessageSummary> { it.seen }.then(newest))
        }
    }

    /**
     * Groups by normalized subject. Messages without a subject are never grouped together.
     * The group position follows its first member in [items] order, so the chosen sort still applies.
     */
    fun group(items: List<MessageSummary>, countUnread: Boolean = true): List<ConversationRow> {
        val order = LinkedHashMap<String, MutableList<MessageSummary>>()
        for (m in items) {
            val key = normalizeSubject(m.subject).ifEmpty { "\u0000uid:${m.uid}" }
            order.getOrPut(key) { mutableListOf() }.add(m)
        }
        return order.values.map { members ->
            val latest = members.maxWith(compareBy<MessageSummary> { timeOf(it) }.thenBy { it.uid })
            ConversationRow(latest, members.size, if (countUnread) members.count { !it.seen } else 0)
        }
    }

    fun single(items: List<MessageSummary>): List<ConversationRow> =
        items.map { ConversationRow(it, 1, if (it.seen) 0 else 1) }
}
