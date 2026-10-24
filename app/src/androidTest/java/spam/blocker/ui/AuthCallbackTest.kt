package spam.blocker.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.core.net.toUri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import spam.blocker.config.OAuth
import spam.blocker.ui.setting.api.PhoneBlock
import spam.blocker.util.spf

class AuthCallbackTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setup() {
        PhoneBlockAuth.cancel(ctx)
        spf.OAuth(ctx).phoneBlockToken = "existing-token"
    }

    @After
    fun cleanup() {
        PhoneBlockAuth.cancel(ctx)
        spf.OAuth(ctx).phoneBlockToken = ""
    }

    @Test
    fun unsolicitedFixedState_doesNotReplaceExistingToken() {
        callback("spamblocker://auth?state=PhoneBlock_OAuth_State&loginToken=injected-token")
        assertEquals("Unsolicited callback must not replace the account token",
            "existing-token", spf.OAuth(ctx).phoneBlockToken)
    }

    @Test
    fun explicitIntentWithWrongScheme_doesNotReplaceExistingToken() {
        callback("https://auth?state=PhoneBlock_OAuth_State&loginToken=injected-token")
        assertEquals("Explicit intents must validate the callback scheme",
            "existing-token", spf.OAuth(ctx).phoneBlockToken)
    }

    @Test
    fun loginLaunches_freshRandomStateForBothProviderEnvironments() {
        val seen = mutableSetOf<String>()
        for (url in listOf(PhoneBlock.oauthUrl, PhoneBlock.oauthUrl_Debug)) {
            repeat(5) {
                var launched: Uri? = null
                val browser = object : ContextWrapper(ctx) {
                    override fun startActivity(intent: Intent, options: Bundle?) {
                        launched = intent.data
                    }
                }
                startAuthLogin(browser, url)
                val uri = launched!!
                val state = uri.getQueryParameter("state")!!
                assertTrue("State must contain 256 random bits", state.matches(Regex("[A-Za-z0-9_-]{43}")))
                assertTrue("Every login must use a new state", seen.add(state))
                assertEquals(1, uri.getQueryParameters("state").size)
                assertEquals("https", uri.scheme)
                assertEquals("phoneblock.net", uri.host)
                assertEquals(Uri.parse(url).path, uri.path)
                assertEquals("SpamBlocker", uri.getQueryParameter("appId"))
                assertNotEquals("PhoneBlock_OAuth_State", state)
            }
        }
    }

    @Test
    fun validCallback_throughExportedActivity_acceptsOnceOnly() {
        val state = begin()
        callback(response(state).toString())
        assertEquals("new-token", spf.OAuth(ctx).phoneBlockToken)
        callback(response(state, "replayed-token").toString())
        assertEquals("A used state must not replace the token again", "new-token", spf.OAuth(ctx).phoneBlockToken)
    }

    @Test
    fun matchingState_survivesNewContextInstance() {
        val state = begin()
        val recreated = ctx.createPackageContext(ctx.packageName, 0)
        assertTrue(PhoneBlockAuth.complete(recreated, response(state)))
        assertEquals("new-token", spf.OAuth(ctx).phoneBlockToken)
    }

    @Test
    fun callbackRequiresPendingLogin_evenWithNonemptyState() {
        assertFalse(PhoneBlockAuth.complete(ctx, response("unsolicited")))
        assertEquals("existing-token", spf.OAuth(ctx).phoneBlockToken)
    }

    @Test
    fun wrongOrMissingState_doesNotConsumeGenuinePendingLogin() {
        val state = begin()
        for (uri in listOf(response("wrong"), response(""), "spamblocker://auth?loginToken=new-token".toUri())) {
            assertFalse(PhoneBlockAuth.complete(ctx, uri))
            assertEquals("existing-token", spf.OAuth(ctx).phoneBlockToken)
        }
        assertTrue(PhoneBlockAuth.complete(ctx, response(state)))
    }

    @Test
    fun malformedCallbacks_rejectedWithoutConsumingPendingLogin() {
        val state = begin()
        for (uri in listOf(
            null,
            "spamblocker:auth?state=$state&loginToken=new-token".toUri(),
            response(state).buildUpon().scheme("https").build(),
            response(state).buildUpon().authority("evil.example").build(),
            response(state).buildUpon().authority("user@auth").build(),
            response(state).buildUpon().authority("auth:80").build(),
            response(state).buildUpon().path("/extra").build(),
            response(state).buildUpon().fragment("extra").build(),
            response(state).buildUpon().appendQueryParameter("state", state).build(),
            response(state).buildUpon().appendQueryParameter("loginToken", "other-token").build(),
            "spamblocker://auth?state=$state".toUri(),
            response(state, ""), response(state, "   ")
        )) {
            assertFalse("Reject malformed callback: $uri", PhoneBlockAuth.complete(ctx, uri))
            assertEquals("existing-token", spf.OAuth(ctx).phoneBlockToken)
        }
        assertTrue(PhoneBlockAuth.complete(ctx, response(state)))
    }

    @Test
    fun stateExpiresAtTenMinutes_andRejectsClockRollback() {
        for (age in listOf(-1L, 600_000L, 600_001L)) {
            val state = begin(now = 1_000_000L)
            assertFalse("Reject age $age", PhoneBlockAuth.complete(ctx, response(state), now = 1_000_000L + age))
            assertEquals("existing-token", spf.OAuth(ctx).phoneBlockToken)
        }
        val state = begin(now = 1_000_000L)
        assertTrue(PhoneBlockAuth.complete(ctx, response(state), now = 1_599_999L))
    }

    @Test
    fun newLogin_invalidatesPreviousAttempt() {
        val oldState = begin()
        val newState = begin()
        assertNotEquals(oldState, newState)
        assertFalse(PhoneBlockAuth.complete(ctx, response(oldState)))
        assertTrue(PhoneBlockAuth.complete(ctx, response(newState)))
    }

    @Test
    fun manualTokenEntry_cancelsPendingBrowserLogin() {
        val state = begin()
        PhoneBlockAuth.saveToken(ctx, "manual-token")
        assertFalse(PhoneBlockAuth.complete(ctx, response(state)))
        assertEquals("manual-token", spf.OAuth(ctx).phoneBlockToken)
    }

    @Test
    fun choosingExistingToken_cancelsPendingBrowserLogin() {
        val state = begin()
        PhoneBlockAuth.cancel(ctx)
        assertFalse(PhoneBlockAuth.complete(ctx, response(state)))
        assertEquals("existing-token", spf.OAuth(ctx).phoneBlockToken)
    }

    @Test
    fun importedToken_cancelsPendingBrowserLogin() {
        val state = begin()
        OAuth().apply {
            phoneBlockToken = "imported-token"
        }.apply(ctx)
        assertFalse("A pending browser callback must not overwrite imported credentials",
            PhoneBlockAuth.complete(ctx, response(state)))
        assertEquals("imported-token", spf.OAuth(ctx).phoneBlockToken)
    }

    @Test
    fun fallbackBrowser_usesSameState() {
        var first: Uri? = null
        var fallback: Uri? = null
        val browser = object : ContextWrapper(ctx) {
            override fun startActivity(intent: Intent, options: Bundle?) {
                first = intent.data
                throw ActivityNotFoundException()
            }
            override fun startActivity(intent: Intent) {
                fallback = intent.data
            }
        }
        startAuthLogin(browser, PhoneBlock.oauthUrl)
        assertNotNull(first)
        assertEquals(first, fallback)
        assertTrue(PhoneBlockAuth.complete(ctx, response(fallback!!.getQueryParameter("state")!!)))
    }

    @Test
    fun noBrowserAvailable_cancelsPendingLogin() {
        var attempted: Uri? = null
        val browser = object : ContextWrapper(ctx) {
            override fun startActivity(intent: Intent, options: Bundle?) {
                attempted = intent.data
                throw ActivityNotFoundException()
            }
            override fun startActivity(intent: Intent) {
                throw ActivityNotFoundException()
            }
        }
        try {
            startAuthLogin(browser, PhoneBlock.oauthUrl)
            throw AssertionError("Expected missing browser failure")
        } catch (_: ActivityNotFoundException) {
            assertFalse(PhoneBlockAuth.complete(ctx, response(attempted!!.getQueryParameter("state")!!)))
            assertEquals("existing-token", spf.OAuth(ctx).phoneBlockToken)
        }
    }

    private fun begin(now: Long = System.currentTimeMillis()): String =
        PhoneBlockAuth.begin(ctx, PhoneBlock.oauthUrl, now)!!.getQueryParameter("state")!!

    private fun response(state: String, token: String = "new-token"): Uri =
        "spamblocker://auth".toUri().buildUpon()
            .appendQueryParameter("state", state)
            .appendQueryParameter("loginToken", token).build()

    private fun callback(url: String) {
        ActivityScenario.launch<AuthCallbackActivity>(
            Intent(ctx, AuthCallbackActivity::class.java).setData(url.toUri())
        ).close()
    }
}
