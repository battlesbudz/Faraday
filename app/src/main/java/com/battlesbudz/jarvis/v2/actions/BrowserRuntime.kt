package com.battlesbudz.jarvis.v2.actions

import android.content.Context

/**
 * M4 browser tasks: production owner of the shared browser runtime path.
 *
 * Owns the single [BrowserSession] and the [WebViewBrowserBridge] behind the
 * nine browse tools; [decorate] installs the [AndroidBrowserExecutor] browse
 * dispatch over a delegate executor. The model-visible catalog
 * ([MobileToolCatalog.BrowserRuntimeGate]) opens exactly when this runtime is
 * installed in the production executor factories, so the model never sees
 * browse tools the runtime cannot execute — the gate and the wiring are one
 * decision, not two.
 *
 * Takeover: when the user touches the [BrowserActivity] WebView (login, 2FA,
 * CAPTCHA), the bridge reports it and the session pauses automation via
 * [BrowserSession.pauseForTakeover] until the live page is reconciled, so
 * nothing dispatches against a screen the model never saw.
 *
 * Android-only: the session itself is JVM-pure, but the bridge needs a
 * Context to hand URLs to the browser activity.
 */
class BrowserRuntime(
    private val appContext: Context,
    private val onDiagnostic: (String) -> Unit = {}
) {
    val session = BrowserSession()
    private val bridge = WebViewBrowserBridge(appContext, onUserTakeover = { session.pauseForTakeover() })

    fun decorate(delegate: MobileActionExecutor): MobileActionExecutor =
        AndroidBrowserExecutor(appContext, delegate, session, bridge, onDiagnostic)
}
