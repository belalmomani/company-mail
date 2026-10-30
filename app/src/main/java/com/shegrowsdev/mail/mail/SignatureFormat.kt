package com.shegrowsdev.mail.mail

/** The visible parts of an employee signature in one language. Pure Kotlin (testable). */
data class SignatureParts(val name: String, val title: String, val contact: String) {
    val isEmpty: Boolean get() = name.isBlank() && title.isBlank() && contact.isBlank()

    fun lines(): List<String> =
        (listOf(name, title) + contact.lines()).map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Renders signatures and message bodies. The signature here is a formatted text/image block; it is
 * NOT a legally binding digital signature (no certificate or cryptographic signing is involved).
 */
object SignatureFormat {
    const val LOGO_CONTENT_ID = "company-logo@signature"

    fun plainBlock(parts: SignatureParts): String =
        if (parts.isEmpty) "" else "-- \n" + parts.lines().joinToString("\n")

    fun escapeHtml(s: String): String = buildString(s.length + 16) {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&#39;")
            else -> append(c)
        }
    }

    /** Each line is its own block with `dir="auto"` so mixed Arabic/English text keeps its direction. */
    fun bodyHtml(body: String): String =
        body.replace("\r\n", "\n").lines().joinToString("") { line ->
            if (line.isEmpty()) "<div><br></div>" else "<div dir=\"auto\">${escapeHtml(line)}</div>"
        }

    fun signatureHtml(parts: SignatureParts, rtl: Boolean, withLogo: Boolean): String {
        if (parts.isEmpty && !withLogo) return ""
        val dir = if (rtl) "rtl" else "ltr"
        val lines = parts.lines()
        return buildString {
            append("<div dir=\"$dir\" style=\"margin-top:16px;padding-top:8px;border-top:1px solid #cccccc;\">")
            if (withLogo) {
                append("<div><img src=\"cid:$LOGO_CONTENT_ID\" alt=\"\" style=\"max-width:200px;height:auto;\"></div>")
            }
            lines.forEachIndexed { i, line ->
                val esc = escapeHtml(line)
                append(if (i == 0 && parts.name.isNotBlank()) "<div dir=\"auto\"><b>$esc</b></div>" else "<div dir=\"auto\">$esc</div>")
            }
            append("</div>")
        }
    }

    fun fullHtml(body: String, signatureHtml: String, rtl: Boolean): String =
        "<html><body dir=\"${if (rtl) "rtl" else "ltr"}\">" + bodyHtml(body) + signatureHtml + "</body></html>"
}
