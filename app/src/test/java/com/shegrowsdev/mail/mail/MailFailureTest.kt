package com.shegrowsdev.mail.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/** Every way a connection or server can fail must become a specific, user-readable failure, never UNKNOWN. */
class MailFailureTest {
    private fun kind(e: Throwable, p: Protocol = Protocol.IMAP) = e.toMailFailure(p).kind

    /** JavaMail wraps the real network error inside a MessagingException. */
    private fun wrapped(e: Throwable) = MessagingException("Could not connect", e as Exception)

    @Test fun wrongPasswordIsAnAuthenticationFailurePerProtocol() {
        assertEquals(FailureKind.AUTH_IMAP, kind(AuthenticationFailedException("bad"), Protocol.IMAP))
        assertEquals(FailureKind.AUTH_SMTP, kind(AuthenticationFailedException("bad"), Protocol.SMTP))
    }

    @Test fun connectionLossAndServerDownAreConnectFailures() {
        assertEquals(FailureKind.CONNECT, kind(wrapped(ConnectException("refused"))))
        assertEquals(FailureKind.CONNECT, kind(wrapped(SocketException("Connection reset"))))
        assertEquals(FailureKind.CONNECT, kind(wrapped(NoRouteToHostException("no route"))))
    }

    @Test fun unknownHostAndTimeoutAreDistinct() {
        assertEquals(FailureKind.HOST_NOT_FOUND, kind(wrapped(UnknownHostException("mail.invalid"))))
        assertEquals(FailureKind.TIMEOUT, kind(wrapped(SocketTimeoutException("read timed out"))))
    }

    @Test fun certificateAndTlsProblemsAreTlsFailures() {
        assertEquals(FailureKind.TLS, kind(wrapped(SSLHandshakeException("PKIX path building failed"))))
        assertEquals(FailureKind.TLS, kind(wrapped(SSLException("Unsupported or unrecognized SSL message"))))
    }

    @Test fun theChainIsFollowedThroughNestedCauses() {
        val deep = MessagingException("outer", RuntimeException("mid", ConnectException("refused")))
        assertEquals(FailureKind.CONNECT, kind(deep))
    }

    @Test fun aServerThatRejectsTheMessageSizeIsMessageTooLarge() {
        val e = com.sun.mail.smtp.SMTPSendFailedException("MAIL", 552, "too big", null, null, null, null)
        assertEquals(FailureKind.MESSAGE_TOO_LARGE, kind(wrapped(e), Protocol.SMTP))
    }

    @Test fun otherServerRejectionKeepsTheAddressesAsDetail() {
        val bad = arrayOf<javax.mail.Address>(javax.mail.internet.InternetAddress("nobody@example.com"))
        val e = javax.mail.SendFailedException("rejected", null, null, null, bad)
        val f = wrapped(e).toMailFailure(Protocol.SMTP)
        assertEquals(FailureKind.SMTP_REJECTED, f.kind)
        assertEquals("nobody@example.com", f.detail)
    }

    @Test fun anAlreadyTypedFailureIsKeptAsIs() {
        val f = MailFailure(FailureKind.MESSAGE_NOT_FOUND)
        assertEquals(FailureKind.MESSAGE_NOT_FOUND, f.toMailFailure(Protocol.IMAP).kind)
    }

    @Test fun anythingElseIsUnknownAndNeverCarriesAMessageThatCouldHoldSecrets() {
        val f = IllegalStateException("password=hunter2").toMailFailure(Protocol.IMAP)
        assertEquals(FailureKind.UNKNOWN, f.kind)
        assertEquals("IllegalStateException", f.detail) // only the class name, never the message
    }

    @Test fun noDetailForPlainNetworkFailures() {
        assertNull(wrapped(ConnectException("refused to 10.0.0.1")).toMailFailure(Protocol.IMAP).detail)
    }
}
