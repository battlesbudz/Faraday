package com.battlesbudz.jarvis.v2.code

import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import java.io.File
import java.io.IOException

/**
 * Serves one open project's folder to the preview (code-and-export
 * scope section 4.5): a [WebViewAssetLoader.PathHandler] rooted at
 * the project directory, mounted at `/` on the appassets origin.
 *
 * Path safety lives here, at the handler level, and is deliberately
 * not delegated to the loader: [fileFor] resolves the request path
 * against the project root, canonicalizes, and refuses anything whose
 * canonical form is not the project folder itself or strictly inside
 * it — `..` segments, encoded traversals after decoding, and symlinks
 * pointing out of the project all fail the same containment check.
 * Directories and missing files are refused too: only real project
 * files are served. The bare root path serves `index.html`, which is
 * the page the preview loads.
 */
class CodeProjectAssetHandler(private val projectDir: File) : WebViewAssetLoader.PathHandler {

    private val rootCanonical: File = projectDir.canonicalFile

    override fun handle(path: String): WebResourceResponse? {
        val file = fileFor(path) ?: return null
        val mime = mimeTypeFor(path)
        return try {
            WebResourceResponse(mime, if (isTextMime(mime)) "utf-8" else null, file.inputStream())
        } catch (e: IOException) {
            null
        }
    }

    /**
     * The file [path] (a loader path such as `/index.html` or
     * `/app.js`) maps to inside the project, or null when it must be
     * refused: an escape, a directory, or nothing on disk.
     */
    fun fileFor(path: String): File? {
        val relative = path.trimStart('/')
        val target =
            if (relative.isEmpty()) File(rootCanonical, CodeProjectStore.INDEX_FILE_NAME)
            else File(rootCanonical, relative)
        val canonical = target.canonicalFile
        val inside = canonical == rootCanonical ||
            canonical.path.startsWith(rootCanonical.path + File.separator)
        if (!inside || !canonical.isFile) return null
        return canonical
    }

    companion object {
        /** Extension-to-MIME for the file kinds a generated web project carries. */
        fun mimeTypeFor(path: String): String =
            when (path.substringAfterLast('.', "").lowercase()) {
                "html", "htm" -> "text/html"
                "css" -> "text/css"
                "js", "mjs" -> "text/javascript"
                "json" -> "application/json"
                "svg" -> "image/svg+xml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "ico" -> "image/x-icon"
                "txt", "md" -> "text/plain"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                else -> "application/octet-stream"
            }

        private fun isTextMime(mime: String): Boolean =
            mime.startsWith("text/") || mime == "application/json" || mime == "image/svg+xml"
    }
}
