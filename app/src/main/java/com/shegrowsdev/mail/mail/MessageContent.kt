package com.shegrowsdev.mail.mail

import android.text.Html
import javax.mail.Multipart
import javax.mail.Part

/** Extracts displayable text from a MIME message. Remote content is never loaded. */
internal class MessageContent(
    val text: String,
    val fromHtml: Boolean,
    val attachments: List<AttachmentInfo>,
) {
    companion object {
        fun extract(root: Part): MessageContent {
            val collector = Collector()
            collect(root, collector)
            val plain = collector.plain
            val html = collector.html
            val infos = collector.parts.mapIndexed { i, p -> describe(i, p) }
            return when {
                !plain.isNullOrBlank() -> MessageContent(plain.trim('\r', '\n'), false, infos)
                !html.isNullOrBlank() -> MessageContent(htmlToText(html), true, infos)
                else -> MessageContent("", false, infos)
            }
        }

        /**
         * The attachment parts of [root] in the same order as [AttachmentInfo.index]. The traversal is
         * the one [extract] uses, so an index always refers to the same part.
         */
        fun attachmentParts(root: Part): List<Part> {
            val collector = Collector()
            collect(root, collector)
            return collector.parts
        }

        private class Collector {
            var plain: String? = null
            var html: String? = null
            val parts = mutableListOf<Part>()
        }

        private fun describe(index: Int, part: Part): AttachmentInfo {
            val mime = runCatching { ContentTypeName.base(part.contentType) }.getOrNull() ?: "application/octet-stream"
            val rawName = runCatching { part.fileName }.getOrNull()
            val name = AttachmentPolicy.sanitizeFileName(
                rawName,
                fallback = if (part.isMimeType("message/rfc822")) "message-${index + 1}.eml" else "attachment-${index + 1}"
            )
            // IMAP reports the encoded size; base64 text is about 4/3 of the real size.
            val encoded = runCatching { part.size }.getOrDefault(-1)
            val base64 = runCatching { part.getHeader("Content-Transfer-Encoding")?.firstOrNull() }
                .getOrNull()?.trim().equals("base64", ignoreCase = true)
            val size = if (encoded < 0) -1L else if (base64) encoded.toLong() * 3 / 4 else encoded.toLong()
            return AttachmentInfo(index, name, mime, size, openable = AttachmentPolicy.isAllowedName(name))
        }

        private fun collect(part: Part, out: Collector) {
            when {
                part.isMimeType("multipart/*") -> {
                    val multipart = part.content as? Multipart ?: return
                    for (i in 0 until multipart.count) collect(multipart.getBodyPart(i), out)
                }
                part.isMimeType("message/rfc822") -> out.parts.add(part)
                isAttachment(part) -> out.parts.add(part)
                part.isMimeType("text/plain") -> if (out.plain == null) out.plain = readText(part)
                part.isMimeType("text/html") -> if (out.html == null) out.html = readText(part)
            }
        }

        private fun isAttachment(part: Part): Boolean {
            val disposition = part.disposition
            if (disposition != null && disposition.equals(Part.ATTACHMENT, ignoreCase = true)) return true
            val hasName = !part.fileName.isNullOrEmpty()
            val inline = disposition != null && disposition.equals(Part.INLINE, ignoreCase = true)
            // Inline images that the HTML body references (they carry a Content-ID) are not attachments.
            val embedded = inline && !(runCatching { part.getHeader("Content-ID") }.getOrNull().isNullOrEmpty())
            if (embedded) return false
            if (hasName) return true
            // A non-text leaf part without a name or disposition (e.g. a bare application/pdf).
            return !part.isMimeType("text/*") && !inline
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

/** Extracts `type/subtype` from a Content-Type header value without parsing parameters. */
internal object ContentTypeName {
    fun base(contentType: String?): String =
        contentType?.substringBefore(';')?.trim()?.lowercase()?.ifEmpty { null } ?: "application/octet-stream"
}
