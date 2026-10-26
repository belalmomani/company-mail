package com.shegrowsdev.mail.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRolesTest {
    @Test fun inboxIsCaseInsensitive() =
        assertEquals(FolderRole.INBOX, FolderRoles.detect("inbox", emptyList()))

    @Test fun specialUseAttributeWinsOverName() =
        assertEquals(FolderRole.SENT, FolderRoles.detect("Archive", listOf("\\Sent")))

    @Test fun fallsBackToWellKnownNames() {
        assertEquals(FolderRole.DRAFTS, FolderRoles.detect("INBOX.Drafts", emptyList()))
        assertEquals(FolderRole.SPAM, FolderRoles.detect("Junk", emptyList()))
        assertEquals(FolderRole.OTHER, FolderRoles.detect("Projects", emptyList()))
    }
}
