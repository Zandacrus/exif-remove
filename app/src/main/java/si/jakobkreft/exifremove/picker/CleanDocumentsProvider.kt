// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking
import si.jakobkreft.exifremove.R
import si.jakobkreft.exifremove.data.AppRepository
import si.jakobkreft.exifremove.data.Template
import si.jakobkreft.exifremove.engine.MediaAccess

/**
 * Publishes the device's photos and videos to the system file picker, once
 * per cleaning template. Picking `EXIF Remove — Remove everything` in an
 * upload dialog hands the other app a cleaned copy; the original never
 * leaves this process.
 *
 * Only DocumentsUI can query this provider (it is guarded by
 * MANAGE_DOCUMENTS in the manifest); the picking app receives nothing but a
 * read grant on the single document the user chose.
 */
class CleanDocumentsProvider : DocumentsProvider() {

    private val appContext: Context
        get() = context?.applicationContext ?: throw FileNotFoundException("No context")

    override fun onCreate(): Boolean = true

    // ------------------------------------------------------------- roots

    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        val templates = try {
            runBlocking { AppRepository.get(appContext).currentState().templates }
        } catch (e: Exception) {
            Template.builtIns()
        }
        // A root per template rather than one root with a setting: the choice
        // of what to strip belongs in the picker, where the file is chosen,
        // not behind a trip back into this app.
        templates.forEach { template ->
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, PickerIntegration.rootDocumentId(template.id))
                add(Root.COLUMN_DOCUMENT_ID, PickerIntegration.rootDocumentId(template.id))
                add(Root.COLUMN_TITLE, appContext.getString(R.string.app_name))
                add(Root.COLUMN_SUMMARY, template.name)
                add(Root.COLUMN_MIME_TYPES, "image/*\nvideo/*")
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
                add(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD)
            }
        }
        return cursor
    }

    // --------------------------------------------------------- documents

    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val state = runBlocking { AppRepository.get(appContext).currentState() }
        val template = PickerIntegration.templateFor(documentId, state.templates)
            ?: throw FileNotFoundException("Unknown template in $documentId")

        if (PickerIntegration.isRoot(documentId)) {
            cursor.newRow().apply {
                add(Document.COLUMN_DOCUMENT_ID, documentId)
                add(Document.COLUMN_DISPLAY_NAME, template.name)
                add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                add(Document.COLUMN_FLAGS, Document.FLAG_DIR_PREFERS_GRID)
            }
            return cursor
        }

        val mediaId = PickerIntegration.mediaIdOf(documentId)
            ?: throw FileNotFoundException("Malformed document id $documentId")
        val item = MediaCatalog.item(appContext, mediaId)
            ?: throw FileNotFoundException("No media $mediaId")
        addFileRow(cursor, documentId, item, state.randomFileNames, state.convertUnsupported)
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val cursor = MatrixCursor(projection ?: DOCUMENT_PROJECTION)
        val state = runBlocking { AppRepository.get(appContext).currentState() }

        // A content provider cannot ask for a runtime permission, and reading
        // the gallery without ACCESS_MEDIA_LOCATION would hand back files the
        // system has already half-redacted. Say so instead of looking empty.
        if (!MediaAccess.hasFullAccess(appContext) &&
            MediaAccess.requiredPermissions().isNotEmpty()
        ) {
            cursor.extras = Bundle().apply {
                putString(
                    android.provider.DocumentsContract.EXTRA_INFO,
                    appContext.getString(R.string.picker_needs_permission),
                )
            }
            return cursor
        }

        MediaCatalog.list(appContext, state.convertUnsupported).forEach { item ->
            addFileRow(
                cursor,
                PickerIntegration.documentId(PickerIntegration.templateIdOf(parentDocumentId), item.id),
                item,
                state.randomFileNames,
                state.convertUnsupported,
            )
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean =
        PickerIntegration.isRoot(parentDocumentId) &&
            PickerIntegration.templateIdOf(documentId) == parentDocumentId

    /**
     * The name and type advertised here are the ones the picking app reads
     * back, so they describe the cleaned output rather than the source: a
     * converted HEIC arrives as a JPEG, and with random file names on, the
     * original name is never handed over.
     *
     * Size and last-modified are deliberately absent. The cleaned size is not
     * known until the file is cleaned, and a modification time would give
     * back the capture date this app was asked to remove.
     */
    private fun addFileRow(
        cursor: MatrixCursor,
        documentId: String,
        item: MediaCatalog.Item,
        randomFileNames: Boolean,
        convertUnsupported: Boolean,
    ) {
        val output = OutputName.of(documentId, item, randomFileNames, convertUnsupported)
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, output.name)
            add(Document.COLUMN_MIME_TYPE, output.mimeType)
            add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_THUMBNAIL)
        }
    }

    // ------------------------------------------------------------ bytes

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read-only provider")
        val file = CleanOnRead.cleanedFile(appContext, documentId, signal)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /**
     * Thumbnails are decoded from the original, which is safe: cleaning never
     * touches pixels, so the preview is the same image the picker would show
     * anywhere else — and a freshly encoded thumbnail carries no metadata.
     */
    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point,
        signal: CancellationSignal?,
    ): AssetFileDescriptor {
        val mediaId = PickerIntegration.mediaIdOf(documentId)
            ?: throw FileNotFoundException("Malformed document id $documentId")
        val file = Thumbnails.of(appContext, mediaId, sizeHint, signal)
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun getDocumentType(documentId: String): String =
        if (PickerIntegration.isRoot(documentId)) {
            Document.MIME_TYPE_DIR
        } else {
            val mediaId = PickerIntegration.mediaIdOf(documentId)
                ?: throw FileNotFoundException("Malformed document id $documentId")
            val item = MediaCatalog.item(appContext, mediaId)
                ?: throw FileNotFoundException("No media $mediaId")
            val state = runBlocking { AppRepository.get(appContext).currentState() }
            OutputName.of(documentId, item, state.randomFileNames, state.convertUnsupported).mimeType
        }

    companion object {
        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_MIME_TYPES,
            Root.COLUMN_ICON,
            Root.COLUMN_FLAGS,
        )

        private val DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
        )
    }
}
