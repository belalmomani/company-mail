package com.shegrowsdev.mail.admin

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.MailFailure
import org.json.JSONArray
import org.json.JSONObject

/**
 * The administrative data (tasks, register, follow-ups and the message references they need) as plain
 * rows. Mail messages, attachments, passwords and account settings are NOT part of it.
 *
 * [accountEmails] maps the account ids used in the rows to e-mail addresses, so a restore on a device
 * where the same accounts were added again (with new ids) can reconnect the rows to the right account.
 */
class AdminSnapshot(
    val format: Int,
    val createdAt: Long,
    val registerCounter: Long,
    val accountEmails: Map<String, String>,
    val tables: Map<String, List<Map<String, Any?>>>,
) {
    fun counts(): Map<String, Int> = tables.mapValues { it.value.size }
}

object AdminSnapshots {
    const val FORMAT = 1

    /** Parents before children: the order rows are inserted in (and the reverse of the order they are deleted in). */
    val TABLE_ORDER = listOf("transactions", "mail_refs", "tasks", "task_events", "register_entries", "follow_ups")

    private val ACCOUNT_COLUMN_TABLES = setOf("transactions", "mail_refs", "tasks", "register_entries", "follow_ups")

    private val COLUMNS: Map<String, Set<String>> = mapOf(
        "transactions" to setOf("id", "thread_key", "account_id", "title", "created_at"),
        "mail_refs" to setOf(
            "id", "transaction_id", "account_id", "account_email", "message_id", "in_reply_to", "folder_role",
            "uid", "subject", "counterpart", "direction", "msg_date", "attachment_names", "external", "created_at"
        ),
        "tasks" to setOf(
            "id", "ref_id", "account_id", "title", "assignee_email", "due_at", "status", "created_at",
            "updated_at", "created_by"
        ),
        "task_events" to setOf("id", "task_id", "at", "actor", "kind", "old_value", "new_value"),
        "register_entries" to setOf(
            "id", "seq", "ref_id", "direction", "entry_date", "subject", "party", "responsible_email",
            "account_id", "created_at"
        ),
        "follow_ups" to setOf(
            "id", "ref_id", "task_id", "account_id", "due_at", "state", "note", "notified_stage",
            "created_at", "completed_at"
        ),
    )

    // ------------------------------------------------------------------ export

    fun export(db: SQLiteDatabase, accountEmails: Map<String, String>, now: Long): AdminSnapshot {
        var counter = 0L
        val tables = LinkedHashMap<String, List<Map<String, Any?>>>()
        db.beginTransactionNonExclusive() // one consistent read of every table
        try {
            for (t in TABLE_ORDER) {
                tables[t] = db.rawQuery("SELECT * FROM $t ORDER BY id", null).use { c -> rows(c) }
            }
            counter = db.rawQuery("SELECT value FROM counters WHERE name = 'register'", null)
                .use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return AdminSnapshot(FORMAT, now, counter, accountEmails, tables)
    }

    private fun rows(c: Cursor): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        while (c.moveToNext()) {
            val row = LinkedHashMap<String, Any?>()
            for (i in 0 until c.columnCount) {
                row[c.getColumnName(i)] = when (c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                    else -> c.getString(i)
                }
            }
            out.add(row)
        }
        return out
    }

    // ------------------------------------------------------------------ JSON

    fun toJson(s: AdminSnapshot): String {
        val root = JSONObject()
        root.put("format", s.format)
        root.put("createdAt", s.createdAt)
        root.put("registerCounter", s.registerCounter)
        root.put("accountEmails", JSONObject(s.accountEmails))
        val tables = JSONObject()
        for ((name, list) in s.tables) {
            val arr = JSONArray()
            for (row in list) {
                val o = JSONObject()
                for ((k, v) in row) o.put(k, v ?: JSONObject.NULL)
                arr.put(o)
            }
            tables.put(name, arr)
        }
        root.put("tables", tables)
        root.put("counts", JSONObject(s.counts()))
        return root.toString()
    }

