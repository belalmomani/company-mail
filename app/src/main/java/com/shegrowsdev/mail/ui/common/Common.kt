package com.shegrowsdev.mail.ui.common

import android.content.Context
import android.text.format.DateUtils
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shegrowsdev.mail.AppContainer
import com.shegrowsdev.mail.MailApplication
import com.shegrowsdev.mail.R
import com.shegrowsdev.mail.mail.FailureKind
import com.shegrowsdev.mail.mail.FolderRole
import com.shegrowsdev.mail.mail.MailFailure
import kotlin.coroutines.cancellation.CancellationException

/** One-time message for the user. [detail] is shown after the text (e.g. rejected addresses). */
data class Notice(
    @StringRes val textRes: Int,
    val detail: String? = null,
    val isError: Boolean = true,
)

@StringRes
fun FailureKind.messageRes(): Int = when (this) {
    FailureKind.AUTH_IMAP -> R.string.err_auth_imap
    FailureKind.AUTH_SMTP -> R.string.err_auth_smtp
    FailureKind.HOST_NOT_FOUND -> R.string.err_host
    FailureKind.TLS -> R.string.err_tls
    FailureKind.TIMEOUT -> R.string.err_timeout
    FailureKind.CONNECT -> R.string.err_connect
    FailureKind.SMTP_REJECTED -> R.string.err_smtp_rejected
    FailureKind.FOLDER_NOT_FOUND -> R.string.err_folder_not_found
    FailureKind.MESSAGE_NOT_FOUND -> R.string.err_message_not_found
    FailureKind.INVALID_RECIPIENT -> R.string.err_invalid_recipient
    FailureKind.NO_RECIPIENT -> R.string.err_no_recipient
    FailureKind.NO_PASSWORD -> R.string.err_no_password
    FailureKind.ACCOUNT_NOT_FOUND -> R.string.err_account_not_found
    FailureKind.MESSAGE_TOO_LARGE -> R.string.err_message_too_large
    FailureKind.ATTACH_TYPE -> R.string.err_attach_type
    FailureKind.ATTACH_CONTENT -> R.string.err_attach_content
    FailureKind.ATTACH_TOO_LARGE -> R.string.err_attach_too_large
    FailureKind.ATTACH_TOTAL_TOO_LARGE -> R.string.err_attach_total_too_large
    FailureKind.ATTACH_TOO_MANY -> R.string.err_attach_too_many
    FailureKind.ATTACH_READ -> R.string.err_attach_read
    FailureKind.ATTACH_DOWNLOAD -> R.string.err_attach_download
    FailureKind.ATTACH_NOT_FOUND -> R.string.err_attach_not_found
    FailureKind.ATTACH_SAVE -> R.string.err_attach_save
    FailureKind.NO_APP_TO_OPEN -> R.string.err_no_app_to_open
    FailureKind.SEARCH_FAILED -> R.string.err_search_failed
    FailureKind.NOT_PERMITTED -> R.string.err_not_permitted
    FailureKind.DATA_ERROR -> R.string.err_data
    FailureKind.UNKNOWN -> R.string.err_generic
}

fun Throwable.asFailure(): MailFailure =
    this as? MailFailure ?: MailFailure(FailureKind.UNKNOWN, detail = javaClass.simpleName, cause = this)

fun Throwable.toNotice(): Notice {
    val failure = asFailure()
    return Notice(failure.kind.messageRes(), failure.detail)
}

@StringRes
fun FolderRole.labelRes(): Int = when (this) {
    FolderRole.INBOX -> R.string.folder_inbox
    FolderRole.SENT -> R.string.folder_sent
    FolderRole.DRAFTS -> R.string.folder_drafts
    FolderRole.SPAM -> R.string.folder_spam
    FolderRole.TRASH -> R.string.folder_trash
    FolderRole.ARCHIVE -> R.string.folder_archive
    FolderRole.OTHER -> R.string.folder_other
}

/** Like runCatching, but never swallows coroutine cancellation. */
inline fun <T> attempt(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    Result.failure(e)
}

/**
 * Wraps text in a left-to-right isolate so e-mail addresses, numbers and Latin names keep their
 * order and punctuation when embedded in Arabic (RTL) text, and vice versa.
 */
fun String.ltr(): String = "⁦$this⁩"

fun formatDateTime(context: Context, millis: Long?): String {
    if (millis == null) return ""
    val flags = if (DateUtils.isToday(millis)) {
        DateUtils.FORMAT_SHOW_TIME
    } else {
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    }
    return DateUtils.formatDateTime(context, millis, flags)
}

fun formatFullDateTime(context: Context, millis: Long?): String {
    if (millis == null) return ""
    return DateUtils.formatDateTime(
        context, millis,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR
    )
}

@Composable
fun rememberContainer(): AppContainer =
    (LocalContext.current.applicationContext as MailApplication).container

/** ViewModel scoped to the current navigation entry, created from the app container. */
@Composable
inline fun <reified VM : ViewModel> containerViewModel(crossinline build: (AppContainer) -> VM): VM {
    val container = rememberContainer()
    return viewModel(factory = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = build(container) as T
    })
}
