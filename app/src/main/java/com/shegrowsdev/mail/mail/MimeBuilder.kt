package com.shegrowsdev.mail.mail

import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Multipart
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Builds the MIME body of an outgoing message.
 *
 *  - no signature, no attachments: a single text/plain part (as in phase 1);
 *  - signature: multipart/alternative with text/plain and an HTML version (the logo, if any, is an
 *    inline image referenced by Content-ID inside multipart/related);
 *  - attachments: everything above wrapped in multipart/mixed, one part per file.
 *
 * Non-ASCII file names are encoded by JavaMail (RFC 2231) and every text part is UTF-8.
 */
internal object MimeBuilder {

    fun fillContent(msg: MimeMessage, out: OutgoingMessage) {
        val sig = out.signature?.takeIf { !it.parts.isEmpty || it.logo != null }
        val hasAttachments = out.attachments.isNotEmpty()

        when {
            sig == null && !hasAttachments -> msg.setText(out.body, "UTF-8")
            !hasAttachments -> msg.setContent(alternative(out, sig!!))
            else -> {
                val mixed = MimeMultipart("mixed")
                mixed.addBodyPart(
                    if (sig == null) textPart(out.body)
                    else MimeBodyPart().apply { setContent(alternative(out, sig)) }
                )
                out.attachments.forEach { mixed.addBodyPart(attachmentPart(it)) }
                msg.setContent(mixed)
            }
        }
    }

    private fun textPart(text: String) = MimeBodyPart().apply { setText(text, "UTF-8") }

    private fun alternative(out: OutgoingMessage, sig: OutgoingSignature): Multipart {
        val plainSig = SignatureFormat.plainBlock(sig.parts)
        val plain = if (plainSig.isEmpty()) out.body else out.body.trimEnd() + "\n\n" + plainSig
        val logo = sig.logo
        val html = SignatureFormat.fullHtml(
            out.body,
            SignatureFormat.signatureHtml(sig.parts, sig.rtl, withLogo = logo != null),
            sig.rtl
        )

        val htmlPart = MimeBodyPart().apply { setText(html, "UTF-8", "html") }
        val htmlSide: MimeBodyPart = if (logo == null) {
            htmlPart
        } else {
            val logoPart = MimeBodyPart().apply {
                val source = FileDataSource(logo)
                source.setFileTypeMap(FixedTypeMap("image/png"))
                dataHandler = DataHandler(source)
                setHeader("Content-ID", "<${SignatureFormat.LOGO_CONTENT_ID}>")
                setHeader("Content-Transfer-Encoding", "base64")
                disposition = "inline"
                fileName = "logo.png"
            }
            val related = MimeMultipart("related")
            related.addBodyPart(htmlPart)
            related.addBodyPart(logoPart)
            MimeBodyPart().apply { setContent(related) }
        }

        val alt = MimeMultipart("alternative")
        alt.addBodyPart(textPart(plain)) // plain first, richest last (RFC 2046)
        alt.addBodyPart(htmlSide)
        return alt
    }

    private fun attachmentPart(a: OutgoingAttachment) = MimeBodyPart().apply {
        val source = FileDataSource(a.file)
        source.setFileTypeMap(FixedTypeMap(a.mimeType))
        dataHandler = DataHandler(source)
        fileName = a.name
        disposition = "attachment"
        setHeader("Content-Transfer-Encoding", "base64")
    }

    /** Makes FileDataSource report exactly the type we validated, not one guessed from the file name. */
    private class FixedTypeMap(private val type: String) : javax.activation.FileTypeMap() {
        override fun getContentType(file: java.io.File): String = type
        override fun getContentType(filename: String): String = type
    }
}
