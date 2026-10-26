package com.shegrowsdev.mail.data

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
    val signatureAr: String = "",
    val signatureEn: String = "",
    val notificationsEnabled: Boolean = true,
) {
    /** Signature matching the UI language, falling back to the other one if empty. */
    fun signatureFor(languageTag: String): String =
        if (languageTag.startsWith("ar")) signatureAr.ifBlank { signatureEn }
        else signatureEn.ifBlank { signatureAr }
}

/**
 * Connection settings supplied in the project specification for the single approved
 * test account. They are offered ONLY for exactly this address; other accounts must
 * have their own server settings entered and verified (spec section 2).
 * No password is stored here.
 */
object ApprovedPreset {
    const val EMAIL = "heba@shegrowsdev.com"
    const val IMAP_HOST = "nl1-sr9.supercp.com"
    const val IMAP_PORT = 993
    const val SMTP_HOST = "nl1-sr9.supercp.com"
    const val SMTP_PORT = 465
}
