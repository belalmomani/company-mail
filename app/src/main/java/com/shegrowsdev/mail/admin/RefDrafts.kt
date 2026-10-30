package com.shegrowsdev.mail.admin

import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MessageDetail

/** Builds the record of a real message for the administrative data. Pure Kotlin. */
object RefDrafts {

    /** Messages found in Sent or Drafts are outgoing; everything else was received. */
    fun directionOf(role: FolderRole): Direction =
        if (role == FolderRole.SENT || role == FolderRole.DRAFTS) Direction.OUTGOING else Direction.INCOMING

    fun fromDetail(
        accountId: String,
        accountEmail: String,
        role: FolderRole,
        detail: MessageDetail,
        companyDomains: Set<String>,
    ): RefDraft {
        val direction = directionOf(role)
        val counterpart = when (direction) {
            Direction.INCOMING -> detail.from?.email.orEmpty()
            Direction.OUTGOING -> detail.to.firstOrNull()?.email ?: detail.cc.firstOrNull()?.email.orEmpty()
        }
        return RefDraft(
            accountId = accountId,
            accountEmail = accountEmail,
            messageId = detail.messageId,
            inReplyTo = detail.inReplyTo,
            references = detail.references,
            folderRole = role,
            uid = detail.uid,
            subject = detail.subject,
            counterpart = counterpart,
            direction = direction,
            dateMillis = detail.dateMillis,
            attachmentNames = detail.attachments.map { it.name },
            external = ThreadLinker.isExternal(counterpart, companyDomains),
        )
    }
}
