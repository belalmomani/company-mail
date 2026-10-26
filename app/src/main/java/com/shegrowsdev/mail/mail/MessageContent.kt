package com.shegrowsdev.mail.mail

import android.text.Html
import javax.mail.Multipart
import javax.mail.Part

/** Extracts displayable text from a MIME message. Remote content is never loaded. */
internal class MessageContent(
    val text: String,
    val fromHtml: Boolean,
    val hasAttachments: Boolean,
) {
    companion object {
        fun extract(root: Part): MessageContent {
            val collector = Collector()
            collect(root, collector)
            val plain = collector.plain
            val html = collector.html
            return when {
                !plain.isNullOrBlank() -> MessageContent(plain.trim('\r', '\n'), false, collector.attachments)
                !html.isNullOrBlank() -> MessageContent(htmlToText(html), true, collector.attachments)
                else -> MessageContent("", false, collector.attachments)
            }
        }

        private class Collector {
            var plain: String? = null
            var html: String? = null
            var attachments: Boolean = false
        }

        private fun collect(part: Part, out: Collector) {
            when {
                part.isMimeType("multipart/*") -> {
                    val multipart = part.content as? Multipart ?: return
                    for (i in 0 until multipart.count) collect(multipart.getBodyPart(i), out)
                }
                part.isMimeType("message/rfc822") -> out.attachments = true
                isAttachment(part) -> out.attachments = true
                part.isMimeType("text/plain") -> if (out.plain == null) out.plain = readText(part)
                part.isMimeType("text/html") -> if (out.html == null) out.html = readText(part)
            }
        }

        private fun isAttachment(part: Part): Boolean {
            val disposition = part.disposition
            if (disposition != null && disposition.equals(Part.ATTACHMENT, ignoreCase = true)) return true
            val hasName = !part.fileName.isNullOrEmpty()
            val inline = disposition != null && disposition.equals(Part.INLINE, ignoreCase = true)
            return hasName && !inline
        }

        private fun readText(part: Part): String? = try {
            part.content as? String
        } catch (e: Exception) {
            // Unknown charset or encoding: fall back to the raw bytes as UTF-8.
            try {
                String(part.inputStream.readBytes(), Charsets.UTF_8)
            } catch (e2: Exception) {
                null
            }
        }

        private fun htmlToText(html: String): String {
            val cleaned = html
                .replace(Regex("(?is)<head.*?</head>"), "")
                .replace(Regex("(?is)<style.*?</style>"), "")
                .replace(Regex("(?is)<script.*?</script>"), "")
                .replace(Regex("(?s)<!--.*?-->"), "")
            val text = Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY).toString()
            return text
                .replace('￼', ' ')
                .replace(Regex("[ \\t]+\\n"), "\n")
                .replace(Regex("\\n{3,}"), "\n\n")
                .trim()
        }
    }
}
