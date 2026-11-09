package com.battlesbudz.jarvis.v2.code

import android.content.Context
import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Turns a project into an installable APK on the phone (code-and-export
 * scope section 5.2). There is no Android build anywhere in the loop:
 * the `:webshell` module's unsigned shell APK ships inside Faraday as
 * the asset `shell/webshell.apk` (section 5.1), and an export is a zip
 * transformation of it, following the proven sequence:
 *
 * 1. Refuse early, with the reason shown: no `index.html` in the
 *    project, or the package name or label would exceed its
 *    placeholder.
 * 2. Stream the shell entry by entry into a new zip, dropping
 *    `META-INF/` entirely; `resources.arsc` is written STORED and
 *    4-byte aligned by [AlignedZipWriter].
 * 3. Patch the manifest's binary string pool in place
 *    ([BinaryXmlStringPoolPatcher]): the placeholder package and label
 *    strings become the project's real ones, zero-filled, no offsets
 *    moved.
 * 4. Inject the project files under `assets/www/`, excluding the
 *    workshop bookkeeping (`versions/` never reaches the file list;
 *    `BRIEF.md` and `rounds.log` are filtered here, and `project.json`
 *    is already excluded by [ProjectFiles]): the exported app is the
 *    app, not the workshop. The round log rides the same exclusion as
 *    the brief — it is Faraday-side bookkeeping (Battles's own request
 *    history), and the scope's rule that bookkeeping never ships
 *    inside an export covers it.
 * 5. Sign with the apksig library, APK Signature Scheme v2 only, with
 *    the install's stable key ([CodeExportSigningKey] on device).
 *    Stable key plus the store's deterministic per-project package
 *    name means re-exporting a project installs as an update.
 * 6. Verify the signed bytes with the same library before reporting
 *    success: [Result.Exported] means the package on disk passed
 *    verification on this phone, so the stats' "exported" outcome is
 *    a claim about an installable file, not about bytes written
 *    (the build 1771 exports were counted successful five times
 *    while Android refused every one of them).
 *
 * The result is written into the app's `code-exports` cache folder,
 * named after the project, ready for [CodeApkInstaller] (Decision B)
 * and for the save/share hand-off (Unit 6 wires the controls).
 *
 * The shell bytes and the signing keystore are injected seams so the
 * whole pipeline is JVM-testable; on device they are the merged asset
 * and the AndroidKeyStore (see [forContext]).
 */
