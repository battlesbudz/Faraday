package com.battlesbudz.jarvis.v2.code

import android.content.Context
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The code-mode preview (code-and-export scope section 4.5): a
 * WebView that runs the open project and reports what its console
 * says. It is a separate, simpler WebView than the browser-automation
 * one in `actions/` — that one drives real websites for the agent;
 * this one only ever displays one project folder, and the two share
 * no code.
 *
 * Serving: a [WebViewAssetLoader] on the fake HTTPS appassets origin
 * with a [CodeProjectAssetHandler] rooted at the open project. A real
 * origin is what makes relative files, DOM storage, and normal page
 * behavior work without file:// quirks. JavaScript and DOM storage
 * are on (generated apps need both); every file-access switch is off.
 * Navigation is locked: a main-frame navigation to anything but the
 * appassets origin is refused, and an http/https subresource request
 * to any other host is answered with an empty 403, so a previewed
 * page has no network reach at all — matching the contract's offline
 * rule and section 8's limits. The cache is off: the preview's whole
 * job in the fix loop is showing the files as they are now, and a
 * cached pre-fix page would send the loop chasing ghosts.
 *
 * Console messages land in [consoleStore] through the chrome client,
 * level and source position kept; the fix loop reads them from there
 * ([CodePreviewFixLoop]). Rendering a real page and hearing its real
 * console is device behavior, proven on the phone in Unit 6; this
 * class is the wiring, and its policy pieces (the handler, the store,
 * the loop) are the JVM-tested parts.
 *
 * Main-thread confinement, like every WebView: construct, load, and
 * destroy on the main thread. Unit 6's Code screen owns the lifecycle.
 */
class CodePreview(context: Context, val consoleStore: CodeConsoleStore = CodeConsoleStore()) {

    val webView: WebView = WebView(context)

    private var assetLoader: WebViewAssetLoader? = null

    /** The store as the fix loop's error feed (each error handed over once). */
    val consoleErrors: CodeConsoleErrors get() = consoleStore

    init {
        // The WebView's own surface paints before (and, for an
        // unstyled page, behind) the page: dark like the app, never
        // the platform's white — on build 1771 the starter page's
        // white surface read as a broken off-theme screen.
        webView.setBackgroundColor(PREVIEW_BACKGROUND)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        lockDownFileUrlAccess(webView.settings)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                consoleStore.add(
                    level = levelOf(consoleMessage.messageLevel()),
                    message = consoleMessage.message(),
                    sourceId = consoleMessage.sourceId(),
                    lineNumber = consoleMessage.lineNumber()
                )
                return true
            }
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val url = request.url
                if (url.scheme == "https" && url.host == ASSETS_DOMAIN) {
                    return assetLoader?.shouldInterceptRequest(url)
                }
                if (url.scheme == "http" || url.scheme == "https") {
                    // Off-origin subresources are refused outright: a
                    // previewed project is offline by construction.
                    return WebResourceResponse(
                        "text/plain", "utf-8", 403, "Forbidden", null,
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url
                val ours = url.scheme == "https" && url.host == ASSETS_DOMAIN
                // True refuses the navigation: anything that is not
                // the project origin goes nowhere.
                return !ours
            }
        }
    }

    /** Points the preview at [projectDir] and loads its `index.html` from the appassets origin. */
    fun loadProject(projectDir: File) {
        assetLoader = WebViewAssetLoader.Builder()
            .setDomain(ASSETS_DOMAIN)
            .addPathHandler("/", CodeProjectAssetHandler(projectDir))
            .build()
        webView.loadUrl(INDEX_URL)
    }

    /**
     * Re-reads the project's current files after a round rewrote
     * them. This is a fresh navigation, not [reload]: a WebView
     * reload can be answered from the WebView's own page cache
     * without re-running the asset handler, which is how the build
     * 1771 preview kept showing the starter page after a completed
     * round had already rewritten `index.html` on disk. A fresh
     * loadUrl plus a cleared cache always re-reads the folder (and
     * the handler's `no-store` responses keep later loads honest).
     */
    fun refresh(projectDir: File) {
        webView.clearCache(true)
        loadProject(projectDir)
    }

    /** Reloads the current page; after a fix round this re-reads the rewritten files. */
    fun reload() {
        webView.reload()
    }

    fun destroy() {
        webView.destroy()
    }

    /** The legacy file-URL access switches, off like every other file path into the page. */
    @Suppress("DEPRECATION")
    private fun lockDownFileUrlAccess(settings: WebSettings) {
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
    }

    private fun levelOf(level: ConsoleMessage.MessageLevel): CodeConsoleLevel = when (level) {
        ConsoleMessage.MessageLevel.TIP -> CodeConsoleLevel.TIP
        ConsoleMessage.MessageLevel.LOG -> CodeConsoleLevel.LOG
        ConsoleMessage.MessageLevel.WARNING -> CodeConsoleLevel.WARNING
        ConsoleMessage.MessageLevel.ERROR -> CodeConsoleLevel.ERROR
        ConsoleMessage.MessageLevel.DEBUG -> CodeConsoleLevel.DEBUG
    }

    companion object {
        /** The preview surface's dark background (the Material dark scheme's background). */
        const val PREVIEW_BACKGROUND: Int = 0xFF1C1B1F.toInt()

        /** The fake HTTPS origin the project is served on (the asset loader's default domain). */
        const val ASSETS_DOMAIN: String = WebViewAssetLoader.DEFAULT_DOMAIN

        const val ORIGIN: String = "https://appassets.androidplatform.net"

        /** Where the preview starts: the project's page, through the path handler. */
        const val INDEX_URL: String = "$ORIGIN/${CodeProjectStore.INDEX_FILE_NAME}"
    }
}
