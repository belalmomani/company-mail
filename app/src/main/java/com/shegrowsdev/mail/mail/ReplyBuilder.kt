package com.shegrowsdev.mail.mail

import java.util.UUID

/** Pure helpers for building replies/forwards. No Android dependencies. */
object ReplyBuilder {

    private val replyPrefixes = listOf("re:", "رد:")
    private val forwardPrefixes = listOf("fwd:", "fw:", "إعادة توجيه:")

    fun replySubject(original: String): String = prefixed(original, "Re:", replyPrefixes)

    fun forwardSubject(original: String): String = prefixed(original, "Fwd:", forwardPrefixes)

    private fun prefixed(original: String, prefix: String, known: List<String>): String {
        val trimmed = original.trim()
        val lower = trimmed.lowercase()
        return if (known.any { lower.startsWith(it) }) trimmed else "$prefix $trimmed".trim()
    }

    /** `> ` prefixed copy of [text]; very long originals are cut to keep the draft manageable. */
    fun quote(text: String, maxChars: Int = 20_000): String =
        text.take(maxChars).lines().joinToString("\n") { line -> if (line.isEmpty()) ">" else "> $line" }

    fun references(original: MessageDetail): String? {
        val parts = listOfNotNull(original.references, original.messageId)
        return parts.joinToString(" ").ifBlank { null }
    }

    /** Reply-all CC list: everyone except me and the people already in To, without duplicates. */
    fun replyAllCc(original: MessageDetail, myEmail: String, to: List<MailAddress>): List<MailAddress> {
        val exclude = (to.map { it.email } + myEmail).map { it.lowercase() }.toMutableSet()
        val result = mutableListOf<MailAddress>()
        for (a in original.to + original.cc) {
            if (exclude.add(a.email.lowercase())) result.add(a)
        }
        return result
    }

    fun newMessageId(accountEmail: String): String {
        val domain = accountEmail.substringAfter('@', "localhost").ifBlank { "localhost" }
        return "<${UUID.randomUUID()}@$domain>"
    }
}
