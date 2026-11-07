package com.battlesbudz.faraday.webshell;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The export shell's only screen (code-and-export scope section 5.1):
 * one WebView, nothing else. It serves the app's own assets/www/ in
 * answer to requests for the appassets origin — the same fake HTTPS
 * origin Faraday's code preview uses, so a project behaves identically
 * in preview and installed — with a ".." guard on every path, and it
 * refuses navigation to any other host. The shell requests no
 * permissions and has no dependencies beyond the platform, so an
 * exported app is offline by construction, not by promise.
 *
 * Written fresh for Faraday; the shape mirrors the runtime behavior
 * the code-and-export scope took from the Mochu shell.
 */
public final class ShellActivity extends Activity {

    static final String ORIGIN_HOST = "appassets.androidplatform.net";
    static final String ENTRY_URL = "https://" + ORIGIN_HOST + "/index.html";

    private static final Map<String, String> MIME_TYPES = new HashMap<>();

    static {
        MIME_TYPES.put("html", "text/html");
        MIME_TYPES.put("htm", "text/html");
        MIME_TYPES.put("css", "text/css");
        MIME_TYPES.put("js", "text/javascript");
        MIME_TYPES.put("mjs", "text/javascript");
        MIME_TYPES.put("json", "application/json");
        MIME_TYPES.put("txt", "text/plain");
        MIME_TYPES.put("md", "text/plain");
        MIME_TYPES.put("png", "image/png");
        MIME_TYPES.put("jpg", "image/jpeg");
        MIME_TYPES.put("jpeg", "image/jpeg");
        MIME_TYPES.put("gif", "image/gif");
        MIME_TYPES.put("svg", "image/svg+xml");
        MIME_TYPES.put("webp", "image/webp");
        MIME_TYPES.put("ico", "image/x-icon");
        MIME_TYPES.put("mp3", "audio/mpeg");
        MIME_TYPES.put("ogg", "audio/ogg");
        MIME_TYPES.put("wav", "audio/wav");
        MIME_TYPES.put("mp4", "video/mp4");
        MIME_TYPES.put("webm", "video/webm");
        MIME_TYPES.put("wasm", "application/wasm");
        MIME_TYPES.put("ttf", "font/ttf");
        MIME_TYPES.put("otf", "font/otf");
        MIME_TYPES.put("woff", "font/woff");
        MIME_TYPES.put("woff2", "font/woff2");
        MIME_TYPES.put("pdf", "application/pdf");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebView webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        webView.setWebViewClient(new ShellWebViewClient());
        setContentView(webView);
        webView.loadUrl(ENTRY_URL);
    }

    private final class ShellWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            // Navigation stays on the appassets origin or goes nowhere.
            return !ORIGIN_HOST.equals(request.getUrl().getHost());
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            Uri url = request.getUrl();
            if (!ORIGIN_HOST.equals(url.getHost())) {
                return status(403, "Forbidden");
            }
            String path = url.getPath();
            if (path == null || path.isEmpty() || "/".equals(path)) {
                path = "/index.html";
            }
            String assetPath = resolveAssetPath(path);
            if (assetPath == null) {
                return status(403, "Forbidden");
            }
            try {
                InputStream in = getAssets().open(assetPath);
                String mime = mimeTypeFor(assetPath);
                String charset = mime.startsWith("text/") || "application/json".equals(mime)
                        ? "UTF-8" : null;
                return new WebResourceResponse(mime, charset, in);
            } catch (IOException e) {
                return status(404, "Not Found");
            }
        }
    }

    /**
     * Maps a request path to an asset under www/, or returns null when
     * any segment climbs out (".."): Uri hands back the decoded path,
     * so an encoded traversal arrives here already decoded and fails
     * the same check. Empty and "." segments are dropped.
     */
    private static String resolveAssetPath(String path) {
        StringBuilder out = new StringBuilder("www");
        for (String segment : path.split("/")) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                return null;
            }
            out.append('/').append(segment);
        }
        return out.toString();
    }

    private static String mimeTypeFor(String assetPath) {
        int dot = assetPath.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        String mime = MIME_TYPES.get(assetPath.substring(dot + 1).toLowerCase(Locale.ROOT));
        return mime != null ? mime : "application/octet-stream";
    }

    private static WebResourceResponse status(int code, String reason) {
        return new WebResourceResponse(
                "text/plain", "UTF-8", code, reason, null, new ByteArrayInputStream(new byte[0]));
    }
}
