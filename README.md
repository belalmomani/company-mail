# Company Mail — Phase 3 (administration, register, follow-ups)

Native Android (Kotlin, Jetpack Compose, Material 3). IMAP/SMTP over implicit SSL/TLS, Arabic/English (RTL/LTR).

## Build (no Android Studio needed)
Push to GitHub → **Actions → Android Build**:
- job `build-debug-apk` builds `app-debug.apk` (artifact `company-mail-debug-apk`);
- job `unit-tests` runs the logic tests and the real-SQLite tests (Robolectric) and uploads the report.
Locally: JDK 17 + Android SDK 34, then `gradle :app:assembleDebug` / `gradle :app:testDebugUnitTest`.

## Accounts and passwords
Add account → for `heba@shegrowsdev.com` tap **Use the approved settings**; enter every password **in the app**.
Passwords are never in the code or repository; they are stored AES-256-GCM encrypted with an Android Keystore key.
Other accounts need their own, verified server settings. The app tests a real IMAP + SMTP login before saving.

## What exists
- Phase 1: accounts, Inbox/Sent/Drafts/Spam, compose/reply/forward, CC/BCC, drafts, notifications, language switch.
- Phase 2: attachments (PDF/Word/Excel/images), search, archive/delete, conversation grouping, signatures with logo.
- Phase 3 (this version):
  - Turn a message into a **task** (responsible account, due date, status, history); the link to the message cannot change.
  - **Official register** (incoming/outgoing) with unique internal serial numbers (separate from mail Message-IDs), search.
  - **Follow-up reminders** (approaching / overdue, each shown once; configurable in Settings).
  - **Unified inbox** and **management dashboard** (incoming, outgoing, open/overdue transactions, average response time).
  - Roles: *Employee* sees own data (and tasks assigned to them); *Administrator* sees all accounts on the device.

## How the dashboard counts (also shown in the app)
Message = one e-mail; transaction = a conversation with an unfinished task or pending reminder. Response time = receipt of a
message → first reply sent **from this app** in the same conversation (a direct reply wins); unanswered messages are excluded.
Incoming/outgoing counts are read from the mail server by calendar day (messages still in Inbox / Sent).

## Known limits
- Never built or run in the authoring environment, and never tested against the real mail server: build and test it first.
- Administrative data (tasks, register, reminders) lives in an app-private SQLite database on the device, **not encrypted
  and not backed up yet**. Encryption, backup and restore belong to phase 4.
- Roles are a convenience on a shared device, not a security boundary: every account's password is on the same device.
- Replies written outside this app are unknown to the response-time statistic.
- Brand colours/logo are placeholders (none supplied).
