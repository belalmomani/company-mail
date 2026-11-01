package com.shegrowsdev.mail.mail

import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.data.AccountStore
import com.sun.mail.imap.IMAPFolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import com.sun.mail.imap.IMAPStore
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent
import kotlinx.coroutines.Job
import kotlin.coroutines.coroutineContext
import java.util.concurrent.ConcurrentHashMap
import javax.mail.Address
import javax.mail.FetchProfile
import javax.mail.Flags
import javax.mail.Folder
import javax.mail.Message
import javax.mail.MessagingException
import javax.mail.Session
import javax.mail.Store
import javax.mail.UIDFolder
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeMessage
import javax.mail.search.AndTerm
import javax.mail.search.BodyTerm
import javax.mail.search.ComparisonTerm
import javax.mail.search.FromStringTerm
import javax.mail.search.HeaderTerm
import javax.mail.search.OrTerm
import javax.mail.search.ReceivedDateTerm
import javax.mail.search.RecipientStringTerm
import javax.mail.search.SearchTerm
import javax.mail.search.SentDateTerm
import javax.mail.search.SubjectTerm

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

    private fun imapProperties(a: Account) = MailProperties.imap(a)

    private fun smtpProperties(a: Account) = MailProperties.smtp(a)

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
        // Archive and Trash may legitimately not exist yet (Archive is created on first use).
        val path = folderPathOrNull(accountId, role)
            ?: if (role == FolderRole.ARCHIVE || role == FolderRole.TRASH) return MessagePage(emptyList(), false)
            else throw MailFailure(FailureKind.FOLDER_NOT_FOUND)
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
                        attachments = content.attachments,
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
        MimeBuilder.fillContent(msg, out)
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

    // ---------------------------------------------------------------- attachments (receiving)

    /**
     * Downloads one attachment of a received message into [dest] (written to a temporary file first,
     * so a failed or interrupted download never leaves a partial file under the final name).
     */
    suspend fun downloadAttachment(
        accountId: String,
        role: FolderRole,
        uid: Long,
        index: Int,
        dest: File,
    ) {
        val path = folderPath(accountId, role)
        withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            imap(account, password) { s ->
                val folder = s.getFolder(path) as IMAPFolder
                folder.open(Folder.READ_ONLY)
                val tmp = File(dest.parentFile, dest.name + ".part")
                try {
                    val msg = folder.getMessageByUID(uid) ?: throw MailFailure(FailureKind.MESSAGE_NOT_FOUND)
                    val part = MessageContent.attachmentParts(msg).getOrNull(index)
                        ?: throw MailFailure(FailureKind.ATTACH_NOT_FOUND)
                    dest.parentFile?.mkdirs()
                    var total = 0L
                    part.inputStream.use { input ->
                        tmp.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_DOWNLOAD_BYTES) throw MailFailure(FailureKind.ATTACH_DOWNLOAD)
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    if (dest.exists()) dest.delete()
                    if (!tmp.renameTo(dest)) throw MailFailure(FailureKind.ATTACH_DOWNLOAD)
                } catch (e: Exception) {
                    tmp.delete()
                    val failure = e.toMailFailure(Protocol.IMAP)
                    throw if (failure.kind == FailureKind.UNKNOWN) MailFailure(FailureKind.ATTACH_DOWNLOAD, cause = e) else failure
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- search

    /**
     * Searches one folder. The server searches subject, sender, recipient and body. If the server
     * cannot run the search (for example it rejects a non-Latin search string), the newest
     * [FALLBACK_SCAN] messages are filtered on this device by subject, sender and recipients only;
     * [SearchResult.partialScan] tells the UI so it can say so.
     */
    suspend fun search(
        accountId: String,
        role: FolderRole,
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): SearchResult {
        val q = query.trim()
        if (q.isEmpty()) return SearchResult(emptyList(), false)
        val path = folderPathOrNull(accountId, role) ?: return SearchResult(emptyList(), false)
        return withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            imap(account, password) { s ->
                val folder = s.getFolder(path) as IMAPFolder
                folder.open(Folder.READ_ONLY)
                try {
                    val serverHits: Array<Message>? = try {
                        folder.search(
                            OrTerm(
                                arrayOf<SearchTerm>(
                                    SubjectTerm(q),
                                    FromStringTerm(q),
                                    RecipientStringTerm(Message.RecipientType.TO, q),
                                    BodyTerm(q),
                                )
                            )
                        )
                    } catch (e: MessagingException) {
                        null
                    }

                    if (serverHits != null) {
                        val newest = serverHits.takeLast(limit).toTypedArray()
                        folder.fetch(newest, envelopeProfile())
                        val items = newest.reversed()
                            .filterNot { it.isSet(Flags.Flag.DELETED) }
                            .map { summarize(folder, it) }
                        SearchResult(items, partialScan = false)
                    } else {
                        val total = folder.messageCount
                        if (total < 1) {
                            SearchResult(emptyList(), partialScan = true)
                        } else {
                            val start = maxOf(1, total - FALLBACK_SCAN + 1)
                            val messages = folder.getMessages(start, total)
                            folder.fetch(messages, envelopeProfile())
                            val items = messages.reversed()
                                .filterNot { it.isSet(Flags.Flag.DELETED) }
                                .map { summarize(folder, it) }
                                .filter { SearchMatch.matches(it, q) }
                                .take(limit)
                            SearchResult(items, partialScan = true)
                        }
                    }
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- delete / archive

    /** True when the account has a folder with this role on the server. */
    suspend fun hasFolder(accountId: String, role: FolderRole): Boolean =
        folderPathOrNull(accountId, role) != null

    /**
     * Moves the message to Trash; permanently deletes it when it is already in Trash or the
     * account has no Trash folder. The copy to Trash is made before anything is removed.
     */
    suspend fun delete(accountId: String, role: FolderRole, uid: Long): DeleteOutcome {
        val src = folderPath(accountId, role)
        val trash = if (role == FolderRole.TRASH) null else folderPathOrNull(accountId, FolderRole.TRASH)
        withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            imap(account, password) { s -> moveOrExpunge(s, src, uid, trash) }
        }
        return if (trash != null) DeleteOutcome.MOVED_TO_TRASH else DeleteOutcome.DELETED_PERMANENTLY
    }

    /** Moves the message to the Archive folder, creating "Archive" on the server if it does not exist. */
    suspend fun archive(accountId: String, role: FolderRole, uid: Long) {
        val src = folderPath(accountId, role)
        val existing = folderPathOrNull(accountId, FolderRole.ARCHIVE)
        val target = existing ?: FolderRoles.siblingPath(folders(accountId).map { it.path }, ARCHIVE_NAME)
        withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            imap(account, password) { s ->
                if (existing == null) {
                    val f = s.getFolder(target)
                    if (!f.exists()) f.create(Folder.HOLDS_MESSAGES)
                    runCatching { f.isSubscribed = true }
                }
                moveOrExpunge(s, src, uid, target)
            }
        }
        if (existing == null) folderCache.remove(accountId)
    }

    private fun moveOrExpunge(s: Store, srcPath: String, uid: Long, destPath: String?) {
        val folder = s.getFolder(srcPath) as IMAPFolder
        folder.open(Folder.READ_WRITE)
        try {
            val msg = folder.getMessageByUID(uid) ?: throw MailFailure(FailureKind.MESSAGE_NOT_FOUND)
            if (destPath != null) {
                // Copy first: if this fails the original is untouched.
                folder.copyMessages(arrayOf(msg), s.getFolder(destPath))
            }
            msg.setFlag(Flags.Flag.DELETED, true)
            try {
                folder.expunge(arrayOf(msg)) // UIDPLUS: only this message
            } catch (e: MessagingException) {
                folder.expunge()
            }
        } finally {
            runCatching { folder.close(false) }
        }
    }


    // ---------------------------------------------------------------- locate / statistics

    /**
     * Finds a message again after it may have moved (archive, trash). The stored folder/UID is checked
     * first (and must still carry the same Message-ID); otherwise the standard folders are searched by
     * Message-ID. Returns the folder role and UID to open, or throws MESSAGE_NOT_FOUND.
     */
    suspend fun locate(accountId: String, messageId: String?, role: FolderRole, uid: Long): Pair<FolderRole, Long> {
        val candidates = listOf(role) + listOf(
            FolderRole.INBOX, FolderRole.SENT, FolderRole.ARCHIVE, FolderRole.DRAFTS, FolderRole.SPAM, FolderRole.TRASH
        ).filter { it != role }
        val paths = candidates.mapNotNull { r -> folderPathOrNull(accountId, r)?.let { r to it } }
        return withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            imap(account, password) { s ->
                for ((r, path) in paths) {
                    val folder = s.getFolder(path) as IMAPFolder
                    folder.open(Folder.READ_ONLY)
                    try {
                        if (r == role && uid >= 0) {
                            val m = folder.getMessageByUID(uid)
                            if (m != null && (messageId == null ||
                                    header(m as MimeMessage, "Message-ID")?.lowercase() == messageId.lowercase())
                            ) return@imap r to uid
                        }
                        if (messageId != null) {
                            val hits = folder.search(HeaderTerm("Message-ID", messageId))
                                .filter { it is MimeMessage && header(it, "Message-ID")?.lowercase() == messageId.lowercase() }
                            hits.firstOrNull()?.let { return@imap r to folder.getUID(it) }
                        }
                    } finally {
                        runCatching { folder.close(false) }
                    }
                }
                throw MailFailure(FailureKind.MESSAGE_NOT_FOUND)
            }
        }
    }

    /**
     * Number of messages in a folder inside a period (whole days; the server compares days in its own
     * time zone). Inbox/Archive use the received date, Sent the sent date. A folder that does not exist
     * on the server counts as 0.
     */
    suspend fun countInPeriod(accountId: String, role: FolderRole, fromMillis: Long, toExclusiveMillis: Long): Int {
        val path = folderPathOrNull(accountId, role) ?: return 0
        return withContext(Dispatchers.IO) {
            val (account, password) = credentials(accountId)
            imap(account, password) { s ->
                val folder = s.getFolder(path) as IMAPFolder
                folder.open(Folder.READ_ONLY)
                try {
                    val from = Date(fromMillis)
                    val to = Date(toExclusiveMillis)
                    val term: SearchTerm = if (role == FolderRole.SENT) {
                        AndTerm(SentDateTerm(ComparisonTerm.GE, from), SentDateTerm(ComparisonTerm.LT, to))
                    } else {
                        AndTerm(ReceivedDateTerm(ComparisonTerm.GE, from), ReceivedDateTerm(ComparisonTerm.LT, to))
                    }
                    folder.search(term).count { !it.isSet(Flags.Flag.DELETED) }
                } finally {
                    runCatching { folder.close(false) }
                }
            }
        }
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

    /**
     * Holds an IMAP IDLE connection to the Inbox and calls [onChange] whenever the server reports new messages.
     * Returns false at once when the server has no IDLE support. Otherwise runs until the coroutine is cancelled
     * (returns normally) or the connection fails (throws a [MailFailure]); the caller reconnects.
     */
    suspend fun watchInbox(accountId: String, onChange: () -> Unit): Boolean = withContext(Dispatchers.IO) {
        val (account, password) = credentials(accountId)
        val job = coroutineContext[Job]!!
        val s = Session.getInstance(MailProperties.idle(account)).getStore("imap")
        // Closing the store from outside is the only way to stop a blocking idle().
        val handle = job.invokeOnCompletion { runCatching { s.close() } }
        val keepAlive = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "idle-keepalive").apply { isDaemon = true } }
        try {
            s.connect(account.imapHost, account.imapPort, account.email, password)
            if (!(s as IMAPStore).hasCapability("IDLE")) return@withContext false
            val folder = s.getFolder("INBOX") as IMAPFolder
            folder.open(Folder.READ_ONLY)
            folder.addMessageCountListener(object : MessageCountAdapter() {
                override fun messagesAdded(e: MessageCountEvent) = onChange()
            })
            // Servers drop silent connections after ~30 min; NOOP every 8 min ends the current IDLE, which is then restarted.
            keepAlive.scheduleWithFixedDelay({
                runCatching { folder.doCommand(IMAPFolder.ProtocolCommand { p -> p.simpleCommand("NOOP", null); null }) }
            }, 8, 8, TimeUnit.MINUTES)
            while (job.isActive) folder.idle()
            true
        } catch (e: Exception) {
            if (!job.isActive) true else throw e.toMailFailure(Protocol.IMAP)
        } finally {
            handle.dispose()
            keepAlive.shutdownNow()
            runCatching { s.close() }
        }
    }

    companion object {
        const val PAGE_SIZE = 30
        const val SEARCH_LIMIT = 100
        const val FALLBACK_SCAN = 300
        const val ARCHIVE_NAME = "Archive"
        private const val MAX_DOWNLOAD_BYTES = 60L * 1024 * 1024
    }
}
