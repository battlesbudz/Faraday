package com.battlesbudz.jarvis.v2.code

import com.android.apksig.ApkVerifier
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The exporter over the real CI-built shell (scope section 6, Unit 5:
 * the fixture export verifies — and this is the fixture that is not a
 * fixture). The shell reaches the test through the build itself:
 * `copyWebShell` runs off `preBuild`, so by the time unit tests run,
 * the unsigned shell APK the app will embed sits in the build outputs.
 * If the :webshell manifest's placeholders ever drift from the
 * exporter's constants, this test fails loudly instead of the export
 * failing quietly on a phone.
 */
class CodeApkExportRealShellTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun realShellCarriesTheExporterPlaceholders() {
        val strings = BinaryXmlStringPoolPatcher.stringsOf(manifestBytes(shellBytes()))
        assertTrue(
            "The shell manifest must carry the exporter's package placeholder",
            strings.contains(CodeApkExporter.PLACEHOLDER_PACKAGE_NAME)
        )
        assertTrue(
            "The shell manifest must carry the exporter's label placeholder",
            strings.contains(CodeApkExporter.PLACEHOLDER_LABEL)
        )
    }

    @Test
    fun realShellExportsIntoAVerifyingApk() {
        val store = CodeProjectStore(File(tmp.root, "workspace"))
        val project = store.createProject("Tank Game", "a tank game", 1_000L)
        File(store.projectDir(project.id), CodeProjectStore.INDEX_FILE_NAME)
            .writeText("<!DOCTYPE html><html><body>tank</body></html>")
        val exporter = CodeApkExporter(
            store = store,
            shellAsset = { shellBytes() },
            signingKeyStore = { loadTestKeyStore() },
            keyAlias = TEST_KEY_ALIAS,
            keyPassword = TEST_KEYSTORE_PASSWORD.toCharArray(),
            outputDir = File(tmp.root, "out")
        )
        val exported = exporter.exportProjectApk(project) as CodeApkExporter.Result.Exported
        assertEquals(project.exportPackageName, exported.packageName)
        assertEquals("Tank Game", exported.label)

        ZipFile(exported.apkFile).use { zip ->
            val names = zip.entries().toList().map { it.name }
            assertTrue(names.contains("assets/www/index.html"))
            assertTrue(names.contains("classes.dex"))
            assertTrue(names.none { it.startsWith("META-INF/") })
            val strings = BinaryXmlStringPoolPatcher.stringsOf(
                zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            )
            assertTrue(strings.contains(project.exportPackageName))
            assertTrue(strings.contains("Tank Game"))
        }
        val arsc = localZipEntries(exported.apkFile.readBytes())
            .firstOrNull { it.name == "resources.arsc" }
        if (arsc != null) {
            assertEquals(ZipEntry.STORED, arsc.method)
            assertEquals(0, arsc.dataStart % 4)
        }

        val verification = ApkVerifier.Builder(exported.apkFile)
            .setMinCheckedPlatformVersion(30)
            .build()
            .verify()
        assertTrue("Real-shell export must verify: ${verification.errors}", verification.isVerified)
        assertTrue(verification.isVerifiedUsingV2Scheme)
    }

    /**
     * The build 1771 device regression, pinned: Battles's "snake game"
     * project exported an APK that Android refused from Downloads
     * ("there is something wrong with this file") and from the in-app
     * Install button, while the JVM suite verified those same bytes —
     * because nothing checked the one thing that differed on device:
     * the derived package name carried the id slug's dash, which is
     * not a legal package-name character. This test runs the exact
     * device scenario (a space-named project over the real shell) and
     * validates the produced package the way the installer does:
     * the name against Android's package rules, the manifest's
     * package string read back out of the binary XML, every zip
     * entry read end to end (CRC-checked), and the v2 signature.
     */
    @Test
    fun snakeGameExportsAValidInstallablePackage() {
        val store = CodeProjectStore(File(tmp.root, "workspace"))
        val project = store.createProject("Snake Game", "a snake game", 1_000L)
        File(store.projectDir(project.id), CodeProjectStore.INDEX_FILE_NAME)
            .writeText("<!DOCTYPE html><html><body>snake</body></html>")
        val exporter = CodeApkExporter(
            store = store,
            shellAsset = { shellBytes() },
            signingKeyStore = { loadTestKeyStore() },
            keyAlias = TEST_KEY_ALIAS,
            keyPassword = TEST_KEYSTORE_PASSWORD.toCharArray(),
            outputDir = File(tmp.root, "out")
        )
        val exported = exporter.exportProjectApk(project) as CodeApkExporter.Result.Exported

        assertTrue(
            "Exported package name must be one Android will install: ${exported.packageName}",
            CodeProjectStore.isValidPackageName(exported.packageName)
        )
        assertFalse(exported.packageName.contains('-'))

        ZipFile(exported.apkFile).use { zip ->
            // A full read of every entry: ZipFile checks each entry's
            // CRC as its stream is consumed, so a structurally corrupt
            // package fails here rather than on a phone.
            for (entry in zip.entries()) {
                zip.getInputStream(entry).use { it.readBytes() }
            }
            val strings = BinaryXmlStringPoolPatcher.stringsOf(
                zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
            )
            assertTrue(strings.contains(exported.packageName))
            assertTrue(strings.contains("Snake Game"))
        }

        val verification = ApkVerifier.Builder(exported.apkFile)
            .setMinCheckedPlatformVersion(30)
            .build()
            .verify()
        assertTrue("Snake-game export must verify: ${verification.errors}", verification.isVerified)
        assertTrue(verification.isVerifiedUsingV2Scheme)
    }

    private fun manifestBytes(apk: ByteArray): ByteArray {
        ZipInputStream(ByteArrayInputStream(apk)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "AndroidManifest.xml") return zip.readBytes()
                zip.closeEntry()
            }
        }
        error("No AndroidManifest.xml in the shell APK")
    }

    private fun shellBytes(): ByteArray {
        val candidates = listOf(
            File("build/webshell/shell/webshell.apk"),
            File("app/build/webshell/shell/webshell.apk"),
            File("../app/build/webshell/shell/webshell.apk"),
            File("../webshell/build/outputs/apk/release/webshell-release-unsigned.apk"),
            File("webshell/build/outputs/apk/release/webshell-release-unsigned.apk")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "The CI-built web shell was not found; copyWebShell should have produced it before " +
                "the unit tests ran. Tried: ${candidates.joinToString { it.path }}",
            found
        )
        return found!!.readBytes()
    }
}