    /** Reads and validates a snapshot. Anything unexpected is a [FailureKind.BACKUP_BAD_FILE]. */
    fun fromJson(json: String): AdminSnapshot {
        try {
            val root = JSONObject(json)
            val format = root.getInt("format")
            if (format != FORMAT) throw MailFailure(FailureKind.BACKUP_BAD_FILE, detail = "format $format")
            val emails = LinkedHashMap<String, String>()
            root.getJSONObject("accountEmails").let { o -> o.keys().forEach { k -> emails[k] = o.getString(k) } }

            val tablesJson = root.getJSONObject("tables")
            val tables = LinkedHashMap<String, List<Map<String, Any?>>>()
            for (t in TABLE_ORDER) {
                val arr = tablesJson.getJSONArray(t)
                val allowed = COLUMNS.getValue(t)
                val list = ArrayList<Map<String, Any?>>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val row = LinkedHashMap<String, Any?>()
                    o.keys().forEach { k ->
                        if (k !in allowed) throw MailFailure(FailureKind.BACKUP_BAD_FILE, detail = "$t.$k")
                        row[k] = when (val v = o.get(k)) {
                            JSONObject.NULL -> null
                            is Int -> v.toLong()
                            is Long, is Double, is String -> v
                            else -> throw MailFailure(FailureKind.BACKUP_BAD_FILE, detail = "$t.$k")
                        }
                    }
                    list.add(row)
                }
                tables[t] = list
            }
            // The counts written next to the rows must match the rows that were read.
            val counts = root.getJSONObject("counts")
            for (t in TABLE_ORDER) {
                if (counts.getInt(t) != tables.getValue(t).size) {
                    throw MailFailure(FailureKind.BACKUP_BAD_FILE, detail = "count $t")
                }
            }
            return AdminSnapshot(format, root.getLong("createdAt"), root.getLong("registerCounter"), emails, tables)
        } catch (e: MailFailure) {
            throw e
        } catch (e: Exception) {
            throw MailFailure(FailureKind.BACKUP_BAD_FILE, cause = e)
        }
    }

    // ------------------------------------------------------------------ restore

    /**
     * Replaces ALL administrative data with [s] in one transaction: on any problem nothing changes.
     * Rows of an account whose e-mail is in [emailToId] are re-pointed to that account's current id.
     */
    fun restore(db: SQLiteDatabase, s: AdminSnapshot, emailToId: Map<String, String>) {
        val idMap = s.accountEmails.mapValues { (oldId, email) -> emailToId[email.lowercase()] ?: oldId }
        db.beginTransaction()
        try {
            // The register protects itself against deletion; those triggers are lifted only inside this
            // transaction and recreated before it commits.
            db.execSQL("DROP TRIGGER IF EXISTS register_no_delete")
            db.execSQL("DROP TRIGGER IF EXISTS register_immutable")
            for (t in TABLE_ORDER.reversed()) db.execSQL("DELETE FROM $t")

            for (t in TABLE_ORDER) {
                for (row in s.tables.getValue(t)) {
                    val cv = ContentValues()
                    for ((k, v) in row) {
                        val value = if (k == "account_id" && t in ACCOUNT_COLUMN_TABLES && v is String) idMap[v] ?: v else v
                        when (value) {
                            null -> cv.putNull(k)
                            is Long -> cv.put(k, value)
                            is Double -> cv.put(k, value)
                            is String -> cv.put(k, value)
                            else -> throw MailFailure(FailureKind.BACKUP_BAD_FILE, detail = "$t.$k")
                        }
                    }
                    db.insertOrThrow(t, null, cv)
                }
            }

            // Serial numbers must never be handed out twice, even after a restore.
            val maxSeq = db.rawQuery("SELECT COALESCE(MAX(seq), 0) FROM register_entries", null)
                .use { c -> c.moveToFirst(); c.getLong(0) }
            db.execSQL(
                "UPDATE counters SET value = ? WHERE name = 'register'",
                arrayOf<Any>(maxOf(s.registerCounter, maxSeq))
            )

            db.execSQL(AdminDb.TRIGGER_REGISTER_IMMUTABLE)
            db.execSQL(AdminDb.TRIGGER_REGISTER_NO_DELETE)

            val broken = db.rawQuery("PRAGMA foreign_key_check", null).use { c -> c.count }
            if (broken > 0) throw MailFailure(FailureKind.BACKUP_BAD_FILE, detail = "relations")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}
