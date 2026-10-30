package com.shegrowsdev.mail

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import com.shegrowsdev.mail.ui.AppRoot

/** AppCompatActivity (not plain ComponentActivity) so the per-app language setting works on all API levels. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as MailApplication).container
        switchAccountFrom(intent, container)
        setContent { AppRoot(container) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        switchAccountFrom(intent, (application as MailApplication).container)
    }

    private fun switchAccountFrom(intent: Intent?, container: AppContainer) {
        intent?.getStringExtra(EXTRA_ACCOUNT_ID)?.let { container.accountStore.setActive(it) }
        intent?.getStringExtra(EXTRA_ROUTE)?.let { container.requestRoute(it) }
    }

    companion object {
        const val EXTRA_ACCOUNT_ID = "account_id"
        const val EXTRA_ROUTE = "route"
    }
}
