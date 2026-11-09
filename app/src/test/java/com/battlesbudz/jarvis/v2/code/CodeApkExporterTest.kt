package com.battlesbudz.jarvis.v2.code

import com.android.apksig.ApkVerifier
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The exporter end to end over a fixture shell and a real project
 * folder (scope section 6, Unit 5 tests): the output zip's entry list,
 * the patched manifest strings, resources.arsc stored and aligned,
 * META-INF gone, the workshop files absent — and the produced APK
 * verified with apksig's own verifier, v2 only. Refusals and the
 * placeholder-fit derivation are pinned too.
 */
class CodeApkExporterTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun exportProducesAPatchedAlignedV2SignedApk() {
        val store = newStore()
        val project = snakeProject(store)
        val result = exporter(store, fixtureShellApk()).exportProjectApk(project)

        val exported = result as CodeApkExporter.Result.Exported
        assertEquals(project.exportPackageName, exported.packageName)
        assertEquals("Snake Game", exported.label)
        assertTrue(exported.apkFile.isFile)

        ZipFile(exported.apkFile).use { zip ->
            assertEquals(
                setOf(
                    "AndroidManifest.xml",
                    "resources.arsc",
                    "classes.dex",
                    "assets/www/index.html",
                    "assets/www/app.js"
                ),
                zip.entries().toList().map { it.name }.toSet()
            )
            assertEquals(
                SNAKE_HTML,
                zip.getInputStream(zip.getEntry("assets/www/index.html")).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
            )
            assertEquals(
                SNAKE_JS,
                zip.getInputStream(zip.getEntry("assets/www/app.js")).use { it.readBytes() }
                    .toString(Charsets.UTF_8)
            )
            // resources.arsc stays STORED in the signed output.
            assertEquals(ZipEntry.STORED, zip.getEntry("resources.arsc").method)
            val manifestBytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            val strings = BinaryXmlStringPoolPatcher.stringsOf(manifestBytes)
            assertTrue(strings.contains(project.exportPackageName))
            assertTrue(strings.contains("Snake Game"))
            assertFalse(strings.contains(CodeApkExporter.PLACEHOLDER_PACKAGE_NAME))
            assertFalse(strings.contains(CodeApkExporter.PLACEHOLDER_LABEL))
        }

        // And STORED + aligned in the final bytes, not just the unsigned ones.
        val arsc = localZipEntries(exported.apkFile.readBytes()).single { it.name == "resources.arsc" }
        assertEquals(ZipEntry.STORED, arsc.method)
        assertEquals(0, arsc.dataStart % 4)

