package com.shegrowsdev.mail.mail

import com.shegrowsdev.mail.data.Account
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SignatureFormatTest {
    private val ar = SignatureParts("هبة أحمد", "مديرة المشاريع", "هاتف: 0790000000\nheba@shegrowsdev.com")

    @Test fun plainBlockUsesStandardSeparator() {
        assertEquals("-- \nهبة أحمد\nمديرة المشاريع\nهاتف: 0790000000\nheba@shegrowsdev.com", SignatureFormat.plainBlock(ar))
        assertEquals("", SignatureFormat.plainBlock(SignatureParts("", " ", "")))
    }

    @Test fun htmlIsEscaped() {
        assertEquals("&lt;b&gt;&amp;&quot;&#39;", SignatureFormat.escapeHtml("<b>&\"'"))
        val html = SignatureFormat.bodyHtml("a <script>alert(1)</script>\n\nسطر")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("<div><br></div>"))
        assertTrue(html.contains("<div dir=\"auto\">سطر</div>"))
    }

    @Test fun signatureHtmlHasDirectionLogoAndBoldName() {
        val rtl = SignatureFormat.signatureHtml(ar, rtl = true, withLogo = true)
        assertTrue(rtl.contains("dir=\"rtl\""))
        assertTrue(rtl.contains("cid:" + SignatureFormat.LOGO_CONTENT_ID))
        assertTrue(rtl.contains("<b>هبة أحمد</b>"))
        val ltr = SignatureFormat.signatureHtml(SignatureParts("Heba", "", ""), rtl = false, withLogo = false)
        assertTrue(ltr.contains("dir=\"ltr\"") && !ltr.contains("cid:"))
        assertEquals("", SignatureFormat.signatureHtml(SignatureParts("", "", ""), true, false))
    }

    @Test fun logoOnlySignatureStillRenders() {
        assertTrue(SignatureFormat.signatureHtml(SignatureParts("", "", ""), true, true).contains("cid:"))
    }

    @Test fun accountPicksUiLanguageAndFallsBack() {
        val a = Account(
            id = "1", displayName = "Heba", email = "h@x.com", imapHost = "i", imapPort = 993,
            smtpHost = "s", smtpPort = 465, nameAr = "هبة", titleAr = "مديرة", signatureAr = "",
            nameEn = "", titleEn = "", signatureEn = ""
        )
        assertEquals("هبة", a.signaturePartsFor("ar").name)
        assertEquals("هبة", a.signaturePartsFor("en").name) // English empty -> Arabic is used
        val b = a.copy(titleEn = "Manager")
        assertEquals("Heba", b.signaturePartsFor("en").name) // missing name -> display name
        assertEquals("Manager", b.signaturePartsFor("en").title)
        assertTrue(Account("1", "", "h@x.com", "i", 1, "s", 2).signaturePartsFor("ar").isEmpty)
    }
}
