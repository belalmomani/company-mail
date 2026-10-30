package com.shegrowsdev.mail.admin

import com.shegrowsdev.mail.data.Account
import com.shegrowsdev.mail.data.AccountRole
import com.shegrowsdev.mail.mail.FolderRole

enum class Direction { INCOMING, OUTGOING }
enum class TaskStatus { OPEN, IN_PROGRESS, DONE }
enum class FollowUpState { PENDING, DONE, CANCELLED }
enum class TaskFilter { OPEN, DONE, OVERDUE, ALL }
enum class FollowUpSection { OVERDUE, UPCOMING, CLOSED }

/** Who is looking at the administrative data. Every repository read and write is scoped by it. */
data class Viewer(val accountId: String, val email: String, val role: AccountRole) {
    val isAdmin: Boolean get() = role == AccountRole.ADMIN

    companion object {
        fun of(active: Account): Viewer = Viewer(active.id, active.email, active.role)
    }
}

/** Visibility rules, kept free of Android so they can be unit tested. */
object Permissions {
    fun visibleAccounts(viewer: Viewer, all: List<Account>): List<Account> =
        if (viewer.isAdmin) all else all.filter { it.id == viewer.accountId }

    fun canSeeAccount(viewer: Viewer, accountId: String): Boolean =
        viewer.isAdmin || viewer.accountId == accountId

    /** Task / register rows: own account's rows, or rows assigned to the viewer's address. */
    fun canSeeRow(viewer: Viewer, rowAccountId: String, assigneeEmail: String?): Boolean =
        viewer.isAdmin || rowAccountId == viewer.accountId ||
            (assigneeEmail != null && assigneeEmail.equals(viewer.email, ignoreCase = true))
}

/**
 * Pointer to one real mail message. The message stays on the mail server; this row only remembers
 * how to find it again. Tasks, register entries and follow-ups reference a [MailRef], never the
 * other way round, so editing them cannot detach them from their message.
 */
data class MailRef(
    val id: Long,
    val transactionId: Long,
    val accountId: String,
    val accountEmail: String,
    /** RFC 5322 Message-ID (lower-cased); the stable way to find the message after it was moved. */
    val messageId: String?,
    val inReplyTo: String?,
    /** Last known location. The UID changes when a message is moved; [messageId] is then used to find it. */
    val folderRole: FolderRole,
    val uid: Long,
    val subject: String,
    /** The other party: sender of an incoming message, first recipient of an outgoing one. */
    val counterpart: String,
    val direction: Direction,
    val dateMillis: Long?,
    val attachmentNames: List<String>,
    /** True when the other party's domain is not one of the company's account domains (at record time). */
    val external: Boolean,
    val createdAt: Long,
)

/** What is needed to record a message; the repository turns it into a [MailRef]. */
data class RefDraft(
    val accountId: String,
    val accountEmail: String,
    val messageId: String?,
    val inReplyTo: String?,
    val references: String?,
    val folderRole: FolderRole,
    val uid: Long,
    val subject: String,
    val counterpart: String,
    val direction: Direction,
    val dateMillis: Long?,
    val attachmentNames: List<String>,
    val external: Boolean,
)

data class TaskItem(
    val id: Long,
    val refId: Long,
    val accountId: String,
    val title: String,
    val assigneeEmail: String,
    val dueAt: Long?,
    val status: TaskStatus,
    val createdAt: Long,
    val updatedAt: Long,
    val createdBy: String,
) {
    /** Overdue only when a due date exists and the task is not finished. */
    fun isOverdue(now: Long): Boolean = status != TaskStatus.DONE && dueAt != null && dueAt < now
}

data class TaskWithRef(val task: TaskItem, val ref: MailRef)

data class TaskEvent(
    val id: Long,
    val taskId: Long,
    val at: Long,
    val actor: String,
    /** CREATED, TITLE, ASSIGNEE, DUE, STATUS */
    val kind: String,
    val oldValue: String?,
    val newValue: String?,
)

/** Fields to change on a task; null / false means "leave as is". [dueAt] only applies when [setDue] is true. */
data class TaskPatch(
    val title: String? = null,
    val assigneeEmail: String? = null,
    val setDue: Boolean = false,
    val dueAt: Long? = null,
    val status: TaskStatus? = null,
)

data class RegisterEntry(
    val id: Long,
    /** Internal serial number; unique, assigned inside a database transaction, never reused. */
    val seq: Long,
    val refId: Long,
    val direction: Direction,
    val entryDate: Long,
    val subject: String,
    val party: String,
    val responsibleEmail: String,
    val accountId: String,
    val createdAt: Long,
)

data class RegisterRow(val entry: RegisterEntry, val ref: MailRef, val relatedTaskCount: Int)

data class RegisterResult(val entry: RegisterEntry, val created: Boolean)

data class FollowUp(
    val id: Long,
    val refId: Long,
    val taskId: Long?,
    val accountId: String,
    val dueAt: Long,
    val state: FollowUpState,
    val note: String,
    /** 0 = nothing sent, 1 = "approaching" sent, 2 = "overdue" sent. Reset when the due time is edited. */
    val notifiedStage: Int,
    val createdAt: Long,
    val completedAt: Long?,
)

data class FollowUpWithRef(val followUp: FollowUp, val ref: MailRef)

/** A transaction that is still open, with the due dates of what keeps it open. */
data class OpenItem(
    val transactionId: Long,
    val openTaskDues: List<Long?>,
    val pendingFollowUpDues: List<Long>,
)

/** A recorded message reduced to what the statistics need. */
data class StatRef(
    val id: Long,
    val transactionId: Long,
    val accountId: String,
    val direction: Direction,
    val messageId: String?,
    val inReplyTo: String?,
    val dateMillis: Long?,
    val external: Boolean,
)
