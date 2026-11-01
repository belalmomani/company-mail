package com.shegrowsdev.mail.admin

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Local administrative database (tasks, register, follow-ups). App-private storage, excluded from
 * cloud backup by the data-extraction rules. Only metadata is kept here (subject, addresses, dates,
 * attachment names); message bodies and attachments stay on the mail server.
 *
 * Integrity is enforced by the database itself:
 *  - foreign keys are on, and referenced rows cannot be deleted (RESTRICT);
 *  - a task's / register entry's message link cannot be changed after creation (triggers);
 *  - the register serial number is UNIQUE, cannot be changed, and register rows cannot be deleted.
 */
class AdminDb(context: Context) : SQLiteOpenHelper(context.applicationContext, NAME, null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE transactions(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                thread_key TEXT NOT NULL,
                account_id TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_tx_key ON transactions(account_id, thread_key)")

        db.execSQL(
            """CREATE TABLE mail_refs(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                transaction_id INTEGER NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
                account_id TEXT NOT NULL,
                account_email TEXT NOT NULL,
                message_id TEXT,
                in_reply_to TEXT,
                folder_role TEXT NOT NULL,
                uid INTEGER NOT NULL,
                subject TEXT NOT NULL,
                counterpart TEXT NOT NULL,
                direction TEXT NOT NULL,
                msg_date INTEGER,
                attachment_names TEXT NOT NULL DEFAULT '',
                external INTEGER NOT NULL DEFAULT 1,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_refs_tx ON mail_refs(transaction_id)")
        db.execSQL("CREATE INDEX idx_refs_msgid ON mail_refs(account_id, message_id)")
        db.execSQL("CREATE INDEX idx_refs_reply ON mail_refs(account_id, in_reply_to)")
        // One row per real message (a mail sent to oneself is legitimately both incoming and outgoing).
        db.execSQL(
            "CREATE UNIQUE INDEX uq_refs_message ON mail_refs(account_id, message_id, direction) " +
                "WHERE message_id IS NOT NULL"
        )

        db.execSQL(
            """CREATE TABLE tasks(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ref_id INTEGER NOT NULL REFERENCES mail_refs(id) ON DELETE RESTRICT,
                account_id TEXT NOT NULL,
                title TEXT NOT NULL,
                assignee_email TEXT NOT NULL,
                due_at INTEGER,
                status TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                created_by TEXT NOT NULL
            )"""
        )
        db.execSQL("CREATE INDEX idx_tasks_ref ON tasks(ref_id)")
        db.execSQL("CREATE INDEX idx_tasks_status ON tasks(status, due_at)")
        db.execSQL(TRIGGER_TASKS_REF)

        db.execSQL(
            """CREATE TABLE task_events(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
                at INTEGER NOT NULL,
                actor TEXT NOT NULL,
                kind TEXT NOT NULL,
                old_value TEXT,
                new_value TEXT
            )"""
        )
        db.execSQL("CREATE INDEX idx_events_task ON task_events(task_id, at)")

        db.execSQL("CREATE TABLE counters(name TEXT PRIMARY KEY, value INTEGER NOT NULL)")
        db.execSQL("INSERT INTO counters(name, value) VALUES('register', 0)")

        db.execSQL(
            """CREATE TABLE register_entries(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                seq INTEGER NOT NULL UNIQUE,
                ref_id INTEGER NOT NULL UNIQUE REFERENCES mail_refs(id) ON DELETE RESTRICT,
                direction TEXT NOT NULL,
                entry_date INTEGER NOT NULL,
                subject TEXT NOT NULL,
                party TEXT NOT NULL,
                responsible_email TEXT NOT NULL,
                account_id TEXT NOT NULL,
                created_at INTEGER NOT NULL
            )"""
        )
        db.execSQL(TRIGGER_REGISTER_IMMUTABLE)
        db.execSQL(TRIGGER_REGISTER_NO_DELETE)

        db.execSQL(
            """CREATE TABLE follow_ups(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                ref_id INTEGER NOT NULL REFERENCES mail_refs(id) ON DELETE RESTRICT,
                task_id INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
                account_id TEXT NOT NULL,
                due_at INTEGER NOT NULL,
                state TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                notified_stage INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                completed_at INTEGER
            )"""
        )
        db.execSQL("CREATE INDEX idx_follow_state ON follow_ups(state, due_at)")
        db.execSQL("CREATE INDEX idx_follow_ref ON follow_ups(ref_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first schema; future versions add migration steps here.
    }

    companion object {
        const val NAME = "company_mail_admin.db"
        const val VERSION = 1

        // Kept here so that a restore can drop and recreate exactly the same triggers.
        const val TRIGGER_TASKS_REF =
            """CREATE TRIGGER tasks_ref_immutable BEFORE UPDATE OF ref_id, account_id ON tasks
               BEGIN SELECT RAISE(ABORT, 'a task cannot be moved to another message'); END"""
        const val TRIGGER_REGISTER_IMMUTABLE =
            """CREATE TRIGGER register_immutable BEFORE UPDATE OF seq, ref_id ON register_entries
               BEGIN SELECT RAISE(ABORT, 'register serial number and message link are fixed'); END"""
        const val TRIGGER_REGISTER_NO_DELETE =
            """CREATE TRIGGER register_no_delete BEFORE DELETE ON register_entries
               BEGIN SELECT RAISE(ABORT, 'register entries cannot be deleted'); END"""
    }
}
