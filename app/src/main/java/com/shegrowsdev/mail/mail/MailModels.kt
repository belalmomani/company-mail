package com.shegrowsdev.mail.mail

enum class FolderRole { INBOX, SENT, DRAFTS, SPAM, TRASH, OTHER }

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
    val hasAttachments: Boolean,
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
)

data class SendOutcome(val copySavedToSent: Boolean)
