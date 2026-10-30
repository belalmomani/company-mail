package com.shegrowsdev.mail.mail

import java.util.Locale

/**
 * Which files may be attached, and how they are checked. Pure Kotlin (no Android types) so it can be
 * unit tested. A file is accepted only when its extension is on the allow-list AND its first bytes
 * match that type; a renamed executable is therefore refused.
 */
object AttachmentPolicy {
    const val MAX_FILE_BYTES: Long = 15L * 1024 * 1024
    const val MAX_TOTAL_BYTES: Long = 20L * 1024 * 1024
    const val MAX_COUNT: Int = 10
    const val HEAD_BYTES: Int = 2048

    enum class Kind { PDF, WORD, EXCEL, IMAGE }

    private data class Rule(val kind: Kind, val mime: String, val magic: Magic)

    private enum class Magic { PDF, ZIP, OLE, PNG, JPEG, GIF, WEBP, BMP }

    private val rules: Map<String, Rule> = mapOf(
        "pdf" to Rule(Kind.PDF, "application/pdf", Magic.PDF),
        "doc" to Rule(Kind.WORD, "application/msword", Magic.OLE),
        "docx" to Rule(
            Kind.WORD,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            Magic.ZIP
        ),
        "xls" to Rule(Kind.EXCEL, "application/vnd.ms-excel", Magic.OLE),
        "xlsx" to Rule(
            Kind.EXCEL,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            Magic.ZIP
        ),
        "png" to Rule(Kind.IMAGE, "image/png", Magic.PNG),
        "jpg" to Rule(Kind.IMAGE, "image/jpeg", Magic.JPEG),
        "jpeg" to Rule(Kind.IMAGE, "image/jpeg", Magic.JPEG),
        "gif" to Rule(Kind.IMAGE, "image/gif", Magic.GIF),
        "webp" to Rule(Kind.IMAGE, "image/webp", Magic.WEBP),
        "bmp" to Rule(Kind.IMAGE, "image/bmp", Magic.BMP),
    )

    /** MIME types offered to the system file picker. */
    val pickerMimeTypes: Array<String> = arrayOf(
        "application/pdf",
        "application/msword",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.ms-excel",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "image/*",
    )

    private fun extension(name: String): String =
        name.substringAfterLast('.', "").lowercase(Locale.ROOT)

    fun kindForName(name: String): Kind? = rules[extension(name)]?.kind

    fun mimeForName(name: String): String? = rules[extension(name)]?.mime

    fun isAllowedName(name: String): Boolean = rules.containsKey(extension(name))

    /** True when [head] (the first bytes of the file) really looks like the type the extension claims. */
    fun contentMatches(name: String, head: ByteArray): Boolean {
        val rule = rules[extension(name)] ?: return false
        return when (rule.magic) {
            Magic.PDF -> indexOf(head, "%PDF-".toByteArray(Charsets.US_ASCII)) in 0..1023
            Magic.ZIP -> startsWith(head, byteArrayOf(0x50, 0x4B, 0x03, 0x04))
            Magic.OLE -> startsWith(
                head,
                byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())
            )
            Magic.PNG -> startsWith(head, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            Magic.JPEG -> startsWith(head, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            Magic.GIF -> startsWith(head, "GIF87a".toByteArray(Charsets.US_ASCII)) ||
                startsWith(head, "GIF89a".toByteArray(Charsets.US_ASCII))
            Magic.WEBP -> head.size >= 12 &&
                startsWith(head, "RIFF".toByteArray(Charsets.US_ASCII)) &&
                String(head, 8, 4, Charsets.US_ASCII) == "WEBP"
            Magic.BMP -> startsWith(head, "BM".toByteArray(Charsets.US_ASCII))
        }
    }

    /**
     * Makes a file name safe to use on disk and in headers: no path parts, no control or
     * reserved characters, bounded length. Falls back to [fallback] when nothing usable is left.
     */
    fun sanitizeFileName(raw: String?, fallback: String): String {
        var name = raw.orEmpty().substringAfterLast('/').substringAfterLast('\\')
        name = name.filter { it.code >= 0x20 && it.code != 0x7F && it !in "<>:\"|?*" }
        name = name.trim().trim('.')
        if (name.length > 120) {
            val ext = name.substringAfterLast('.', "")
            val keep = if (ext.length in 1..8) ext else ""
            val stem = name.removeSuffix(if (keep.isEmpty()) "" else ".$keep")
            name = stem.take(110) + if (keep.isEmpty()) "" else ".$keep"
        }
        return name.ifBlank { fallback }
    }

    /** `1.4 MB` style text with Latin digits, so it reads the same in Arabic and English. */
    fun formatSize(bytes: Long): String = when {
        bytes < 0 -> ""
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.0f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun startsWith(data: ByteArray, prefix: ByteArray): Boolean {
        if (data.size < prefix.size) return false
        for (i in prefix.indices) if (data[i] != prefix[i]) return false
        return true
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
