package com.shegrowsdev.mail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanyDirectoryTest {
    private fun account(email: String) = Account(
        id = email, displayName = "", email = email, imapHost = "h", imapPort = 993, smtpHost = "h", smtpPort = 465
    )

    @Test fun listsTheSevenCompanyAddressesOnly() {
        assertEquals(7, CompanyDirectory.emails.size)
        assertTrue(CompanyDirectory.emails.all { it.endsWith("@" + ApprovedPreset.DOMAIN) })
        assertEquals(CompanyDirectory.emails.size, CompanyDirectory.emails.map { it.lowercase() }.toSet().size)
    }

    @Test fun deviceAccountsAreMergedWithoutDuplicates() {
        val merged = CompanyDirectory.withAccounts(listOf(account("HEBA@shegrowsdev.com"), account("new@other.com")))
        assertEquals(8, merged.size)
        assertTrue(merged.contains("new@other.com"))
    }

    @Test fun nameIsTheCapitalisedLocalPart() {
        assertEquals("Heba", CompanyDirectory.nameOf("heba@shegrowsdev.com"))
    }
}