class CodeApkExporter(
    private val store: CodeProjectStore,
    private val shellAsset: () -> ByteArray,
    private val signingKeyStore: () -> KeyStore,
    private val keyAlias: String = CodeExportSigningKey.KEY_ALIAS,
    private val keyPassword: CharArray? = null,
    private val outputDir: File
) {

    /** The outcome of an export: the finished APK, or the refusal with its reason. */
    sealed class Result {
        data class Exported(val apkFile: File, val packageName: String, val label: String) : Result()
        data class Refused(val reason: String) : Result()
    }

    fun exportProjectApk(project: CodeProjectStore.CodeProject): Result {
        val dir = store.projectDir(project.id)
        val entries = ProjectFiles.list(dir)
        if (entries.none { it.path == CodeProjectStore.INDEX_FILE_NAME }) {
            return Result.Refused(
                "This project has no ${CodeProjectStore.INDEX_FILE_NAME} yet, so there is no app to export."
            )
        }
        val label = project.name.trim()
        if (label.isEmpty()) {
            return Result.Refused("The project has no name to label the exported app with.")
        }
        if (label.length > LABEL_PLACEHOLDER_CHARS) {
            return Result.Refused(
                "The project name is ${label.length} characters; an exported app's label fits at most " +
                    "$LABEL_PLACEHOLDER_CHARS. Rename the project to export it."
            )
        }
        // Projects created before the store's derivation was sanitized
        // carry a dashed package name in their state, and a dash is not
        // a legal package-name character: Android's installer refuses
        // such a package with a parse error (device-proven 2026-11-09).
        // The stored name is sanitized deterministically here so those
        // projects export installable packages too, and the final name
        // is validated outright — an export that cannot install is a
        // refusal with the reason shown, never a file that fails later
        // in Downloads.
        val packageName = CodeProjectStore.sanitizePackageName(project.exportPackageName)
        if (!CodeProjectStore.isValidPackageName(packageName)) {
            return Result.Refused(
                "The derived package name \"$packageName\" is not a package name Android will install."
            )
        }
        if (packageName.length > PACKAGE_PLACEHOLDER_CHARS) {
            return Result.Refused(
                "The derived package name is ${packageName.length} characters; the shell's placeholder " +
                    "holds $PACKAGE_PLACEHOLDER_CHARS."
            )
        }
        val shell = try {
            shellAsset()
        } catch (e: Exception) {
            return Result.Refused("The web shell asset could not be read (${e.message}).")
        }
        val payloads = try {
            entries.filter { it.path !in WORKSHOP_FILES }
                .map { entry -> entry.path to File(dir, entry.path).readBytes() }
        } catch (e: Exception) {
            return Result.Refused("A project file could not be read (${e.message}).")
        }
        val unsigned = try {
            buildUnsignedApk(shell, payloads, packageName, label)
        } catch (e: BinaryXmlStringPoolPatcher.PatchException) {
            return Result.Refused(e.message ?: "The shell manifest could not be patched.")
        } catch (e: Exception) {
            return Result.Refused("The export could not be assembled (${e.message}).")
        }
        val keyStore = try {
            signingKeyStore()
        } catch (e: Exception) {
            return Result.Refused("The export signing key could not be loaded or created (${e.message}).")
        }
        val signed = try {
            signApkV2(unsigned, keyStore, keyAlias, keyPassword)
        } catch (e: Exception) {
            return Result.Refused("The export could not be signed (${e.message}).")
        }
        return try {
            outputDir.mkdirs()
            val outFile = File(outputDir, project.id + ".apk")
            val tmp = File(outputDir, project.id + ".apk.tmp")
            tmp.writeBytes(signed)
            // The success signal is gated on verification, here on the
            // device that produced the bytes: on build 1771 the stats
            // recorded five "exported" outcomes with no error while
            // every produced package was one Android refused to
            // install, so an unverified file must never be reported
            // as an export. The temp file is verified as written;
            // only a verified package is renamed into place.
            val verification = try {
                ApkVerifier.Builder(tmp).setMinCheckedPlatformVersion(SHELL_MIN_SDK).build().verify()
            } catch (e: Exception) {
                tmp.delete()
                return Result.Refused("The exported APK could not be verified (${e.message}).")
            }
            if (!verification.isVerified || !verification.isVerifiedUsingV2Scheme) {
                tmp.delete()
                val detail = verification.errors.firstOrNull()?.toString() ?: "signature verification failed"
                return Result.Refused("The exported APK failed verification on this phone: $detail")
            }
            if (!tmp.renameTo(outFile)) {
                outFile.writeBytes(signed)
                tmp.delete()
            }
            Result.Exported(outFile, packageName, label)
        } catch (e: Exception) {
            Result.Refused("The signed export could not be saved (${e.message}).")
        }
    }

    companion object {
        /** The merged-asset path of the embedded shell (section 5.1). */
        const val SHELL_ASSET_PATH = "shell/webshell.apk"

        /** The cache folder exports are written to; the `code-exports` FileProvider serves it. */
        const val EXPORTS_DIR_NAME = "code-exports"

        /** The shell's package placeholder length (section 5.1). */
        const val PACKAGE_PLACEHOLDER_CHARS = 64

        /** The shell's label placeholder length (section 5.1). */
        const val LABEL_PLACEHOLDER_CHARS = 48

        /**
         * The exact placeholder strings the `:webshell` module is built
         * with: its `applicationId` and its manifest label. The
         * exporter replaces these in the binary string pool; if either
         * is absent the export refuses. Keep both in step with
         * `webshell/build.gradle.kts` and
         * `webshell/src/main/AndroidManifest.xml` — the real-shell test
         * fails loudly if they drift.
         */
        val PLACEHOLDER_PACKAGE_NAME: String = "com.battlesbudz.faraday.app.p" + "0".repeat(35)
        val PLACEHOLDER_LABEL: String = "FaradayAppLabel" + "0".repeat(33)

        /** The shell's minSdk (section 5.1), told to the signer for its scheme decisions. */
        const val SHELL_MIN_SDK = 30

        /** Faraday-side bookkeeping in a project folder that never ships inside an export. */
        private val WORKSHOP_FILES = setOf(CodeProjectStore.BRIEF_FILE_NAME, CodeProjectStore.ROUND_LOG_FILE_NAME)

        /** The exporter wired for the app: merged shell asset, AndroidKeyStore key, cache output. */
        fun forContext(context: Context, store: CodeProjectStore): CodeApkExporter = CodeApkExporter(
            store = store,
            shellAsset = { context.assets.open(SHELL_ASSET_PATH).use { it.readBytes() } },
            signingKeyStore = { CodeExportSigningKey.loadOrCreateKeyStore() },
            outputDir = File(context.cacheDir, EXPORTS_DIR_NAME)
        )

        /**
         * Steps 2 to 4 of the export: the shell streamed through
         * [AlignedZipWriter] with `META-INF/` dropped, the manifest
         * patched, and the project files injected under `assets/www/`.
         * Shell entries keep their compression, except
         * `resources.arsc`, which is always written STORED (and so
         * 4-byte aligned). Throws
         * [BinaryXmlStringPoolPatcher.PatchException] when the shell is
         * not the shell this exporter expects.
         */
        internal fun buildUnsignedApk(
            shellApk: ByteArray,
            projectFiles: List<Pair<String, ByteArray>>,
            packageName: String,
            label: String
        ): ByteArray {
            val out = ByteArrayOutputStream()
            val writer = AlignedZipWriter(out)
            var manifestSeen = false
            ZipInputStream(ByteArrayInputStream(shellApk)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    val data = zip.readBytes()
                    zip.closeEntry()
                    if (name.startsWith("META-INF/")) continue
                    when {
                        name == MANIFEST_ENTRY -> {
                            manifestSeen = true
                            val patched = BinaryXmlStringPoolPatcher.patch(
                                data,
                                mapOf(PLACEHOLDER_PACKAGE_NAME to packageName, PLACEHOLDER_LABEL to label)
                            )
                            writeEntry(writer, name, patched, entry.method)
                        }
                        name == RESOURCES_ENTRY -> writer.addStored(name, data)
                        else -> writeEntry(writer, name, data, entry.method)
                    }
                }
            }
            if (!manifestSeen) {
                throw BinaryXmlStringPoolPatcher.PatchException(
                    "The web shell has no $MANIFEST_ENTRY; it is not the shell this exporter expects."
                )
            }
            for ((path, data) in projectFiles) {
                writer.addDeflated("$WWW_PREFIX$path", data)
            }
            writer.finish()
            return out.toByteArray()
        }

        private fun writeEntry(writer: AlignedZipWriter, name: String, data: ByteArray, shellMethod: Int) {
            if (shellMethod == ZipEntry.STORED) writer.addStored(name, data) else writer.addDeflated(name, data)
        }

        /** Step 5: APK Signature Scheme v2 only, via the apksig library (scope section 2, fact 7). */
        internal fun signApkV2(
            unsignedApk: ByteArray,
            keyStore: KeyStore,
            alias: String,
            keyPassword: CharArray?
        ): ByteArray {
            val key = keyStore.getKey(alias, keyPassword) as? PrivateKey
                ?: throw IllegalStateException("No private key under the export signing alias.")
            val chain = keyStore.getCertificateChain(alias)
                ?.map { it as? X509Certificate ?: throw IllegalStateException("The export certificate chain holds a non-X.509 certificate.") }
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException("No certificate chain under the export signing alias.")
            val signerConfig = ApkSigner.SignerConfig.Builder(CodeExportSigningKey.CERT_SUBJECT, key, chain).build()
            val input = File.createTempFile("faraday-export-in-", ".apk")
            val output = File.createTempFile("faraday-export-out-", ".apk")
            try {
                input.writeBytes(unsignedApk)
                ApkSigner.Builder(listOf(signerConfig))
                    .setInputApk(input)
                    .setOutputApk(output)
                    .setMinSdkVersion(SHELL_MIN_SDK)
                    .setV1SigningEnabled(false)
                    .setV2SigningEnabled(true)
                    .setV3SigningEnabled(false)
                    .setCreatedBy("Faraday")
                    .build()
                    .sign()
                return output.readBytes()
            } finally {
                input.delete()
                output.delete()
            }
        }

        private const val MANIFEST_ENTRY = "AndroidManifest.xml"
        private const val RESOURCES_ENTRY = "resources.arsc"
        private const val WWW_PREFIX = "assets/www/"
    }
}
