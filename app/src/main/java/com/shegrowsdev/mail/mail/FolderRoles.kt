package com.shegrowsdev.mail.mail

/**
 * Detects a folder's role. Server-declared IMAP special-use attributes (RFC 6154) win;
 * well-known names are only a fallback.
 */
object FolderRoles {
    private val sentNames = setOf("sent", "sent items", "sent mail", "sent messages")
    private val draftNames = setOf("drafts", "draft")
    private val spamNames = setOf("spam", "junk", "junk e-mail", "junk email", "bulk mail")
    private val trashNames = setOf("trash", "deleted items", "deleted messages", "bin")

    fun detect(path: String, attributes: List<String>): FolderRole {
        if (path.equals("INBOX", ignoreCase = true)) return FolderRole.INBOX

        val attrs = attributes.map { it.lowercase() }
        when {
            "\\sent" in attrs -> return FolderRole.SENT
            "\\drafts" in attrs -> return FolderRole.DRAFTS
            "\\junk" in attrs -> return FolderRole.SPAM
            "\\trash" in attrs -> return FolderRole.TRASH
        }

        val leaf = path.split('/', '.').last().trim().lowercase()
        return when (leaf) {
            in sentNames -> FolderRole.SENT
            in draftNames -> FolderRole.DRAFTS
            in spamNames -> FolderRole.SPAM
            in trashNames -> FolderRole.TRASH
            else -> FolderRole.OTHER
        }
    }
}
