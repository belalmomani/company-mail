package com.shegrowsdev.mail.data

import com.shegrowsdev.mail.mail.SignatureParts

/** EMPLOYEE sees only own data; ADMIN sees the administrative data of every account on this device. */
enum class AccountRole { EMPLOYEE, ADMIN }

/**
 * A mail account. Connection settings are per account: nothing here assumes that
 * all employees share one server. Both IMAP and SMTP always use implicit SSL/TLS
 * with certificate and host-name verification (no downgrade option exists).
 *
 * The password is deliberately NOT part of this class; it lives encrypted in [AccountStore].
 */
data class Account(
    val id: String,
    val displayName: String,
    val email: String,
    val imapHost: String,
    val imapPort: Int,
    val smtpHost: String,
    val smtpPort: Int,
    /** Contact details line(s) of the Arabic / English signature (phase 1 called this "signature"). */
    val signatureAr: String = "",
    val signatureEn: String = "",
    val notificationsEnabled: Boolean = true,
    val nameAr: String = "",
    val titleAr: String = "",
    val nameEn: String = "",
    val titleEn: String = "",
    /** Attach the company logo (a local image chosen in Settings) to the signature. */
    val includeLogo: Boolean = false,
    /** Decides which administrative data (tasks, register, statistics) this account may see. */
    val role: AccountRole = AccountRole.EMPLOYEE,
) {
    /**
     * Signature for the UI language; if that language has nothing filled in, the other language's is
     * used. A missing name falls back to the account's display name.
     */
    fun signaturePartsFor(languageTag: String): SignatureParts {
        val ar = SignatureParts(nameAr, titleAr, signatureAr)
        val en = SignatureParts(nameEn, titleEn, signatureEn)
        val (primary, secondary) = if (languageTag.startsWith("ar")) ar to en else en to ar
        val chosen = if (!primary.isEmpty) primary else secondary
        return if (chosen.name.isBlank() && !chosen.isEmpty) chosen.copy(name = displayName.trim()) else chosen
    }
}

/**
 * Connection settings of the company mail server. They were approved for heba@shegrowsdev.com and the owner has
 * confirmed that every @shegrowsdev.com mailbox uses the same ones; other domains still need their own settings.
 * The real login is tested before an account is saved. No password is stored here.
 */
object ApprovedPreset {
    const val DOMAIN = "shegrowsdev.com"
    const val EMAIL = "heba@shegrowsdev.com"
    const val IMAP_HOST = "nl1-sr9.supercp.com"
    const val IMAP_PORT = 993
    const val SMTP_HOST = "nl1-sr9.supercp.com"
    const val SMTP_PORT = 465
}
