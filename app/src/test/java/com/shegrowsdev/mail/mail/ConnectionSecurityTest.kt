package com.shegrowsdev.mail.mail

import androidx.test.core.app.ApplicationProvider
import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.data.AccountStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Real sockets on the loopback interface (no mail server needed): the app must report a dead port as a
 * connection problem, must refuse a server that does not speak TLS, and must never send the password to it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConnectionSecurityTest {
    private val secret = "Zq9-never-sent-in-clear-7731"

    private fun account(host: String, imapPort: Int, smtpPort: Int = imapPort) = Account(
        id = "t", displayName = "Test", email = "test@example.com",
        imapHost = host, imapPort = imapPort, smtpHost = host, smtpPort = smtpPort,
    )

    private fun repo() = MailRepository(AccountStore(ApplicationProvider.getApplicationContext()))

    private fun failureOf(block: suspend () -> Unit): MailFailure {
        try {
            runBlocking { block() }
        } catch (e: MailFailure) {
            return e
        }
        fail("expected a MailFailure")
        throw AssertionError()
    }

    /** A "server" that talks plain text (no TLS) and records every byte it receives. */
    private class PlainServer : AutoCloseable {
        val socket = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val received = ByteArrayOutputStream()
        val port get() = socket.localPort
        private val worker = thread(isDaemon = true) {
            try {
                socket.accept().use { c: Socket ->
                    c.getOutputStream().apply { write("* OK plain IMAP ready\r\n".toByteArray()); flush() }
                    c.soTimeout = 3000
                    val buf = ByteArray(4096)
                    try {
                        while (true) {
                            val n = c.getInputStream().read(buf)
                            if (n < 0) break
                            synchronized(received) { received.write(buf, 0, n) }
                        }
                    } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
        fun bytes(): String = synchronized(received) { String(received.toByteArray(), Charsets.ISO_8859_1) }
        override fun close() { socket.close(); worker.join(5000) }
    }

    @Test fun aPortWithNothingListeningIsAConnectionFailure() {
        val free = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort } // closed again
        val f = failureOf { repo().testConnection(account("127.0.0.1", free), secret) }
        assertEquals(FailureKind.CONNECT, f.kind)
    }

    @Test fun aServerWithoutTlsIsRefusedAndNeverReceivesThePassword() {
        PlainServer().use { server ->
            val f = failureOf { repo().testConnection(account("127.0.0.1", server.port), secret) }
            assertTrue("was ${f.kind}", f.kind == FailureKind.TLS || f.kind == FailureKind.CONNECT)
            val seen = server.bytes()
            assertFalse("password reached a non-TLS server", seen.contains(secret))
            assertFalse("a plaintext IMAP LOGIN was attempted", seen.contains("LOGIN", ignoreCase = true))
            assertFalse(seen.contains("AUTHENTICATE", ignoreCase = true))
        }
    }

    @Test fun aServerThatHangsUpIsAConnectionOrTlsFailureNotUnknown() {
        val server = ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"))
        val t = thread(isDaemon = true) { runCatching { server.accept().close() } }
        try {
            val f = failureOf { repo().testConnection(account("127.0.0.1", server.localPort), secret) }
            assertTrue("was ${f.kind}", f.kind == FailureKind.TLS || f.kind == FailureKind.CONNECT)
        } finally {
            server.close(); t.join(3000)
        }
    }

    @Test fun failureMessagesNeverContainThePassword() {
        val f = failureOf { repo().testConnection(account("127.0.0.1", 1), secret) }
        assertFalse((f.detail ?: "").contains(secret))
        assertFalse((f.message ?: "").contains(secret))
        assertFalse(generateSequence<Throwable>(f) { it.cause }.any { (it.message ?: "").contains(secret) })
    }

    // ------------------------------------------------------------ settings that must never be weakened

    @Test fun mailSettingsUseImplicitTlsWithFullCertificateChecks() {
        val a = account("mail.example.com", 993, 465)
        for ((p, prefix) in listOf(MailProperties.imap(a) to "mail.imap.", MailProperties.smtp(a) to "mail.smtp.")) {
            assertEquals("true", p.getProperty(prefix + "ssl.enable"))
            assertEquals("true", p.getProperty(prefix + "ssl.checkserveridentity"))
            assertNull("no trust override", p.getProperty(prefix + "ssl.trust"))
            assertNull("no custom socket factory", p.getProperty(prefix + "ssl.socketFactory"))
            assertNull(p.getProperty(prefix + "socketFactory.class"))
            assertNull("no STARTTLS downgrade path", p.getProperty(prefix + "starttls.enable"))
            assertNull(p.getProperty(prefix + "starttls.required"))
            assertNull("debug output would print credentials", p.getProperty("mail.debug"))
        }
    }
}
