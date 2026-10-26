package com.shegrowsdev.mail.mail

import org.junit.Assert.assertEquals
import org.junit.Test

class ReplyBuilderTest {
    @Test fun replySubjectIsNotPrefixedTwice() {
        assertEquals("Re: Hello", ReplyBuilder.replySubject("Hello"))
        assertEquals("RE: Hello", ReplyBuilder.replySubject("RE: Hello"))
        assertEquals("رد: مرحبا", ReplyBuilder.replySubject("رد: مرحبا"))
    }

    @Test fun forwardSubjectIsNotPrefixedTwice() {
        assertEquals("Fwd: Hello", ReplyBuilder.forwardSubject("Hello"))
        assertEquals("Fwd: Hello", ReplyBuilder.forwardSubject("Fwd: Hello"))
    }

    @Test fun quotePrefixesEveryLine() =
        assertEquals("> a\n>\n> b", ReplyBuilder.quote("a\n\nb"))

    @Test fun messageIdUsesAccountDomain() {
        val id = ReplyBuilder.newMessageId("heba@shegrowsdev.com")
        assert(id.startsWith("<") && id.endsWith("@shegrowsdev.com>"))
    }
}
