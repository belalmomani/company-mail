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
    private val archiveNames = setOf("archive", "archives", "archived")

    fun detect(path: String, attributes: List<String>): FolderRole {
        if (path.equals("INBOX", ignoreCase = true)) return FolderRole.INBOX

        val attrs = attributes.map { it.lowercase() }
        when {
            "\\sent" in attrs -> return FolderRole.SENT
            "\\drafts" in attrs -> return FolderRole.DRAFTS
            "\\junk" in attrs -> return FolderRole.SPAM
            "\\trash" in attrs -> return FolderRole.TRASH
            "\\archive" in attrs -> return FolderRole.ARCHIVE
        }

        val leaf = path.split('/', '.').last().trim().lowercase()
        return when (leaf) {
            in sentNames -> FolderRole.SENT
            in draftNames -> FolderRole.DRAFTS
            in spamNames -> FolderRole.SPAM
            in trashNames -> FolderRole.TRASH
            in archiveNames -> FolderRole.ARCHIVE
            else -> FolderRole.OTHER
        }
    }

    /**
     * Full path for a new top-level folder called [name]. Servers whose folders all live under
     * `INBOX.` / `INBOX/` (Courier, some Dovecot setups) need the same prefix for new folders.
     */
    fun siblingPath(existingPaths: List<String>, name: String): String {
        for (p in existingPaths) {
            if (p.length > 6 && p.startsWith("INBOX", ignoreCase = true) && (p[5] == '.' || p[5] == '/')) {
                return "INBOX" + p[5] + name
            }
        }
        return name
    }
}
