package com.shegrowsdev.mail.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.MailFailure
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Imports the company logo used in signatures: the picked image is size-limited, decoded (so only
 * real images are accepted), scaled down and stored as a small PNG in app-private storage.
 */
object LogoImporter {
    private const val MAX_INPUT_BYTES = 5 * 1024 * 1024
    private const val MAX_SIDE = 400

    fun importLogo(resolver: ContentResolver, uri: Uri, target: File) {
        val bytes = try {
            val input = resolver.openInputStream(uri) ?: throw MailFailure(FailureKind.ATTACH_READ)
            input.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf)
                    if (n < 0) break
                    if (out.size() + n > MAX_INPUT_BYTES) throw MailFailure(FailureKind.ATTACH_TOO_LARGE)
                    out.write(buf, 0, n)
                }
                out.toByteArray()
            }
        } catch (e: MailFailure) {
            throw e
        } catch (e: Exception) {
            throw MailFailure(FailureKind.ATTACH_READ, cause = e)
        }

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw MailFailure(FailureKind.ATTACH_CONTENT)

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample }
        ) ?: throw MailFailure(FailureKind.ATTACH_CONTENT)

        val scale = MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height)
        val scaled = if (scale < 1f) {
            Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1),
                (decoded.height * scale).toInt().coerceAtLeast(1), true)
        } else decoded

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) throw MailFailure(FailureKind.ATTACH_SAVE)
        } catch (e: MailFailure) {
            tmp.delete()
            throw e
        } catch (e: Exception) {
            tmp.delete()
            throw MailFailure(FailureKind.ATTACH_SAVE, cause = e)
        }
    }
}
