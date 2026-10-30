package com.shegrowsdev.mail.admin

import com.shegrowsdev.mail.mail.Conversations

/** Decides which messages belong to one transaction (conversation). Pure Kotlin. */
object ThreadLinker {
    private val idRegex = Regex("<[^<>\\s]+>")

    /** Message-IDs are compared lower-cased and with their angle brackets. */
    fun normalizeId(raw: String?): String? {
        if (raw == null) return null
        val found = idRegex.find(raw)?.value
        return (found ?: raw.trim()).lowercase().ifEmpty { null }
    }

    fun idsIn(raw: String?): List<String> =
        if (raw == null) emptyList() else idRegex.findAll(raw).map { it.value.lowercase() }.toList()

    /** Every id that ties this message to others: its References, In-Reply-To and its own Message-ID. */
    fun relatedIds(messageId: String?, inReplyTo: String?, references: String?): List<String> =
        (idsIn(references) + listOfNotNull(normalizeId(inReplyTo), normalizeId(messageId))).distinct()

    /** The thread root when the headers give one, otherwise the normalized subject. */
    fun threadKey(messageId: String?, inReplyTo: String?, references: String?, subject: String): String {
        val root = idsIn(references).firstOrNull() ?: normalizeId(inReplyTo) ?: normalizeId(messageId)
        return if (root != null) "id:$root" else "subj:" + Conversations.normalizeSubject(subject)
    }

    /** Company domains are the domains of the accounts configured on this device. */
    fun isExternal(counterpartEmail: String, companyDomains: Set<String>): Boolean {
        val domain = counterpartEmail.substringAfterLast('@', "").trim().lowercase()
        return domain.isEmpty() || domain !in companyDomains
    }
}
