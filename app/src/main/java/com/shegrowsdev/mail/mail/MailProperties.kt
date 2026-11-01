package com.shegrowsdev.mail.mail

import com.shegrowsdev.mail.data.Account
import java.util.Properties

/**
 * JavaMail settings for the account servers. Kept in one place so a test can prove what is (and is not) enabled:
 * implicit SSL/TLS with certificate and host-name checks, no STARTTLS downgrade, no custom trust, no debug output.
 */
internal object MailProperties {
    fun imap(a: Account) = Properties().apply {
        put("mail.store.protocol", "imap")
        put("mail.imap.host", a.imapHost)
        put("mail.imap.port", a.imapPort.toString())
        put("mail.imap.ssl.enable", "true")
        put("mail.imap.ssl.checkserveridentity", "true")
        put("mail.imap.connectiontimeout", "15000")
        put("mail.imap.timeout", "30000")
        put("mail.imap.writetimeout", "30000")
        // Reading a body must not silently mark the message as read; we do it explicitly.
        put("mail.imap.peek", "true")
    }

    /**
     * For the long-lived IDLE connection: the same secure settings, but NO read timeout (the server stays silent
     * until something arrives; a keep-alive command is sent regularly instead).
     */
    fun idle(a: Account) = imap(a).apply { remove("mail.imap.timeout") }

    fun smtp(a: Account) = Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.host", a.smtpHost)
        put("mail.smtp.port", a.smtpPort.toString())
        put("mail.smtp.ssl.enable", "true")
        put("mail.smtp.ssl.checkserveridentity", "true")
        put("mail.smtp.auth", "true")
        put("mail.smtp.connectiontimeout", "15000")
        put("mail.smtp.timeout", "30000")
        put("mail.smtp.writetimeout", "30000")
    }
}