        val verification = ApkVerifier.Builder(exported.apkFile)
            .setMinCheckedPlatformVersion(30)
            .build()
            .verify()
        assertTrue("Export must verify: ${verification.errors}", verification.isVerified)
        assertTrue(verification.isVerifiedUsingV2Scheme)
        assertFalse(verification.isVerifiedUsingV1Scheme)
    }

    @Test
    fun exportOverAUtf8PoolShellPatchesTheSameWay() {
        val store = newStore()
        val project = snakeProject(store)
        val result = exporter(store, fixtureShellApk(utf8Pool = true)).exportProjectApk(project)
        val exported = result as CodeApkExporter.Result.Exported
        ZipFile(exported.apkFile).use { zip ->
            val manifestBytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            val strings = BinaryXmlStringPoolPatcher.stringsOf(manifestBytes)
            assertTrue(strings.contains(project.exportPackageName))
            assertTrue(strings.contains("Snake Game"))
        }
    }

    @Test
    fun exportRepairsALegacyDashedPackageName() {
        // A project created before the derivation was sanitized (the
        // on-device snake-game project from build 1771) carries the
        // dashed form in its saved state. Its export must come out
        // under the sanitized name — the name the manifest is patched
        // with and the name the result reports.
        val store = newStore()
        val created = snakeProject(store)
        val legacy = created.copy(
            exportPackageName = "com.battlesbudz.faraday.app.snake-game_0123abcd"
        )
        store.saveProject(legacy)
        val project = store.loadProject(legacy.id)!!
        val exported = exporter(store, fixtureShellApk()).exportProjectApk(project)
            as CodeApkExporter.Result.Exported
        assertEquals("com.battlesbudz.faraday.app.snake_game_0123abcd", exported.packageName)
        assertTrue(CodeProjectStore.isValidPackageName(exported.packageName))
        ZipFile(exported.apkFile).use { zip ->
            val manifestBytes = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            val strings = BinaryXmlStringPoolPatcher.stringsOf(manifestBytes)
            assertTrue(strings.contains(exported.packageName))
            assertFalse(strings.contains(legacy.exportPackageName))
        }
    }

    @Test
    fun exportRefusesAProjectWithoutIndexHtml() {
        val store = newStore()
        val project = store.createProject("Empty App", "nothing yet", 1_000L)
        File(store.projectDir(project.id), CodeProjectStore.INDEX_FILE_NAME).delete()
        val result = exporter(store, fixtureShellApk()).exportProjectApk(project)
        val refused = result as CodeApkExporter.Result.Refused
        assertTrue(refused.reason.contains(CodeProjectStore.INDEX_FILE_NAME))
    }

    @Test
    fun exportRefusesAnOversizeLabel() {
        val store = newStore()
        val project = store.createProject("N".repeat(60), "a long-named app", 1_000L)
        val result = exporter(store, fixtureShellApk()).exportProjectApk(project)
        val refused = result as CodeApkExporter.Result.Refused
        assertTrue(refused.reason.contains(CodeApkExporter.LABEL_PLACEHOLDER_CHARS.toString()))
    }

    @Test
    fun theDerivationAlwaysFitsThePlaceholders() {
        assertEquals(CodeApkExporter.PACKAGE_PLACEHOLDER_CHARS, CodeApkExporter.PLACEHOLDER_PACKAGE_NAME.length)
        assertEquals(CodeApkExporter.LABEL_PLACEHOLDER_CHARS, CodeApkExporter.PLACEHOLDER_LABEL.length)
        // The longest package the derivation can produce: prefix + 24
        // slug chars + "_" + 8 hex digits = 61, inside the 64 placeholder.
        val longest = CodeProjectStore.exportPackageName("z".repeat(CodeProjectStore.MAX_SLUG_CHARS))
        assertEquals(61, longest.length)
        assertTrue(longest.length <= CodeApkExporter.PACKAGE_PLACEHOLDER_CHARS)
        // The longest legal label is the exporter's acceptance bound
        // (48, pinned here), which equals the placeholder's length, so
        // a longest-legal label fits its slot exactly.
        assertEquals(48, CodeApkExporter.LABEL_PLACEHOLDER_CHARS)
    }

    @Test
    fun packageNamesAreStablePerProjectAndDistinctAcrossProjects() {
        val snake = CodeProjectStore.exportPackageName("snake-game")
        assertEquals(snake, CodeProjectStore.exportPackageName("snake-game"))
        assertNotEquals(snake, CodeProjectStore.exportPackageName("tank-game"))
    }

    @Test
    fun theUnsignedBuildIsDeterministic() {
        val files = listOf("index.html" to SNAKE_HTML.toByteArray(Charsets.UTF_8))
        val first = CodeApkExporter.buildUnsignedApk(fixtureShellApk(), files, PACKAGE, LABEL)
        val second = CodeApkExporter.buildUnsignedApk(fixtureShellApk(), files, PACKAGE, LABEL)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun reexportKeepsThePackageAndVerifiesAgain() {
        val store = newStore()
        val project = snakeProject(store)
        val exporter = exporter(store, fixtureShellApk())
        val first = exporter.exportProjectApk(project) as CodeApkExporter.Result.Exported
        val second = exporter.exportProjectApk(project) as CodeApkExporter.Result.Exported
        assertEquals(first.packageName, second.packageName)
        assertEquals(first.apkFile, second.apkFile)
        val verification = ApkVerifier.Builder(second.apkFile)
            .setMinCheckedPlatformVersion(30)
            .build()
            .verify()
        assertTrue(verification.isVerifiedUsingV2Scheme)
    }

    private fun newStore(): CodeProjectStore = CodeProjectStore(File(tmp.root, "workspace"))

    /** A project with real content, a round log, and a version snapshot — the full workshop on disk. */
    private fun snakeProject(store: CodeProjectStore): CodeProjectStore.CodeProject {
        val project = store.createProject("Snake Game", "a snake game", 1_000L)
        val dir = store.projectDir(project.id)
        File(dir, CodeProjectStore.INDEX_FILE_NAME).writeText(SNAKE_HTML)
        File(dir, "app.js").writeText(SNAKE_JS)
        store.appendRoundLog(project, "make a snake game", "written", 2_000L)
        store.snapshotVersion(project)
        return store.loadProject(project.id)!!
    }

    private fun exporter(store: CodeProjectStore, shell: ByteArray): CodeApkExporter = CodeApkExporter(
        store = store,
        shellAsset = { shell },
        signingKeyStore = { loadTestKeyStore() },
        keyAlias = TEST_KEY_ALIAS,
        keyPassword = TEST_KEYSTORE_PASSWORD.toCharArray(),
        outputDir = File(tmp.root, "out")
    )

    private companion object {
        const val PACKAGE = "com.battlesbudz.faraday.app.snake_game_0123abcd"
        const val LABEL = "Snake Game"
        const val SNAKE_HTML = "<!DOCTYPE html><html><body><canvas id=\"game\"></canvas></body></html>"
        const val SNAKE_JS = "const canvas = document.getElementById('game');"
    }
}
