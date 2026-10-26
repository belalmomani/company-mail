package com.shegrowsdev.mail

import android.content.Context
import com.shegrowsdev.mail.data.AccountStore
import com.shegrowsdev.mail.mail.MailRepository

/** Minimal manual dependency container (no DI framework needed at this size). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val accountStore = AccountStore(appContext)
    val repository = MailRepository(accountStore)
}
