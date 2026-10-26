package com.shegrowsdev.mail.mail

import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.data.AccountStore
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.mail.Address
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.HeaderTerm

/**
 * Real IMAP/SMTP access. Every operation opens its own connection and closes it afterwards.
 * - TLS is always on (implicit SSL/TLS) with certificate and host-name checks; there is no
 *   fallback to plain text and no trust-all socket factory.
 * - Mail debug logging is never enabled (it would print credentials).
 * - Nothing is sent to any third-party service; the only network peers are the account's
 *   own IMAP/SMTP servers.
 */
class MailRepository(private val store: AccountStore) {

    private val folderCache = ConcurrentHashMap<String, List<MailFolder>>()

    // ---------------------------------------------------------------- connection

    /** Real login to IMAP (and listing) and SMTP (authentication only, nothing is sent). */
    suspend fun testConnection(account: Account, password: String) = withContext(Dispatchers.IO) {
        imap(account, password) { s -> s.defaultFolder.list("*").size }

        val session = Session.getInstance(smtpProperties(account))
        val transport = session.getTransport("smtp")
        try {
            transport.connect(account.smtpHost, account.smtpPort, account.email, password)
        } catch (e: Exception) {
            throw e.toMailFailure(Protocol.SMTP)
        } finally {
            runCatching { transport.close() }
        }
    }

    private fun <T> imap(account: Account, password: String, block: (Store) -> T): T {
        val session = Session.getInstance(imapProperties(account))
        val s = session.getStore("imap")
        try {
            s.connect(account.imapHost, account.imapPort, account.email, password)
            return block(s)
        } catch (e: Exception) {
            throw e.toMailFailure(Protocol.IMAP)
        } finally {
            runCatching { s.close() }
        }
    }

