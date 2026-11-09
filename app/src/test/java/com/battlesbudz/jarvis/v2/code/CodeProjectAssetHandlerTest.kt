package com.battlesbudz.jarvis.v2.code

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The preview's asset handler (scope Unit 4): resolution inside the
 * project root, and refusal of everything outside it — traversals and
 * symlink escapes included — at the handler level.
 */
class CodeProjectAssetHandlerTest {

    @get:Rule val temp = TemporaryFolder()

    private lateinit var projectDir: File
    private lateinit var handler: CodeProjectAssetHandler

    @Before fun setUp() {
        projectDir = temp.newFolder("project")
        File(projectDir, "index.html").writeText("<!DOCTYPE html><html></html>")
        File(projectDir, "app.js").writeText("let x = 1;")
        File(projectDir, "sub").mkdirs()
        File(projectDir, "sub/style.css").writeText("body {}")
        handler = CodeProjectAssetHandler(projectDir)
    }

    private fun canonical(relative: String): File = File(projectDir, relative).canonicalFile

    @Test fun rootPathServesTheIndexPage() {
        assertEquals(canonical("index.html"), handler.fileFor("/"))
        assertEquals(canonical("index.html"), handler.fileFor(""))
        assertEquals(canonical("index.html"), handler.fileFor("/index.html"))
    }

    @Test fun nestedProjectFilesResolve() {
        assertEquals(canonical("app.js"), handler.fileFor("/app.js"))
        assertEquals(canonical("sub/style.css"), handler.fileFor("/sub/style.css"))
    }

    @Test fun missingFilesAndDirectoriesAreRefused() {
        assertNull(handler.fileFor("/nope.js"))
        assertNull(handler.fileFor("/sub"))
    }

    @Test fun traversalOutsideTheProjectIsRefused() {
        val secret = temp.newFile("secret.txt")
        secret.writeText("not project content")
        assertNull(handler.fileFor("/../secret.txt"))
        assertNull(handler.fileFor("/sub/../../secret.txt"))
        assertNull(handler.fileFor("/../../../../etc/passwd"))
    }

    @Test fun symlinkPointingOutsideTheProjectIsRefused() {
        val outside = temp.newFile("outside.txt")
        outside.writeText("outside")
        val link = File(projectDir, "link.txt")
        val created = try {
            Files.createSymbolicLink(link.toPath(), outside.toPath())
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue("symlinks unavailable on this filesystem", created)
        assertNull(handler.fileFor("/link.txt"))
    }

    @Test fun servedFilesCarryNoStoreHeaders() {
        // A round rewrites project files under the same URLs; a
        // cacheable response is a stale preview (build 1771 showed
        // the starter page after completed rounds).
        assertEquals("no-store", CodeProjectAssetHandler.NO_STORE_HEADERS["Cache-Control"])
        assertEquals("no-cache", CodeProjectAssetHandler.NO_STORE_HEADERS["Pragma"])
    }

    @Test fun mimeTypesFollowTheExtension() {
        assertEquals("text/html", CodeProjectAssetHandler.mimeTypeFor("/index.html"))
        assertEquals("text/css", CodeProjectAssetHandler.mimeTypeFor("/style.css"))
        assertEquals("text/javascript", CodeProjectAssetHandler.mimeTypeFor("/app.js"))
        assertEquals("image/png", CodeProjectAssetHandler.mimeTypeFor("/sprite.PNG"))
        assertEquals("application/json", CodeProjectAssetHandler.mimeTypeFor("/data.json"))
        assertEquals("application/octet-stream", CodeProjectAssetHandler.mimeTypeFor("/blob.bin"))
    }
}
