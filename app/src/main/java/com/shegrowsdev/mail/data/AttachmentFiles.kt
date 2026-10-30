package com.shegrowsdev.mail.data

import android.content.ActivityNotFoundException
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.shegrowsdev.mail.mail.AttachmentPolicy
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.MailFailure
import com.shegrowsdev.mail.mail.OutgoingAttachment
import java.io.File
import java.io.IOException

/**
 * App-private working copies of attachments. Everything lives under the cache directory, is never
 * exported except through [FileProvider] read grants, and is wiped by [cleanOldFiles].
 */
object AttachmentFiles {
    private const val COMPOSE = "compose"
    const val RECEIVED = "attachments"

    fun composeDir(context: Context, sessionId: String): File =
        File(File(context.cacheDir, COMPOSE), sessionId)

    fun receivedFile(context: Context, accountId: String, uid: Long, index: Int, name: String): File =
        File(File(File(context.cacheDir, RECEIVED), accountId), "$uid-$index/$name")

    /** Removes leftovers of earlier runs: compose copies always (no draft survives a process restart) and downloads after a day. */
    fun cleanOldFiles(context: Context) {
        runCatching { File(context.cacheDir, COMPOSE).deleteRecursively() }
        runCatching {
            val cutoff = System.currentTimeMillis() - 24L * 60 * 60 * 1000
            File(context.cacheDir, RECEIVED).walkBottomUp().forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
                else if (f.isDirectory && f.listFiles().isNullOrEmpty()) f.delete()
            }
        }
    }

    /**
     * Copies a picked file into [dir] after checking it. The type must be on the allow-list, the
     * first bytes must match that type, and the count/size limits must hold. Throws [MailFailure]
     * (with the file name as detail) describing the first problem; nothing is left behind on failure.
     */
    fun importFromUri(
        resolver: ContentResolver,
        uri: Uri,
        dir: File,
        existingCount: Int,
        existingBytes: Long,
    ): OutgoingAttachment {
        if (existingCount >= AttachmentPolicy.MAX_COUNT) throw MailFailure(FailureKind.ATTACH_TOO_MANY)

        var displayName: String? = null
        var declaredSize = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val s = c.getColumnIndex(OpenableColumns.SIZE)
                        if (n >= 0 && !c.isNull(n)) displayName = c.getString(n)
                        if (s >= 0 && !c.isNull(s)) declaredSize = c.getLong(s)
                    }
                }
        } catch (e: Exception) {
            // Name/size are optional; the checks below also run on the real bytes.
        }

        val name = AttachmentPolicy.sanitizeFileName(displayName ?: uri.lastPathSegment, "attachment")
        if (!AttachmentPolicy.isAllowedName(name)) throw MailFailure(FailureKind.ATTACH_TYPE, detail = name)
        if (declaredSize > AttachmentPolicy.MAX_FILE_BYTES) throw MailFailure(FailureKind.ATTACH_TOO_LARGE, detail = name)
        if (declaredSize > 0 && existingBytes + declaredSize > AttachmentPolicy.MAX_TOTAL_BYTES) {
            throw MailFailure(FailureKind.ATTACH_TOTAL_TOO_LARGE, detail = name)
        }

        dir.mkdirs()
        val dest = uniqueFile(dir, name)
        try {
            var total = 0L
            val input = resolver.openInputStream(uri) ?: throw IOException("cannot open")
            input.use { ins ->
                dest.outputStream().use { out ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buffer)
                        if (n < 0) break
                        total += n
                        if (total > AttachmentPolicy.MAX_FILE_BYTES) {
                            throw MailFailure(FailureKind.ATTACH_TOO_LARGE, detail = name)
                        }
                        if (existingBytes + total > AttachmentPolicy.MAX_TOTAL_BYTES) {
                            throw MailFailure(FailureKind.ATTACH_TOTAL_TOO_LARGE, detail = name)
                        }
                        out.write(buffer, 0, n)
                    }
                }
            }
            if (total == 0L) throw MailFailure(FailureKind.ATTACH_READ, detail = name)
            if (!AttachmentPolicy.contentMatches(name, readHead(dest))) {
                throw MailFailure(FailureKind.ATTACH_CONTENT, detail = name)
            }
            return OutgoingAttachment(dest, dest.name, AttachmentPolicy.mimeForName(name)!!, total)
        } catch (e: MailFailure) {
            dest.delete()
            throw e
        } catch (e: Exception) {
            dest.delete()
            throw MailFailure(FailureKind.ATTACH_READ, detail = name, cause = e)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        var i = 2
        while (candidate.exists()) {
            candidate = File(dir, "$stem ($i)$ext")
            i++
        }
        return candidate
    }

    /** First bytes of a file, for the content-type check (avoids InputStream.readNBytes, API 33+ only). */
    fun readHead(file: File, n: Int = AttachmentPolicy.HEAD_BYTES): ByteArray = file.inputStream().use { ins ->
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = ins.read(buf, read, n - read)
            if (r < 0) break
            read += r
        }
        buf.copyOf(read)
    }

    /** Opens a downloaded file with another app through a read-only [FileProvider] grant. */
    fun open(context: Context, file: File, mime: String): Boolean {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return try {
            context.startActivity(intent)
            true
        } catch (e: ActivityNotFoundException) {
            false
        }
    }

    /** Copies [source] to a location the user picked with the system file dialog. */
    fun copyTo(resolver: ContentResolver, source: File, target: Uri) {
        val out = resolver.openOutputStream(target) ?: throw IOException("cannot open target")
        out.use { o -> source.inputStream().use { it.copyTo(o) } }
    }
}