    private fun imapProperties(a: Account) = Properties().apply {
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

    private fun smtpProperties(a: Account) = Properties().apply {
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

    private fun credentials(accountId: String): Pair<Account, String> {
        val account = store.get(accountId) ?: throw MailFailure(FailureKind.ACCOUNT_NOT_FOUND)
        val password = store.password(accountId) ?: throw MailFailure(FailureKind.NO_PASSWORD)
        return account to password
    }

    // ---------------------------------------------------------------- folders

    suspend fun folders(accountId: String, refresh: Boolean = false): List<MailFolder> =
        withContext(Dispatchers.IO) {
            if (!refresh) folderCache[accountId]?.let { return@withContext it }
            val (account, password) = credentials(accountId)
            val list = imap(account, password) { s -> loadFolders(s) }
            folderCache[accountId] = list
            list
        }

    private fun loadFolders(s: Store): List<MailFolder> =
        s.defaultFolder.list("*").mapNotNull { f ->
            if ((f.type and Folder.HOLDS_MESSAGES) == 0) return@mapNotNull null
            val attrs = runCatching { (f as? IMAPFolder)?.attributes?.toList() }.getOrNull().orEmpty()
            MailFolder(f.fullName, FolderRoles.detect(f.fullName, attrs))
        }

    private suspend fun folderPathOrNull(accountId: String, role: FolderRole): String? {
        if (role == FolderRole.INBOX) return "INBOX"
        folders(accountId).firstOrNull { it.role == role }?.let { return it.path }
        return folders(accountId, refresh = true).firstOrNull { it.role == role }?.path
    }

    private suspend fun folderPath(accountId: String, role: FolderRole): String =
        folderPathOrNull(accountId, role) ?: throw MailFailure(FailureKind.FOLDER_NOT_FOUND)

    // ---------------------------------------------------------------- reading

    /** Newest-first page of message headers. [page] is zero based. */
    suspend fun page(
        accountId: String,
        role: FolderRole,
        page: Int,
        pageSize: Int = PAGE_SIZE,
    ): MessagePage {
        val path = folderPath(accountId, role)
        return withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            imap(account, password) { s ->
                val folder = s.getFolder(path) as IMAPFolder
                folder.open(Folder.READ_ONLY)
                try {
                    val end = folder.messageCount - page * pageSize
                    if (end < 1) {
                        MessagePage(emptyList(), false)
                    } else {
                        val start = maxOf(1, end - pageSize + 1)
                        val messages = folder.getMessages(start, end)
                        folder.fetch(messages, envelopeProfile())
                        val items = messages.reversed()
                            .filterNot { it.isSet(Flags.Flag.DELETED) }
                            .map { summarize(folder, it) }
                        MessagePage(items, hasMore = start > 1)
                    }
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        }
    }

    suspend fun message(
        accountId: String,
        role: FolderRole,
        uid: Long,
        markSeen: Boolean,
    ): MessageDetail {
        val path = folderPath(accountId, role)
        return withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            imap(account, password) { s ->
                val folder = s.getFolder(path) as IMAPFolder
                folder.open(if (markSeen) Folder.READ_WRITE else Folder.READ_ONLY)
                try {
                    val msg = folder.getMessageByUID(uid)
                        ?: throw MailFailure(FailureKind.MESSAGE_NOT_FOUND)
                    val content = MessageContent.extract(msg)
                    val mime = msg as MimeMessage
                    val detail = MessageDetail(
                        uid = uid,
                        messageId = header(mime, "Message-ID"),
                        inReplyTo = header(mime, "In-Reply-To"),
                        references = header(mime, "References"),
                        subject = runCatching { msg.subject }.getOrNull().orEmpty(),
                        from = addresses { msg.from }.firstOrNull(),
                        replyTo = addresses { msg.replyTo },
                        to = addresses { msg.getRecipients(Message.RecipientType.TO) },
                        cc = addresses { msg.getRecipients(Message.RecipientType.CC) },
                        bcc = addresses { msg.getRecipients(Message.RecipientType.BCC) },
                        dateMillis = (msg.sentDate ?: msg.receivedDate)?.time,
                        bodyText = content.text,
                        bodyFromHtml = content.fromHtml,
                        hasAttachments = content.hasAttachments,
                    )
                    if (markSeen && !msg.isSet(Flags.Flag.SEEN)) {
                        runCatching { msg.setFlag(Flags.Flag.SEEN, true) }
                    }
                    detail
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        }
    }

    private fun envelopeProfile() = FetchProfile().apply {
        add(FetchProfile.Item.ENVELOPE)
        add(FetchProfile.Item.FLAGS)
        add(UIDFolder.FetchProfileItem.UID)
    }

    private fun summarize(folder: IMAPFolder, m: Message) = MessageSummary(
        uid = folder.getUID(m),
        subject = runCatching { m.subject }.getOrNull().orEmpty(),
        from = addresses { m.from }.firstOrNull(),
        to = addresses { m.getRecipients(Message.RecipientType.TO) },
        dateMillis = runCatching { (m.sentDate ?: m.receivedDate)?.time }.getOrNull(),
        seen = m.isSet(Flags.Flag.SEEN),
        answered = m.isSet(Flags.Flag.ANSWERED),
    )

    private fun header(m: MimeMessage, name: String): String? =
        runCatching { m.getHeader(name)?.firstOrNull() }.getOrNull()
            ?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null }

    private inline fun addresses(read: () -> Array<Address>?): List<MailAddress> =
        try {
            read()?.mapNotNull { it.toMailAddress() }.orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

    private fun Address.toMailAddress(): MailAddress? {
        val ia = this as? InternetAddress ?: return MailAddress(null, toString())
        val email = ia.address ?: return null
        return MailAddress(ia.personal?.takeIf { it.isNotBlank() }, email)
    }

    // ---------------------------------------------------------------- sending / drafts

    /**
     * Sends through SMTP, then (best effort) appends a copy to the Sent folder and removes the
     * draft it came from. A failure of the copy never turns a delivered message into an error.
     */
    suspend fun send(accountId: String, out: OutgoingMessage): SendOutcome =
        withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            if (out.to.isEmpty() && out.cc.isEmpty() && out.bcc.isEmpty()) {
                throw MailFailure(FailureKind.NO_RECIPIENT)
            }
            val session = Session.getInstance(smtpProperties(account))
            val msg = buildMessage(session, account, out)
            val recipients = msg.allRecipients ?: throw MailFailure(FailureKind.NO_RECIPIENT)

            val transport = session.getTransport("smtp")
            try {
                transport.connect(account.smtpHost, account.smtpPort, account.email, password)
                transport.sendMessage(msg, recipients)
            } catch (e: Exception) {
                throw e.toMailFailure(Protocol.SMTP)
            } finally {
                runCatching { transport.close() }
            }

            // From here on the message is delivered; problems are reported softly.
            var copySaved = false
            try {
                folderPathOrNull(accountId, FolderRole.SENT)?.let { sentPath ->
                    imap(account, password) { s ->
                        replaceInFolder(s, sentPath, msg, Flags(Flags.Flag.SEEN), null, null)
                    }
                    copySaved = true
                }
            } catch (e: Exception) {
                copySaved = false
            }
            try {
                if (out.replacesDraftUid != null || out.messageId.isNotBlank()) {
                    folderPathOrNull(accountId, FolderRole.DRAFTS)?.let { draftsPath ->
                        imap(account, password) { s ->
                            removeFromFolder(s, draftsPath, out.messageId, out.replacesDraftUid)
                        }
                    }
                }
            } catch (e: Exception) {
                // A leftover draft is harmless; the message itself was sent.
            }
            SendOutcome(copySaved)
        }

    /** Stores the message in the server's Drafts folder, replacing an earlier save of the same draft. */
    suspend fun saveDraft(accountId: String, out: OutgoingMessage) = withContext(Dispatchers.IO) {
        val (account, password) = credentials(accountId)
        val draftsPath = folderPath(accountId, FolderRole.DRAFTS)
        val session = Session.getInstance(smtpProperties(account))
        val msg = buildMessage(session, account, out)
        imap(account, password) { s ->
            replaceInFolder(
                s, draftsPath, msg,
                Flags().apply { add(Flags.Flag.DRAFT); add(Flags.Flag.SEEN) },
                out.messageId, out.replacesDraftUid
            )
        }
    }

    private fun buildMessage(session: Session, account: Account, out: OutgoingMessage): MimeMessage {
        val msg = FixedIdMimeMessage(session, out.messageId)
        msg.setFrom(InternetAddress(account.email, account.displayName.ifBlank { null }, "UTF-8"))
        if (out.to.isNotEmpty()) msg.setRecipients(Message.RecipientType.TO, parse(out.to))
        if (out.cc.isNotEmpty()) msg.setRecipients(Message.RecipientType.CC, parse(out.cc))
        if (out.bcc.isNotEmpty()) msg.setRecipients(Message.RecipientType.BCC, parse(out.bcc))
        msg.setSubject(out.subject, "UTF-8")
        msg.sentDate = Date()
        out.inReplyTo?.let { msg.setHeader("In-Reply-To", it) }
        out.references?.let { msg.setHeader("References", it) }
        msg.setText(out.body, "UTF-8")
        msg.saveChanges()
        return msg
    }

    private fun parse(list: List<String>): Array<InternetAddress> =
        InternetAddress.parse(list.joinToString(","), false)

    /** Message-ID is fixed up front so a draft keeps its identity across saves. */
    private class FixedIdMimeMessage(session: Session, private val fixedId: String) : MimeMessage(session) {
        override fun updateMessageID() {
            setHeader("Message-ID", fixedId)
        }
    }

    private fun replaceInFolder(
        s: Store,
        path: String,
        msg: MimeMessage,
        flags: Flags,
        removeMessageId: String?,
        removeUid: Long?,
    ) {
        val folder = s.getFolder(path) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            val old = findExisting(folder, removeMessageId, removeUid)
            msg.setFlags(flags, true)
            folder.appendMessages(arrayOf<Message>(msg))
            if (old.isNotEmpty()) {
                folder.setFlags(old.toTypedArray(), Flags(Flags.Flag.DELETED), true)
                folder.expunge()
            }
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun removeFromFolder(s: Store, path: String, messageId: String?, uid: Long?) {
        val folder = s.getFolder(path) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            val old = findExisting(folder, messageId, uid)
            if (old.isNotEmpty()) {
                folder.setFlags(old.toTypedArray(), Flags(Flags.Flag.DELETED), true)
                folder.expunge()
            }
        } finally {
            runCatching { folder.close(false) }
        }
    }

    private fun findExisting(folder: IMAPFolder, messageId: String?, uid: Long?): List<Message> {
        val found = LinkedHashSet<Message>()
        if (!messageId.isNullOrBlank()) {
            folder.search(HeaderTerm("Message-ID", messageId)).forEach { found.add(it) }
        }
        if (uid != null) {
            folder.getMessageByUID(uid)?.let { found.add(it) }
        }
        return found.toList()
    }

    // ---------------------------------------------------------------- new mail check (notifications)

    /**
     * Returns unread inbox messages that arrived since the previous check. The first call only
     * records a baseline, so enabling notifications never floods the user with old mail.
     */
    suspend fun checkNewInbox(accountId: String): List<MessageSummary> = withContext(Dispatchers.IO) {
        val (account, password) = credentials(accountId)
        imap<List<MessageSummary>>(account, password) { s ->
            val folder = s.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            try {
                val validity = folder.uidValidity
                val newMarker = folder.uidNext - 1
                val marker = store.notifyMarker(accountId)

                if (marker == null || marker.validity != validity) {
                    store.setNotifyMarker(accountId, validity, newMarker)
                    emptyList()
                } else if (newMarker <= marker.lastUid) {
                    emptyList()
                } else {
                    val messages = folder.getMessagesByUID(marker.lastUid + 1, UIDFolder.LASTUID)
                        .filterNotNull()
                        .toTypedArray()
                    folder.fetch(messages, envelopeProfile())
                    val fresh = messages
                        .map { summarize(folder, it) }
                        .filter { it.uid > marker.lastUid && !it.seen }
                        .sortedByDescending { it.uid }
                    store.setNotifyMarker(accountId, validity, newMarker)
                    fresh
                }
            } finally {
                runCatching { folder.close(false) }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 30
    }
}
