# Company Mail — Phase 1 (core + mail)

Native Android (Kotlin, Jetpack Compose, Material 3). IMAP/SMTP over implicit SSL/TLS, Arabic/English (RTL/LTR).

## Build (no Android Studio needed)
Push to GitHub → **Actions → Android Build** runs unit tests and builds `app-debug.apk` (artifact `company-mail-debug-apk`).
Locally: JDK 17 + Android SDK 34, then `gradle :app:assembleDebug`.

## First run
Add account → for `heba@shegrowsdev.com` tap **Use the approved settings**, enter the password **in the app**.
The app tests a real IMAP + SMTP login before saving. Passwords are never in the code/repo; they are stored
AES-256-GCM encrypted with an Android Keystore key. Other accounts need their own server settings.

## Phase 1 scope
Accounts setup/switching, Inbox/Sent/Drafts/Spam, read, compose/reply/reply-all/forward, CC/BCC, drafts,
basic text signature per language, new-mail notifications (polling ~15 min), language switch.
Not included yet (later phases): attachments, search/archive/delete, tasks, official log, reminders, dashboard, backups.

## Known limits / not yet verified
Never built or run against the real server in the authoring environment. HTML mail is shown as plain text.
Brand colours/logo are placeholders (none supplied).
