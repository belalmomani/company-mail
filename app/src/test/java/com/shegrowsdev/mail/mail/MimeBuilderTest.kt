package com.shegrowsdev.mail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage

/** Builds real MIME messages (Arabic text, Arabic file name, attachments, signature) and reads them back. */
class MimeBuilderTest {
    private val session = Session.getInstance(Properties())

    private fun base(out: OutgoingMessage): MimeMessage {
        val msg = MimeMessage(session)
        msg.setFrom(InternetAddress("heba@shegrowsdev.com"))
        msg.setRecipients(Message.RecipientType.TO, "client@example.com")
        msg.setSubject("مرحبا بالعالم", "UTF-8")
        MimeBuilder.fillContent(msg, out)
        msg.saveChanges()
        val bytes = ByteArrayOutputStream().also { msg.writeTo(it) }.toByteArray()
        return MimeMessage(session, ByteArrayInputStream(bytes))
    }

    private fun message(body: String, attachments: List<OutgoingAttachment> = emptyList(), signature: OutgoingSignature? = null) =
        OutgoingMessage("<id@x>", listOf("client@example.com"), emptyList(), emptyList(), "s", body, null, null, null, attachments, signature)

    private fun temp(name: String, content: ByteArray): File =
        File.createTempFile("mimetest", name).also { it.writeBytes(content); it.deleteOnExit() }

    @Test fun plainArabicMessageRoundTrips() {
        val parsed = base(message("نص عربي\nEnglish line"))
        assertEquals("مرحبا بالعالم", parsed.subject)
        val c = MessageContent.extract(parsed)
        assertEquals("نص عربي\nEnglish line", c.text)
        assertTrue(c.attachments.isEmpty())
    }

    @Test fun attachmentWithArabicNameRoundTripsByteForByte() {
        val data = "%PDF-1.4 تجربة".toByteArray(Charsets.UTF_8)
        val file = temp("a.pdf", data)
        val parsed = base(message("body", listOf(OutgoingAttachment(file, "تقرير.pdf", "application/pdf", data.size.toLong()))))
        val c = MessageContent.extract(parsed)
        assertEquals(1, c.attachments.size)
        assertEquals("تقرير.pdf", c.attachments[0].name)
        assertEquals("application/pdf", c.attachments[0].mimeType)
        assertTrue(c.attachments[0].openable)
        val part = MessageContent.attachmentParts(parsed)[0]
        assertTrue(data.contentEquals(part.inputStream.readBytes()))
    }

    @Test fun multipleAttachmentsKeepTheirOrderAndIndexes() {
        val a = temp("a.pdf", "%PDF-1".toByteArray())
        val b = temp("b.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val parsed = base(
            message(
                "x",
                listOf(
                    OutgoingAttachment(a, "first.pdf", "application/pdf", 6),
                    OutgoingAttachment(b, "second.png", "image/png", 8),
                )
            )
        )
        val c = MessageContent.extract(parsed)
        assertEquals(listOf("first.pdf", "second.png"), c.attachments.map { it.name })
        assertEquals(listOf(0, 1), c.attachments.map { it.index })
    }

    @Test fun signatureIsAddedToTextAndHtmlAndNotToTheTypedBody() {
        val sig = OutgoingSignature(SignatureParts("هبة", "مديرة", "0790000000"), rtl = true, logo = null)
        val parsed = base(message("مرحبا", signature = sig))
        assertTrue(parsed.isMimeType("multipart/alternative"))
        val c = MessageContent.extract(parsed)
        assertTrue(c.text.startsWith("مرحبا"))
        assertTrue(c.text.contains("-- \nهبة\nمديرة\n0790000000"))
    }

    @Test fun logoIsSentAsInlineRelatedImageNotAsAnAttachment() {
        val logo = temp("logo.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val sig = OutgoingSignature(SignatureParts("Heba", "", ""), rtl = false, logo = logo)
        val data = temp("a.pdf", "%PDF-1".toByteArray())
        val parsed = base(message("hi", listOf(OutgoingAttachment(data, "a.pdf", "application/pdf", 6)), sig))
        val c = MessageContent.extract(parsed)
        assertEquals(listOf("a.pdf"), c.attachments.map { it.name })
        val raw = ByteArrayOutputStream().also { parsed.writeTo(it) }.toString("ISO-8859-1")
        assertTrue(raw.contains("Content-ID: <" + SignatureFormat.LOGO_CONTENT_ID + ">"))
    }
}
