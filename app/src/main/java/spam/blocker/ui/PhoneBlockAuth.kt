package spam.blocker.ui

import android.content.Context
import android.net.Uri
import android.util.Base64
import spam.blocker.ui.setting.api.PhoneBlock
import spam.blocker.util.spf
import java.security.SecureRandom

internal object PhoneBlockAuth {
    private const val STATE_KEY = "phoneblock_pending_state"
    private const val STARTED_KEY = "phoneblock_login_started"
    private const val MAX_AGE_MS = 10 * 60 * 1000L

    // Persist before opening the browser: Android may kill the app during login.
    @Synchronized
    fun begin(ctx: Context, url: String, now: Long = System.currentTimeMillis()): Uri? {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val state = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val saved = spf.OAuth(ctx).prefs.edit()
            .putString(STATE_KEY, state)
            .putLong(STARTED_KEY, now)
            .commit()
        if (!saved) return null
        return Uri.parse(url).buildUpon().appendQueryParameter("state", state).build()
    }

    @Synchronized
    fun complete(ctx: Context, data: Uri?, now: Long = System.currentTimeMillis()): Boolean {
        // Exported activities also receive explicit intents, bypassing manifest filters.
        if (data == null || !data.isHierarchical || data.scheme != "spamblocker" ||
            data.encodedAuthority != "auth" || !data.encodedPath.isNullOrEmpty() ||
            data.fragment != null) return false

        val states = data.getQueryParameters("state")
        val tokens = data.getQueryParameters("loginToken")
        if (states.size != 1 || tokens.size != 1 || tokens[0].isBlank()) return false

        val prefs = spf.OAuth(ctx).prefs
        val pending = prefs.getString(STATE_KEY, null)
        if (pending.isNullOrEmpty() || states[0] != pending ||
            !prefs.contains(STARTED_KEY)) return false
        val age = now - prefs.getLong(STARTED_KEY, 0)
        if (age !in 0 until MAX_AGE_MS) return false

        return saveToken(ctx, tokens[0])
    }

    // Every credential replacement also invalidates any outstanding browser login.
    @Synchronized
    fun saveToken(ctx: Context, token: String): Boolean {
        return spf.OAuth(ctx).prefs.edit()
            .remove(STATE_KEY)
            .remove(STARTED_KEY)
            .putString(PhoneBlock.spfTokenKey, token)
            .commit()
    }

    @Synchronized
    fun cancel(ctx: Context) {
        spf.OAuth(ctx).prefs.edit().remove(STATE_KEY).remove(STARTED_KEY).commit()
    }
}
