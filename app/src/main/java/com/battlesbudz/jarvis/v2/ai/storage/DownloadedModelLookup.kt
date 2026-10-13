package com.battlesbudz.jarvis.v2.ai.storage

import android.content.Context
import android.content.ContentUris
import android.os.CancellationSignal
import android.os.Environment
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume


import com.battlesbudz.jarvis.v2.ai.LocalModelSpec
import com.battlesbudz.jarvis.v2.ai.importFileNames

/** Exact-name Downloads queries only; never scans unrelated user storage. */
internal class DownloadedModelLookup(context: Context) {
    private val context = context.applicationContext
    suspend fun find(spec: LocalModelSpec): Uri? {
        for (name in spec.importFileNames()) {
            // Generic publisher names identify multiple models; only explicit
            // picker imports may use those, with the selected model's hash.
            if (name != spec.fileName && name in setOf("model.litertlm", "model_block32.litertlm", "model_block128.litertlm")) continue
            findExact(spec.copy(fileName = name))?.let { return it }
        }
        return null
    }

    private suspend fun findExact(spec: LocalModelSpec): Uri? =
        suspendCancellableCoroutine { continuation ->
            val cancellationSignal = CancellationSignal()
            continuation.invokeOnCancellation { cancellationSignal.cancel() }
            val result = runCatching {
                val projection = arrayOf(
                    MediaStore.Downloads._ID,
                    MediaStore.Downloads.RELATIVE_PATH
                )
                context.contentResolver.query(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    projection,
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?",
                    arrayOf(spec.fileName),
                    null,
                    cancellationSignal
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
                    val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.RELATIVE_PATH)
                    while (cursor.moveToNext()) {
                        if (cursor.getString(pathIndex).equals(
                                "${Environment.DIRECTORY_DOWNLOADS}/",
                                ignoreCase = true
                            )
                        ) {
                            return@use ContentUris.withAppendedId(
                                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                cursor.getLong(idIndex)
                            )
    }

}
                    null
                }
            }.getOrNull()
                ?: findExactFileInMediaStore(spec, cancellationSignal)
                ?: findExactDownloadsDocument(spec)
            if (continuation.isActive) continuation.resume(result)
        }

    /**
     * Some Android builds expose Downloads files through Files rather than
     * MediaStore.Downloads. Keep this an exact display-name/path query; it is
     * not a storage scan.
     */
    private fun findExactFileInMediaStore(
        spec: LocalModelSpec,
        cancellationSignal: CancellationSignal
    ): Uri? = runCatching {
        val collection = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.RELATIVE_PATH
        )
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
            arrayOf(spec.fileName),
            null,
            cancellationSignal
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            while (cursor.moveToNext()) {
                if (cursor.getString(pathIndex).equals(
                        "${Environment.DIRECTORY_DOWNLOADS}/",
                        ignoreCase = true
                    )
                ) {
                    return@use ContentUris.withAppendedId(collection, cursor.getLong(idIndex))
                }
            }
            null
        }
    }.getOrNull()

    /**
     * The system picker reads the Downloads DocumentsProvider, which can contain
     * files that are not yet represented by the MediaStore Downloads table.
     * Query that same provider as an exact-name fallback so setup agrees with
     * what the user sees when manually importing from Downloads.
     */
    private fun findExactDownloadsDocument(spec: LocalModelSpec): Uri? {
        // DocumentsUI exposes the phone's public Downloads folder through the
        // external-storage provider as primary:Download. This is the provider
        // shown by the picker in the setup screenshots.
        findExactDocumentInChildren(
            authority = "com.android.externalstorage.documents",
            parentDocumentId = "primary:Download",
            spec = spec
        )?.let { return it }

        return runCatching {
            val authority = "com.android.providers.downloads.documents"
            val rootProjection = arrayOf(
                DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE
            )
            val rootDocumentId = context.contentResolver.query(
                DocumentsContract.buildRootsUri(authority),
                rootProjection,
                null,
                null,
                null
            )?.use { cursor ->
                var selected: String? = null
                val documentIdIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                val titleIndex = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
                while (cursor.moveToNext()) {
                    val documentId = cursor.getString(documentIdIndex)
                    val title = cursor.getString(titleIndex)
                    if (title.equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true) ||
                        documentId.equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true)
                    ) {
                        selected = documentId
                        break
                    }
                }
                selected
            } ?: return@runCatching null

            findExactDocumentInChildren(authority, rootDocumentId, spec)
        }.getOrNull()
    }

    private fun findExactDocumentInChildren(
        authority: String,
        parentDocumentId: String,
        spec: LocalModelSpec
    ): Uri? = runCatching {
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        context.contentResolver.query(
            DocumentsContract.buildChildDocumentsUri(authority, parentDocumentId),
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == spec.fileName) {
                    return@use DocumentsContract.buildDocumentUri(authority, cursor.getString(idIndex))
                }
            }
            null
        }
    }.getOrNull()

}
