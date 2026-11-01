# Company Mail — Phase 4 (security, encrypted backup, tests, release)

Native Android (Kotlin, Jetpack Compose, Material 3). IMAP/SMTP over implicit SSL/TLS, Arabic/English (RTL/LTR).

## Build (no Android Studio needed)
Push to GitHub -> **Actions -> Android Build**. Four independent jobs:
- `build-debug-apk`: `app-debug.apk` (artifact `company-mail-debug-apk`);
- `unit-tests`: logic tests, real-SQLite tests (Robolectric), backup tests, loopback connection tests, resource/security checks;
- `build-release`: `app-release*.apk` and `app-release.aab` (artifacts `company-mail-release-apk`, `company-mail-release-aab`).
Locally: JDK 17 + Android SDK 34, then `gradle :app:assembleDebug`, `gradle :app:testDebugUnitTest`, `gradle :app:assembleRelease :app:bundleRelease`.

### Signing (needed so a new APK UPDATES the installed app)
All builds (debug and release) are signed with ONE fixed key and get a higher version number on every run. Without the secrets below each run uses a different temporary key, and Android then demands uninstalling the old app first.
Without secrets the release files are built **unsigned** (fine for testing; Google Play needs a signed AAB).
To sign: create a keystore yourself, then in GitHub -> Settings -> Secrets and variables -> Actions add:
`ANDROID_KEYSTORE_BASE64` (output of `base64 -w0 your.keystore`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD`.
Never commit the keystore (`.gitignore` blocks it and a test fails if one is found). **Lose the keystore = cannot update the app on Play.**

## Setup and run
Install the APK (allow "install unknown apps"), add each account (for `heba@shegrowsdev.com` tap **Use the approved settings**;
other accounts need their own verified server settings). Passwords are typed in the app only; stored AES-256-GCM encrypted with an
Android Keystore key. The app tests a real IMAP + SMTP login before saving. Set the role of the manager account to *Administrator* in Settings.

## What exists
- Phase 1: accounts, folders, compose/reply/forward, CC/BCC, drafts, notifications, language switch.
- Phase 2: attachments, search, archive/delete, conversations, signatures with logo.
- Phase 3: tasks, official register (unique serials), follow-up reminders, unified inbox, dashboard, roles.
- Phase 4 (this version): **encrypted backup and restore** (administrators only), error handling for backup/connection failures, tests, release APK/AAB.

## Backup and restore (menu -> Backup and restore, administrators only)
- **Scope: administrative data only** — tasks and history, official register, follow-ups and the message references they use.
  It does **not** contain e-mail messages, attachments, passwords or account settings (mail stays on the mail server).
- Encrypted with AES-256-GCM; key derived from your passphrase (PBKDF2-HMAC-SHA256, 310,000 iterations, random salt per file).
  Any modification of the file, or a wrong passphrase, is detected. **The passphrase cannot be recovered — keep it safe.**
- Each backup is written to a temporary file, read back and fully verified, then renamed; incomplete files are never listed.
- Automatic daily backup (optional): the passphrase is then kept on the device encrypted with the Keystore; a failed run is shown on
  the screen and as a notification. Retention: 7 automatic, 10 manual, 3 safety copies.
- Restore: verifies the file first, makes a safety copy of the current data, then replaces everything in **one database transaction**
  (any error = nothing changes). Register serial numbers continue after the highest number; register protections are recreated.
  Rows are re-linked by e-mail address if accounts were added again on a new device.
- Copies on the device are deleted if the app is uninstalled: use **Export** to keep a copy elsewhere, and **Restore from file** on a new device.

## Security review (summary)
Implicit TLS only, certificate + host-name checks, no STARTTLS downgrade, no custom trust, no cleartext (network security config);
no logging, no JavaMail debug; Android auto-backup disabled; passwords Keystore-encrypted; no external services; failure details never
contain passwords. Automated checks for these are in the test suite.

## Known limits (honest list)
- Never built or run in the authoring environment: results come from your GitHub Actions run. The new Phase 4 code and tests have not been
  compiled by me; send the log if a job fails.
- Not tested against a real mail server by the authoring side, and no real TLS-handshake/timeout test with a valid certificate; connection
  tests use loopback fake servers (closed port, non-TLS server, hang-up).
- Administrative database is **not encrypted at rest** on the device (app-private storage only); backups are encrypted.
- Roles are a convenience on a shared device, not a security boundary: all passwords are on the same device.
- The automatic backup stores its passphrase on the device (Keystore-encrypted); anyone who can unlock the app on that device can use it.
- Replies written outside this app are unknown to the response-time statistic.
- Brand colours/logo are placeholders.
