package spam.blocker.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.browser.customtabs.CustomTabsIntent
import spam.blocker.ui.main.MainActivity


fun startAuthLogin(ctx: Context, url: String) {

    val loginUri = PhoneBlockAuth.begin(ctx, url) ?: return
    val customTabsIntent = CustomTabsIntent.Builder().build()

    try {
        try {
            customTabsIntent.launchUrl(ctx, loginUri)
        } catch (e: Exception) {
            // Very rare fallback
            val fallbackIntent = Intent(Intent.ACTION_VIEW, loginUri)
            ctx.startActivity(fallbackIntent)
        }
    } catch (e: Exception) {
        PhoneBlockAuth.cancel(ctx)
        throw e
    }
}

class AuthCallbackActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        PhoneBlockAuth.complete(this, intent.data)

        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
        finish()
    }
}
