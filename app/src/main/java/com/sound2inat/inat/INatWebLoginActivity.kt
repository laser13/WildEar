package com.sound2inat.inat

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject
import org.json.JSONTokener

/**
 * In-app WebView login flow for iNaturalist.
 *
 * The user lands on `/users/api_token`. If they're not signed in, iNat
 * redirects to `/login`; once signed in, it redirects back and the page
 * body is the JSON `{"api_token":"…"}` we want. We sniff URLs on every
 * page load — when the path is `/users/api_token`, we evaluate
 * `document.body.innerText`, parse JSON, and finish with [Activity.RESULT_OK].
 *
 * Cookies persist via the system [CookieManager], so the next call to
 * [com.sound2inat.inat.INatAuthRepository.silentRefresh] can re-fetch
 * `/users/api_token` headlessly without showing this screen again — until
 * the iNat session cookie expires (typically a couple of weeks) and the
 * user has to log in once more.
 */
class INatWebLoginActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var loading by remember { mutableStateOf(true) }
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text("Log in to iNaturalist") },
                        navigationIcon = {
                            IconButton(onClick = {
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                            }) {
                                Icon(Icons.Filled.Close, contentDescription = "Cancel")
                            }
                        },
                    )
                },
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                    AndroidView(
                        factory = { ctx ->
                            buildWebView(
                                ctx,
                                onLoadingChange = { loading = it },
                                onTokenCaptured = { token ->
                                    val data = Intent().apply {
                                        putExtra(EXTRA_TOKEN, token)
                                    }
                                    setResult(Activity.RESULT_OK, data)
                                    finish()
                                },
                            )
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (loading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }

    /**
     * `ActivityResultContract` for callers (Settings screen). Launching it
     * starts this activity; the result is the captured api_token string,
     * or null if the user cancelled / the flow failed.
     */
    class Contract : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: Context, input: Unit): Intent =
            Intent(context, INatWebLoginActivity::class.java)

        override fun parseResult(resultCode: Int, intent: Intent?): String? {
            if (resultCode != Activity.RESULT_OK) return null
            return intent?.getStringExtra(EXTRA_TOKEN)?.takeIf(String::isNotBlank)
        }
    }

    companion object {
        const val EXTRA_TOKEN = "api_token"
        const val LOGIN_URL = "https://www.inaturalist.org/users/api_token"
        const val TOKEN_PATH_SUFFIX = "/users/api_token"
    }
}

/**
 * Common WebView wiring used by both the visible login activity above and
 * the headless silent-refresh path in [INatAuthRepository]. Configures JS,
 * persistent cookies, and the URL sniffer that fires [onTokenCaptured] as
 * soon as the page at [INatWebLoginActivity.TOKEN_PATH_SUFFIX] is loaded.
 *
 * Caller is responsible for calling [WebView.loadUrl] with the entry URL
 * (typically [INatWebLoginActivity.LOGIN_URL]) and for cleaning up the
 * [WebView] when done (`destroy()` after the result fires).
 */
@Suppress("SetJavaScriptEnabled")
internal fun buildWebView(
    context: Context,
    onLoadingChange: (Boolean) -> Unit,
    onTokenCaptured: (String) -> Unit,
): WebView {
    val webView = WebView(context)
    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
    }
    CookieManager.getInstance().apply {
        setAcceptCookie(true)
        setAcceptThirdPartyCookies(webView, true)
    }
    webView.webViewClient = TokenCaptureClient(onLoadingChange, onTokenCaptured)
    webView.loadUrl(INatWebLoginActivity.LOGIN_URL)
    return webView
}

/**
 * `WebViewClient` that watches every page load and, when the URL path
 * matches [INatWebLoginActivity.TOKEN_PATH_SUFFIX], extracts the JSON
 * body via JavaScript and reports the captured token. Other URL loads
 * (login form, OAuth screens) pass through unchanged.
 */
private class TokenCaptureClient(
    private val onLoadingChange: (Boolean) -> Unit,
    private val onTokenCaptured: (String) -> Unit,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onLoadingChange(true)
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onLoadingChange(false)
        if (url == null) return
        if (!url.contains(INatWebLoginActivity.TOKEN_PATH_SUFFIX)) return
        view.evaluateJavascript("(function(){return document.body.innerText;})();") { quoted ->
            val token = parseTokenJson(quoted) ?: return@evaluateJavascript
            onTokenCaptured(token)
        }
    }
}

/**
 * `evaluateJavascript` returns the JS expression's value JSON-encoded, so
 * `document.body.innerText` comes back as `"{\"api_token\":\"...\"}"`
 * (with surrounding quotes and escaped quotes inside). Unwrap once to
 * regular JSON, parse, return the api_token field. Any deviation from
 * that shape returns null — the caller treats null as "not on the token
 * page yet, keep waiting".
 */
internal fun parseTokenJson(jsResult: String?): String? {
    if (jsResult.isNullOrBlank() || jsResult == "null") return null
    // evaluateJavascript wraps the returned value as a JSON literal — for
    // a string return, that's a quoted, escape-encoded string. JSONTokener
    // unwraps it back to the raw page body.
    val unwrapped = runCatching {
        JSONTokener(jsResult).nextValue() as? String
    }.getOrNull() ?: return null
    return runCatching {
        JSONObject(unwrapped).optString("api_token").takeIf(String::isNotBlank)
    }.getOrNull()
}

