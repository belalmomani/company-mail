package com.shegrowsdev.mail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentPolicyTest {
    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0, 0)
    private val ole = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(), 0)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @Test fun allowsOnlyPdfWordExcelAndImages() {
        listOf("a.pdf", "a.DOCX", "a.doc", "a.xls", "a.xlsx", "a.PNG", "a.jpg", "a.jpeg", "a.gif", "a.webp", "a.bmp")
            .forEach { assertTrue(it, AttachmentPolicy.isAllowedName(it)) }
        listOf("a.exe", "a.apk", "a.html", "a.zip", "a.js", "a", "a.pdf.exe", "a.docm").forEach {
            assertFalse(it, AttachmentPolicy.isAllowedName(it))
        }
    }

    @Test fun kindsAndMimeTypes() {
        assertEquals(AttachmentPolicy.Kind.WORD, AttachmentPolicy.kindForName("x.docx"))
        assertEquals(AttachmentPolicy.Kind.EXCEL, AttachmentPolicy.kindForName("x.xls"))
        assertEquals("image/jpeg", AttachmentPolicy.mimeForName("x.JPG"))
        assertNull(AttachmentPolicy.mimeForName("x.exe"))
    }

    @Test fun contentMustMatchTheExtension() {
        assertTrue(AttachmentPolicy.contentMatches("a.pdf", "%PDF-1.7\n...".toByteArray()))
        assertTrue(AttachmentPolicy.contentMatches("a.docx", zip))
        assertTrue(AttachmentPolicy.contentMatches("a.xlsx", zip))
        assertTrue(AttachmentPolicy.contentMatches("a.doc", ole))
        assertTrue(AttachmentPolicy.contentMatches("a.png", png))
        assertTrue(AttachmentPolicy.contentMatches("a.jpg", jpg))
        assertTrue(AttachmentPolicy.contentMatches("a.gif", "GIF89a....".toByteArray()))
        assertTrue(AttachmentPolicy.contentMatches("a.webp", "RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test fun renamedFilesAreRefused() {
        // an executable (MZ header) or a script renamed to look harmless
        assertFalse(AttachmentPolicy.contentMatches("invoice.pdf", "MZ\u0090\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertFalse(AttachmentPolicy.contentMatches("photo.jpg", "<html><script>".toByteArray()))
        assertFalse(AttachmentPolicy.contentMatches("old.doc", zip)) // .doc must be OLE, not zip
        assertFalse(AttachmentPolicy.contentMatches("new.docx", ole))
        assertFalse(AttachmentPolicy.contentMatches("a.pdf", ByteArray(0)))
        assertFalse(AttachmentPolicy.contentMatches("a.exe", "MZ".toByteArray()))
    }

    @Test fun sanitizesFileNames() {
        assertEquals("report.pdf", AttachmentPolicy.sanitizeFileName("../../etc/report.pdf", "x"))
        assertEquals("report.pdf", AttachmentPolicy.sanitizeFileName("C:\\Users\\me\\report.pdf", "x"))
        assertEquals("تقرير شهري.pdf", AttachmentPolicy.sanitizeFileName("تقرير شهري.pdf", "x"))
        assertEquals("ab.pdf", AttachmentPolicy.sanitizeFileName("a\u0000b<>:|?.pdf", "x"))
        assertEquals("fallback", AttachmentPolicy.sanitizeFileName("  ..  ", "fallback"))
        assertEquals("fallback", AttachmentPolicy.sanitizeFileName(null, "fallback"))
        val long = AttachmentPolicy.sanitizeFileName("a".repeat(300) + ".pdf", "x")
        assertTrue(long.length <= 120 && long.endsWith(".pdf"))
    }

    @Test fun formatsSizesWithLatinDigits() {
        assertEquals("512 B", AttachmentPolicy.formatSize(512))
        assertEquals("2 KB", AttachmentPolicy.formatSize(2048))
        assertEquals("1.5 MB", AttachmentPolicy.formatSize(1_572_864))
        assertEquals("15.0 MB", AttachmentPolicy.formatSize(AttachmentPolicy.MAX_FILE_BYTES))
    }

    @Test fun limitsAreConsistent() {
        assertTrue(AttachmentPolicy.MAX_FILE_BYTES <= AttachmentPolicy.MAX_TOTAL_BYTES)
        assertEquals(10, AttachmentPolicy.MAX_COUNT)
    }
}
