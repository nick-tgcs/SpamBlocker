# PhoneBlock browser authentication

Each browser login creates a fresh 256-bit random `state`, stored privately with its start time before the browser opens. A new attempt replaces the previous one. State persists across app-process recreation and expires after ten minutes; callbacks dated before the attempt are rejected.

Only the exact `spamblocker://auth` endpoint is accepted, with one matching state and one nonblank `loginToken`. Explicit intents receive the same validation as browser redirects. A successful callback saves the token and removes the pending state in one preferences transaction. Unsolicited, malformed, expired, mismatched and replayed callbacks leave the existing token unchanged. Manually entering or importing a token invalidates pending login in the same transaction; explicitly choosing the existing token also cancels any pending browser attempt. Browser fallback uses the same state; if both launch attempts fail, the pending login is canceled.

The lookup, report and bot setup dialogs share this login path. Production and debug continue to use their existing PhoneBlock endpoints and `appId=SpamBlocker`. The provider forwards the state in its [login controller](https://github.com/haumacher/phoneblock/blob/master/phoneblock/src/main/java/de/haumacher/phoneblock/app/render/controller/MobileLoginController.java) and [token-creation callback](https://github.com/haumacher/phoneblock/blob/master/phoneblock/src/main/java/de/haumacher/phoneblock/app/CreateAuthTokenServlet.java).

This change prevents unsolicited account-token replacement. It retains the provider's custom-scheme bearer-token flow: it does not authenticate the sending Android app, prevent another app from registering the same scheme, or change token storage, backups or logging. A live provider login has not been exercised by the regression tests; tests use synthetic tokens and never submit phone numbers or account details.

Run the Android regression suite on an isolated emulator:

```sh
ANDROID_HOME=/path/to/Android/Sdk ANDROID_SERIAL=emulator-5580 ./gradlew connectedFdroidDebugAndroidTest
```

`AuthCallbackTest` covers the exported activity, random state, exact URI validation, expiry, stale and replayed attempts, new context instances, manual/import replacement, cancellation, browser fallback and browser launch failure.
