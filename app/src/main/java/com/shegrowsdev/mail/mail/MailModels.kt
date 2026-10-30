package com.shegrowsdev.mail.mail

enum class FolderRole { INBOX, SENT, DRAFTS, SPAM, TRASH, ARCHIVE, OTHER }

data class MailFolder(val path: String, val role: FolderRole)

data class MailAddress(val name: String?, val email: String) {
    val display: String get() = if (name.isNullOrBlank()) email else name

    /** Form accepted by the recipient fields: `"Name" <email>` or the bare address. */
    fun formatted(): String =
        if (name.isNullOrBlank()) email else "\"${name.replace("\"", "")}\" <$email>"
}

data class MessageSummary(
    val uid: Long,
    val subject: String,
    val from: MailAddress?,
    val to: List<MailAddress>,
    val dateMillis: Long?,
    val seen: Boolean,
    val answered: Boolean,
)

data class MessagePage(val items: List<MessageSummary>, val hasMore: Boolean)

data class MessageDetail(
    val uid: Long,
    val messageId: String?,
    val inReplyTo: String?,
    val references: String?,
    val subject: String,
    val from: MailAddress?,
    val replyTo: List<MailAddress>,
    val to: List<MailAddress>,
    val cc: List<MailAddress>,
    val bcc: List<MailAddress>,
    val dateMillis: Long?,
    val bodyText: String,
    val bodyFromHtml: Boolean,
    val attachments: List<AttachmentInfo>,
) {
    val hasAttachments: Boolean get() = attachments.isNotEmpty()
}

/**
 * An attachment of a received message. [index] is its position in the message's attachment list
 * (stable for a given message) and is how it is found again for download, so an attachment can
 * never be confused with another message's.
 */
data class AttachmentInfo(
    val index: Int,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    /** True when the type is on the allow-list; only those may be opened directly (others: save only). */
    val openable: Boolean,
)

/** A local file that will be attached to an outgoing message (a private copy in the app cache). */
data class OutgoingAttachment(
    val file: java.io.File,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
)

/** Signature added when sending; the body typed by the user stays free of it. */
data class OutgoingSignature(
    val parts: SignatureParts,
    val rtl: Boolean,
    val logo: java.io.File?,
)

/**
 * A message to send or to store as a draft. [messageId] is the RFC 5322 Message-ID that is
 * kept stable across draft saves so a re-saved draft replaces the previous copy.
 */
data class OutgoingMessage(
    val messageId: String,
    val to: List<String>,
    val cc: List<String>,
    val bcc: List<String>,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: String?,
    val replacesDraftUid: Long?,
    val attachments: List<OutgoingAttachment> = emptyList(),
    val signature: OutgoingSignature? = null,
)

data class SendOutcome(val copySavedToSent: Boolean)

/** Result of a folder search. [partialScan] is true when only recent headers were checked (no body search). */
data class SearchResult(val items: List<MessageSummary>, val partialScan: Boolean)

enum class DeleteOutcome { MOVED_TO_TRASH, DELETED_PERMANENTLY }

/** Device-side matching used when the server cannot run a search. Pure Kotlin (testable). */
object SearchMatch {
    fun matches(m: MessageSummary, query: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return false
        val haystack = buildList {
            add(m.subject)
            m.from?.let { add(it.email); it.name?.let(::add) }
            m.to.forEach { add(it.email); it.name?.let(::add) }
        }
        return haystack.any { it.lowercase().contains(q) }
    }
}
