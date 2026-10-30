package com.shegrowsdev.mail.mail

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import com.sun.mail.smtp.SMTPSendFailedException
import javax.mail.SendFailedException
import javax.net.ssl.SSLException

enum class FailureKind {
    AUTH_IMAP,
    AUTH_SMTP,
    HOST_NOT_FOUND,
    TLS,
    TIMEOUT,
    CONNECT,
    SMTP_REJECTED,
    FOLDER_NOT_FOUND,
    MESSAGE_NOT_FOUND,
    INVALID_RECIPIENT,
    NO_RECIPIENT,
    NO_PASSWORD,
    ACCOUNT_NOT_FOUND,
    MESSAGE_TOO_LARGE,
    ATTACH_TYPE,
    ATTACH_CONTENT,
    ATTACH_TOO_LARGE,
    ATTACH_TOTAL_TOO_LARGE,
    ATTACH_TOO_MANY,
    ATTACH_READ,
    ATTACH_DOWNLOAD,
    ATTACH_NOT_FOUND,
    ATTACH_SAVE,
    NO_APP_TO_OPEN,
    SEARCH_FAILED,
    NOT_PERMITTED,
    DATA_ERROR,
    UNKNOWN,
}

enum class Protocol { IMAP, SMTP }

/**
 * Typed failure shown to the user. [detail] never contains credentials; it carries at most
 * rejected recipient addresses or an exception class name for diagnostics.
 */
class MailFailure(
    val kind: FailureKind,
    val detail: String? = null,
    cause: Throwable? = null,
) : Exception(kind.name, cause)

/** Maps any exception raised while talking to the mail server to a [MailFailure]. */
internal fun Throwable.toMailFailure(protocol: Protocol): MailFailure {
    if (this is MailFailure) return this

    val chain = generateSequence<Throwable>(this) { t ->
        if (t is MessagingException) t.nextException ?: t.cause else t.cause
    }.take(10).toList()

    for (t in chain) {
        when (t) {
            is AuthenticationFailedException -> return MailFailure(
                if (protocol == Protocol.IMAP) FailureKind.AUTH_IMAP else FailureKind.AUTH_SMTP,
                cause = this
            )
            is UnknownHostException -> return MailFailure(FailureKind.HOST_NOT_FOUND, cause = this)
            is SSLException -> return MailFailure(FailureKind.TLS, cause = this)
            is SocketTimeoutException -> return MailFailure(FailureKind.TIMEOUT, cause = this)
            is ConnectException, is NoRouteToHostException, is SocketException ->
                return MailFailure(FailureKind.CONNECT, cause = this)
            is SMTPSendFailedException -> {
                // 552 = message exceeds the server's size/storage limit.
                if (t.returnCode == 552) {
                    return MailFailure(FailureKind.MESSAGE_TOO_LARGE, cause = this)
                }
                return t.asRejected(this)
            }
            is SendFailedException -> return t.asRejected(this)
        }
    }
    return MailFailure(FailureKind.UNKNOWN, detail = javaClass.simpleName, cause = this)
}

private fun SendFailedException.asRejected(cause: Throwable): MailFailure {
    val rejected = (invalidAddresses.orEmpty().toList() + validUnsentAddresses.orEmpty().toList())
        .joinToString(", ") { it.toString() }
    return MailFailure(FailureKind.SMTP_REJECTED, detail = rejected.ifBlank { null }, cause = cause)
}
