package com.shegrowsdev.mail.admin

import android.content.ContentValues
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import com.shegrowsdev.mail.mail.Conversations
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MailFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Counts of what is attached to one message (shown on the message screen). */
data class MessageLinks(val registerSeq: Long?, val tasks: Int, val pendingFollowUps: Int) {
    val any: Boolean get() = registerSeq != null || tasks > 0 || pendingFollowUps > 0
}

/**
 * All reads and writes of the administrative data. Every call that takes a [Viewer] filters by it
 * inside the SQL (an employee never receives rows of other accounts) and refuses writes outside
 * the viewer's scope with [FailureKind.NOT_PERMITTED].
 */
class AdminRepository(
    private val helper: AdminDb,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val db: SQLiteDatabase get() = helper.writableDatabase

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) {
        try {
            block()
        } catch (e: MailFailure) {
            throw e
        } catch (e: SQLiteException) {
            throw MailFailure(FailureKind.DATA_ERROR, cause = e)
        }
    }

    private inline fun <T> inTx(block: (SQLiteDatabase) -> T): T {
        val d = db
        d.beginTransaction()
        try {
            val result = block(d)
            d.setTransactionSuccessful()
            return result
        } finally {
            d.endTransaction()
        }
    }

    // ------------------------------------------------------------------ helpers

    private class Where(val sql: String, val args: List<String>)

    private fun rowScope(v: Viewer, accountCol: String, assigneeCol: String?, selected: String? = null): Where = when {
        !v.isAdmin && assigneeCol == null -> Where("$accountCol = ?", listOf(v.accountId))
        !v.isAdmin -> Where("($accountCol = ? OR lower($assigneeCol) = ?)", listOf(v.accountId, v.email.lowercase()))
        selected != null -> Where("$accountCol = ?", listOf(selected))
        else -> Where("1 = 1", emptyList())
    }

    private fun requireAccess(v: Viewer, accountId: String) {
        if (!Permissions.canSeeAccount(v, accountId)) throw MailFailure(FailureKind.NOT_PERMITTED)
    }

    private fun Cursor.strOrNull(i: Int): String? = if (isNull(i)) null else getString(i)
    private fun Cursor.longOrNull(i: Int): Long? = if (isNull(i)) null else getLong(i)

    private inline fun <T> Cursor.mapRows(f: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        use { while (moveToNext()) out.add(f(this)) }
        return out
    }

    private fun like(token: String): String =
        "%" + token.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

    private fun Cursor.readRef(o: Int): MailRef = MailRef(
        id = getLong(o),
        transactionId = getLong(o + 1),
        accountId = getString(o + 2),
        accountEmail = getString(o + 3),
        messageId = strOrNull(o + 4),
        inReplyTo = strOrNull(o + 5),
        folderRole = runCatching { FolderRole.valueOf(getString(o + 6)) }.getOrDefault(FolderRole.OTHER),
        uid = getLong(o + 7),
        subject = getString(o + 8),
        counterpart = getString(o + 9),
        direction = Direction.valueOf(getString(o + 10)),
        dateMillis = longOrNull(o + 11),
        attachmentNames = getString(o + 12).split('\n').filter { it.isNotEmpty() },
        external = getInt(o + 13) != 0,
        createdAt = getLong(o + 14),
    )

    private fun Cursor.readTask(o: Int): TaskItem = TaskItem(
        id = getLong(o),
        refId = getLong(o + 1),
        accountId = getString(o + 2),
        title = getString(o + 3),
        assigneeEmail = getString(o + 4),
        dueAt = longOrNull(o + 5),
        status = TaskStatus.valueOf(getString(o + 6)),
        createdAt = getLong(o + 7),
        updatedAt = getLong(o + 8),
        createdBy = getString(o + 9),
    )

    private fun Cursor.readFollowUp(o: Int): FollowUp = FollowUp(
        id = getLong(o),
        refId = getLong(o + 1),
        taskId = longOrNull(o + 2),
        accountId = getString(o + 3),
        dueAt = getLong(o + 4),
        state = FollowUpState.valueOf(getString(o + 5)),
        note = getString(o + 6),
        notifiedStage = getInt(o + 7),
        createdAt = getLong(o + 8),
        completedAt = longOrNull(o + 9),
    )

    private fun Cursor.readEntry(o: Int): RegisterEntry = RegisterEntry(
        id = getLong(o),
        seq = getLong(o + 1),
        refId = getLong(o + 2),
        direction = Direction.valueOf(getString(o + 3)),
        entryDate = getLong(o + 4),
        subject = getString(o + 5),
        party = getString(o + 6),
        responsibleEmail = getString(o + 7),
        accountId = getString(o + 8),
        createdAt = getLong(o + 9),
    )

    // ------------------------------------------------------------------ message references

    private fun loadRef(d: SQLiteDatabase, id: Long): MailRef =
        d.rawQuery("SELECT $REF_COLS FROM mail_refs r WHERE r.id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.readRef(0) else throw MailFailure(FailureKind.DATA_ERROR) }

    /** Finds the transaction (conversation) a message belongs to, or starts a new one. */
    private fun resolveTransaction(d: SQLiteDatabase, draft: RefDraft, now: Long): Long {
        val ids = ThreadLinker.relatedIds(draft.messageId, draft.inReplyTo, draft.references)
        if (ids.isNotEmpty()) {
            val placeholders = ids.joinToString(",") { "?" }
            val args = arrayOf(draft.accountId) + ids + ids
            val found = d.rawQuery(
                "SELECT transaction_id FROM mail_refs WHERE account_id = ? AND " +
                    "(message_id IN ($placeholders) OR in_reply_to IN ($placeholders)) " +
                    "ORDER BY transaction_id ASC LIMIT 1",
                args
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (found != null) return found
        } else {
            val key = ThreadLinker.threadKey(null, null, null, draft.subject)
            val found = d.rawQuery(
                "SELECT id FROM transactions WHERE account_id = ? AND thread_key = ? ORDER BY id ASC LIMIT 1",
                arrayOf(draft.accountId, key)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
            if (found != null) return found
        }
        val cv = ContentValues().apply {
            put("thread_key", ThreadLinker.threadKey(draft.messageId, draft.inReplyTo, draft.references, draft.subject))
            put("account_id", draft.accountId)
            put("title", draft.subject)
            put("created_at", now)
        }
        return d.insertOrThrow("transactions", null, cv)
    }

    /** Idempotent: the same real message always maps to the same [MailRef]. */
    private fun ensureRef(d: SQLiteDatabase, draft: RefDraft, now: Long): MailRef {
        val msgId = ThreadLinker.normalizeId(draft.messageId)
        val existing: Long? = if (msgId != null) {
            d.rawQuery(
                "SELECT id FROM mail_refs WHERE account_id = ? AND message_id = ? AND direction = ?",
                arrayOf(draft.accountId, msgId, draft.direction.name)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        } else {
            d.rawQuery(
                "SELECT id FROM mail_refs WHERE account_id = ? AND message_id IS NULL AND " +
                    "folder_role = ? AND uid = ? AND direction = ?",
                arrayOf(draft.accountId, draft.folderRole.name, draft.uid.toString(), draft.direction.name)
            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        }
        if (existing != null) {
            if (draft.uid >= 0) {
                val cv = ContentValues().apply {
                    put("folder_role", draft.folderRole.name)
                    put("uid", draft.uid)
                }
                d.update("mail_refs", cv, "id = ?", arrayOf(existing.toString()))
            }
            return loadRef(d, existing)
        }

        val txId = resolveTransaction(d, draft, now)
        val cv = ContentValues().apply {
            put("transaction_id", txId)
            put("account_id", draft.accountId)
            put("account_email", draft.accountEmail)
            if (msgId == null) putNull("message_id") else put("message_id", msgId)
            val reply = ThreadLinker.normalizeId(draft.inReplyTo)
            if (reply == null) putNull("in_reply_to") else put("in_reply_to", reply)
            put("folder_role", draft.folderRole.name)
            put("uid", draft.uid)
            put("subject", draft.subject)
            put("counterpart", draft.counterpart)
            put("direction", draft.direction.name)
            if (draft.dateMillis == null) putNull("msg_date") else put("msg_date", draft.dateMillis)
            put("attachment_names", draft.attachmentNames.joinToString("\n"))
            put("external", if (draft.external) 1 else 0)
            put("created_at", now)
        }
        return loadRef(d, d.insertOrThrow("mail_refs", null, cv))
    }

    /** Remembers where a message was found after it moved between folders. */
    suspend fun updateLocation(refId: Long, role: FolderRole, uid: Long) = io {
        val cv = ContentValues().apply {
            put("folder_role", role.name)
            put("uid", uid)
        }
        db.update("mail_refs", cv, "id = ?", arrayOf(refId.toString()))
        Unit
    }

    /** What is already attached to this message (register number, tasks, pending follow-ups). */
    suspend fun linksFor(viewer: Viewer, accountId: String, messageId: String?, direction: Direction): MessageLinks = io {
        val id = ThreadLinker.normalizeId(messageId)
        if (id == null || !Permissions.canSeeAccount(viewer, accountId)) return@io MessageLinks(null, 0, 0)
        val d = db
        val refId = d.rawQuery(
            "SELECT id FROM mail_refs WHERE account_id = ? AND message_id = ? AND direction = ?",
            arrayOf(accountId, id, direction.name)
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else null } ?: return@io MessageLinks(null, 0, 0)
        val arg = arrayOf(refId.toString())
        val seq = d.rawQuery("SELECT seq FROM register_entries WHERE ref_id = ?", arg)
            .use { c -> if (c.moveToFirst()) c.getLong(0) else null }
        val tasks = DatabaseUtils.longForQuery(d, "SELECT COUNT(*) FROM tasks WHERE ref_id = ?", arg).toInt()
        val follow = DatabaseUtils.longForQuery(
            d, "SELECT COUNT(*) FROM follow_ups WHERE ref_id = ? AND state = 'PENDING'", arg
        ).toInt()
        MessageLinks(seq, tasks, follow)
    }

    // ------------------------------------------------------------------ tasks

    private fun addEvent(d: SQLiteDatabase, taskId: Long, at: Long, actor: String, kind: String, old: String?, new: String?) {
        val cv = ContentValues().apply {
            put("task_id", taskId)
            put("at", at)
            put("actor", actor)
            put("kind", kind)
            if (old == null) putNull("old_value") else put("old_value", old)
            if (new == null) putNull("new_value") else put("new_value", new)
        }
        d.insertOrThrow("task_events", null, cv)
    }

    suspend fun createTask(
        viewer: Viewer,
        draft: RefDraft,
        title: String,
        assigneeEmail: String,
        dueAt: Long?,
    ): Long = io {
        requireAccess(viewer, draft.accountId)
        val cleanTitle = title.trim()
        val cleanAssignee = assigneeEmail.trim()
        if (cleanTitle.isEmpty() || cleanAssignee.isEmpty()) throw MailFailure(FailureKind.DATA_ERROR)
        inTx { d ->
            val now = clock()
            val ref = ensureRef(d, draft, now)
            val cv = ContentValues().apply {
                put("ref_id", ref.id)
                put("account_id", ref.accountId)
                put("title", cleanTitle)
                put("assignee_email", cleanAssignee)
                if (dueAt == null) putNull("due_at") else put("due_at", dueAt)
                put("status", TaskStatus.OPEN.name)
                put("created_at", now)
                put("updated_at", now)
                put("created_by", viewer.email)
            }
            val id = d.insertOrThrow("tasks", null, cv)
            addEvent(d, id, now, viewer.email, "CREATED", null, cleanTitle)
            id
        }
    }

    private fun queryTasks(
        d: SQLiteDatabase,
        viewer: Viewer,
        extraWhere: String,
        extraArgs: List<String>,
        order: String,
    ): List<TaskWithRef> {
        val scope = rowScope(viewer, "t.account_id", "t.assignee_email")
        val sql = "SELECT $REF_COLS, t.id, t.ref_id, t.account_id, t.title, t.assignee_email, t.due_at, " +
            "t.status, t.created_at, t.updated_at, t.created_by " +
            "FROM tasks t JOIN mail_refs r ON r.id = t.ref_id " +
            "WHERE ${scope.sql} $extraWhere ORDER BY $order"
        return d.rawQuery(sql, (scope.args + extraArgs).toTypedArray())
            .mapRows { c -> TaskWithRef(c.readTask(15), c.readRef(0)) }
    }

    suspend fun listTasks(viewer: Viewer, filter: TaskFilter, now: Long): List<TaskWithRef> = io {
        val (where, args) = when (filter) {
            TaskFilter.OPEN -> " AND t.status != 'DONE'" to emptyList<String>()
            TaskFilter.DONE -> " AND t.status = 'DONE'" to emptyList<String>()
            TaskFilter.OVERDUE ->
                " AND t.status != 'DONE' AND t.due_at IS NOT NULL AND t.due_at < ?" to listOf(now.toString())
            TaskFilter.ALL -> "" to emptyList<String>()
        }
        val order = if (filter == TaskFilter.DONE) "t.updated_at DESC"
        else "CASE WHEN t.due_at IS NULL THEN 1 ELSE 0 END, t.due_at ASC, t.id DESC"
        queryTasks(db, viewer, where, args, order)
    }

    suspend fun task(viewer: Viewer, taskId: Long): TaskWithRef? = io {
        queryTasks(db, viewer, " AND t.id = ?", listOf(taskId.toString()), "t.id").firstOrNull()
    }

    suspend fun tasksInTransaction(viewer: Viewer, transactionId: Long): List<TaskWithRef> = io {
        queryTasks(db, viewer, " AND r.transaction_id = ?", listOf(transactionId.toString()), "t.id DESC")
    }

    suspend fun taskEvents(viewer: Viewer, taskId: Long): List<TaskEvent> = io {
        val d = db
        queryTasks(d, viewer, " AND t.id = ?", listOf(taskId.toString()), "t.id").firstOrNull()
            ?: throw MailFailure(FailureKind.NOT_PERMITTED)
        d.rawQuery(
            "SELECT id, task_id, at, actor, kind, old_value, new_value FROM task_events " +
                "WHERE task_id = ? ORDER BY at ASC, id ASC",
            arrayOf(taskId.toString())
        ).mapRows { c ->
            TaskEvent(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.strOrNull(5), c.strOrNull(6))
        }
    }

    /** Changes only the listed fields; the message link is never touched (and a trigger forbids it). */
    suspend fun updateTask(viewer: Viewer, taskId: Long, patch: TaskPatch): TaskWithRef = io {
        inTx { d ->
            val current = queryTasks(d, viewer, " AND t.id = ?", listOf(taskId.toString()), "t.id").firstOrNull()
                ?: throw MailFailure(FailureKind.NOT_PERMITTED)
            val t = current.task
            val now = clock()
            val cv = ContentValues()

            val newTitle = patch.title?.trim()
            if (!newTitle.isNullOrEmpty() && newTitle != t.title) {
                cv.put("title", newTitle)
                addEvent(d, taskId, now, viewer.email, "TITLE", t.title, newTitle)
            }
            val newAssignee = patch.assigneeEmail?.trim()
            if (!newAssignee.isNullOrEmpty() && !newAssignee.equals(t.assigneeEmail, ignoreCase = true)) {
                cv.put("assignee_email", newAssignee)
                addEvent(d, taskId, now, viewer.email, "ASSIGNEE", t.assigneeEmail, newAssignee)
            }
            if (patch.setDue && patch.dueAt != t.dueAt) {
                val due = patch.dueAt
                if (due == null) cv.putNull("due_at") else cv.put("due_at", due)
                addEvent(d, taskId, now, viewer.email, "DUE", t.dueAt?.toString(), due?.toString())
            }
            val newStatus = patch.status
            if (newStatus != null && newStatus != t.status) {
                cv.put("status", newStatus.name)
                addEvent(d, taskId, now, viewer.email, "STATUS", t.status.name, newStatus.name)
            }
            if (cv.size() > 0) {
                cv.put("updated_at", now)
                d.update("tasks", cv, "id = ?", arrayOf(taskId.toString()))
            }
            queryTasks(d, viewer, " AND t.id = ?", listOf(taskId.toString()), "t.id").first()
        }
    }

    // ------------------------------------------------------------------ official register

    private fun nextSeq(d: SQLiteDatabase): Long {
        // Runs inside the caller's transaction: the increment and the read cannot interleave with another writer.
        d.execSQL("UPDATE counters SET value = value + 1 WHERE name = 'register'")
        return DatabaseUtils.longForQuery(d, "SELECT value FROM counters WHERE name = 'register'", null)
    }

    private fun entryForRef(d: SQLiteDatabase, refId: Long): RegisterEntry? =
        d.rawQuery(
            "SELECT id, seq, ref_id, direction, entry_date, subject, party, responsible_email, account_id, created_at " +
                "FROM register_entries WHERE ref_id = ?",
            arrayOf(refId.toString())
        ).use { c -> if (c.moveToFirst()) c.readEntry(0) else null }

    private fun registerInTx(d: SQLiteDatabase, ref: MailRef, responsible: String, now: Long): RegisterResult {
        entryForRef(d, ref.id)?.let { return RegisterResult(it, created = false) }
        val seq = nextSeq(d)
        val entryDate = ref.dateMillis ?: now
        val cv = ContentValues().apply {
            put("seq", seq)
            put("ref_id", ref.id)
            put("direction", ref.direction.name)
            put("entry_date", entryDate)
            put("subject", ref.subject)
            put("party", ref.counterpart)
            put("responsible_email", responsible)
            put("account_id", ref.accountId)
            put("created_at", now)
        }
        val id = d.insertOrThrow("register_entries", null, cv)
        return RegisterResult(
            RegisterEntry(id, seq, ref.id, ref.direction, entryDate, ref.subject, ref.counterpart, responsible, ref.accountId, now),
            created = true
        )
    }

    /** Registers a message in the official log. Registering the same message twice returns the first entry. */
    suspend fun register(viewer: Viewer, draft: RefDraft, responsibleEmail: String): RegisterResult = io {
        requireAccess(viewer, draft.accountId)
        inTx { d ->
            val now = clock()
            registerInTx(d, ensureRef(d, draft, now), responsibleEmail.trim(), now)
        }
    }

    suspend fun listRegister(
        viewer: Viewer,
        query: String,
        direction: Direction?,
        limit: Int = 200,
    ): List<RegisterRow> = io {
        val scope = rowScope(viewer, "e.account_id", "e.responsible_email")
        val args = ArrayList<String>(scope.args)
        val sql = StringBuilder(
            "SELECT $REF_COLS, e.id, e.seq, e.ref_id, e.direction, e.entry_date, e.subject, e.party, " +
                "e.responsible_email, e.account_id, e.created_at, " +
                "(SELECT COUNT(*) FROM tasks t2 JOIN mail_refs r2 ON r2.id = t2.ref_id " +
                "WHERE r2.transaction_id = r.transaction_id) " +
                "FROM register_entries e JOIN mail_refs r ON r.id = e.ref_id WHERE ${scope.sql}"
        )
        if (direction != null) {
            sql.append(" AND e.direction = ?")
            args.add(direction.name)
        }
        for (token in query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            sql.append(
                " AND (e.subject LIKE ? ESCAPE '\\' OR e.party LIKE ? ESCAPE '\\' OR " +
                    "e.responsible_email LIKE ? ESCAPE '\\' OR CAST(e.seq AS TEXT) LIKE ? ESCAPE '\\')"
            )
            val pattern = like(token)
            repeat(4) { args.add(pattern) }
        }
        sql.append(" ORDER BY e.seq DESC LIMIT ?")
        args.add(limit.toString())
        db.rawQuery(sql.toString(), args.toTypedArray())
            .mapRows { c -> RegisterRow(c.readEntry(15), c.readRef(0), c.getInt(25)) }
    }

    // ------------------------------------------------------------------ follow-ups

    suspend fun createFollowUp(viewer: Viewer, draft: RefDraft, dueAt: Long, note: String, taskId: Long? = null): Long = io {
        requireAccess(viewer, draft.accountId)
        inTx { d ->
            val now = clock()
            val ref = ensureRef(d, draft, now)
            val cv = ContentValues().apply {
                put("ref_id", ref.id)
                if (taskId == null) putNull("task_id") else put("task_id", taskId)
                put("account_id", ref.accountId)
                put("due_at", dueAt)
                put("state", FollowUpState.PENDING.name)
                put("note", note.trim())
                put("notified_stage", 0)
                put("created_at", now)
            }
            d.insertOrThrow("follow_ups", null, cv)
        }
    }

    private fun queryFollowUps(
        d: SQLiteDatabase,
        scope: Where,
        extraWhere: String,
        extraArgs: List<String>,
        order: String,
        limit: Int,
    ): List<FollowUpWithRef> {
        val sql = "SELECT $REF_COLS, f.id, f.ref_id, f.task_id, f.account_id, f.due_at, f.state, f.note, " +
            "f.notified_stage, f.created_at, f.completed_at " +
            "FROM follow_ups f JOIN mail_refs r ON r.id = f.ref_id " +
            "WHERE ${scope.sql} $extraWhere ORDER BY $order LIMIT $limit"
        return d.rawQuery(sql, (scope.args + extraArgs).toTypedArray())
            .mapRows { c -> FollowUpWithRef(c.readFollowUp(15), c.readRef(0)) }
    }

    suspend fun listFollowUps(viewer: Viewer, section: FollowUpSection, now: Long): List<FollowUpWithRef> = io {
        val scope = rowScope(viewer, "f.account_id", null)
        when (section) {
            FollowUpSection.OVERDUE ->
                queryFollowUps(db, scope, " AND f.state = 'PENDING' AND f.due_at < ?", listOf(now.toString()), "f.due_at ASC", 500)
            FollowUpSection.UPCOMING ->
                queryFollowUps(db, scope, " AND f.state = 'PENDING' AND f.due_at >= ?", listOf(now.toString()), "f.due_at ASC", 500)
            FollowUpSection.CLOSED ->
                queryFollowUps(db, scope, " AND f.state != 'PENDING'", emptyList(), "f.completed_at DESC", 50)
        }
    }

    suspend fun updateFollowUp(viewer: Viewer, id: Long, dueAt: Long?, note: String?) = io {
        inTx { d ->
            val scope = rowScope(viewer, "f.account_id", null)
            val current = queryFollowUps(d, scope, " AND f.id = ?", listOf(id.toString()), "f.id", 1).firstOrNull()
                ?: throw MailFailure(FailureKind.NOT_PERMITTED)
            val cv = ContentValues()
            if (dueAt != null && dueAt != current.followUp.dueAt) {
                cv.put("due_at", dueAt)
                cv.put("notified_stage", 0) // a new time deserves a fresh reminder
            }
            if (note != null && note.trim() != current.followUp.note) cv.put("note", note.trim())
            if (cv.size() > 0) d.update("follow_ups", cv, "id = ? AND state = 'PENDING'", arrayOf(id.toString()))
        }
        Unit
    }

    suspend fun closeFollowUp(viewer: Viewer, id: Long, state: FollowUpState) = io {
        require(state != FollowUpState.PENDING)
        inTx { d ->
            val scope = rowScope(viewer, "f.account_id", null)
            queryFollowUps(d, scope, " AND f.id = ?", listOf(id.toString()), "f.id", 1).firstOrNull()
                ?: throw MailFailure(FailureKind.NOT_PERMITTED)
            val cv = ContentValues().apply {
                put("state", state.name)
                put("completed_at", clock())
            }
            d.update("follow_ups", cv, "id = ? AND state = 'PENDING'", arrayOf(id.toString()))
        }
        Unit
    }

    /** Background use (no viewer): follow-ups whose next reminder stage has not been shown yet. */
    suspend fun followUpsToNotify(now: Long, leadMillis: Long): List<Pair<FollowUpWithRef, Int>> = io {
        queryFollowUps(
            db, Where("1 = 1", emptyList()),
            " AND f.state = 'PENDING' AND f.notified_stage < 2 AND f.due_at <= ?",
            listOf((now + leadMillis).toString()), "f.due_at ASC", 500
        ).mapNotNull { item ->
            val stage = ReminderRules.stageFor(item.followUp.dueAt, now, leadMillis)
            if (ReminderRules.shouldNotify(stage, item.followUp.notifiedStage)) item to stage else null
        }
    }

    suspend fun markNotified(ids: List<Long>, stage: Int) = io {
        if (ids.isNotEmpty()) {
            val cv = ContentValues().apply { put("notified_stage", stage) }
            inTx { d ->
                for (id in ids) {
                    d.update("follow_ups", cv, "id = ? AND notified_stage < ?", arrayOf(id.toString(), stage.toString()))
                }
            }
        }
        Unit
    }

    // ------------------------------------------------------------------ recording sent mail

    /**
     * Called after a message was really sent. Replies also record the message they answer (so
     * response times can be computed later) and finish the pending follow-ups of that message.
     * Optionally registers the sent message in the official log.
     */
    suspend fun recordSent(
        viewer: Viewer,
        original: RefDraft?,
        sent: RefDraft,
        register: Boolean,
        responsibleEmail: String,
    ): RegisterResult? = io {
        requireAccess(viewer, sent.accountId)
        inTx { d ->
            val now = clock()
            val originalRef = original?.let { ensureRef(d, it, now) }
            val sentRef = ensureRef(d, sent, now)
            if (originalRef != null) {
                val cv = ContentValues().apply {
                    put("state", FollowUpState.DONE.name)
                    put("completed_at", now)
                }
                d.update("follow_ups", cv, "ref_id = ? AND state = 'PENDING'", arrayOf(originalRef.id.toString()))
            }
            if (register) registerInTx(d, sentRef, responsibleEmail.trim(), now) else null
        }
    }

    // ------------------------------------------------------------------ statistics inputs

    suspend fun statRefs(viewer: Viewer, accountFilter: String?): List<StatRef> = io {
        val scope = rowScope(viewer, "r.account_id", null, accountFilter)
        db.rawQuery(
            "SELECT r.id, r.transaction_id, r.account_id, r.direction, r.message_id, r.in_reply_to, r.msg_date, r.external " +
                "FROM mail_refs r WHERE ${scope.sql}",
            scope.args.toTypedArray()
        ).mapRows { c ->
            StatRef(
                c.getLong(0), c.getLong(1), c.getString(2), Direction.valueOf(c.getString(3)),
                c.strOrNull(4), c.strOrNull(5), c.longOrNull(6), c.getInt(7) != 0
            )
        }
    }

    suspend fun openItems(viewer: Viewer, accountFilter: String?): List<OpenItem> = io {
        val scope = rowScope(viewer, "r.account_id", null, accountFilter)
        val args = scope.args.toTypedArray()
        val taskDues = LinkedHashMap<Long, MutableList<Long?>>()
        val followDues = LinkedHashMap<Long, MutableList<Long>>()
        db.rawQuery(
            "SELECT r.transaction_id, t.due_at FROM tasks t JOIN mail_refs r ON r.id = t.ref_id " +
                "WHERE t.status != 'DONE' AND ${scope.sql}", args
        ).mapRows { c -> taskDues.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.longOrNull(1)) }
        db.rawQuery(
            "SELECT r.transaction_id, f.due_at FROM follow_ups f JOIN mail_refs r ON r.id = f.ref_id " +
                "WHERE f.state = 'PENDING' AND ${scope.sql}", args
        ).mapRows { c -> followDues.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getLong(1)) }
        (taskDues.keys + followDues.keys).distinct().map { id ->
            OpenItem(id, taskDues[id].orEmpty(), followDues[id].orEmpty())
        }
    }

    companion object {
        private const val REF_COLS =
            "r.id, r.transaction_id, r.account_id, r.account_email, r.message_id, r.in_reply_to, r.folder_role, " +
                "r.uid, r.subject, r.counterpart, r.direction, r.msg_date, r.attachment_names, r.external, r.created_at"

        /** Subject without reply/forward prefixes, used for display of a transaction title. */
        fun titleOf(subject: String): String = Conversations.normalizeSubject(subject)
    }
}
