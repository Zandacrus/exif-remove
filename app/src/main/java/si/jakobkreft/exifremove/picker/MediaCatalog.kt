// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/**
 * The gallery as the picker sees it. One MediaStore query covers images and
 * videos together, so the listing stays in one chronological order instead of
 * two interleaved ones.
 */
object MediaCatalog {

    data class Item(val id: Long, val mimeType: String, val displayName: String?)

    /** Formats the engine can strip without re-encoding. */
    private val IN_PLACE_IMAGE_MIMES = setOf("image/jpeg", "image/png", "image/webp")

    /** Formats only reachable by decoding and re-encoding to JPEG. */
    private val CONVERTIBLE_IMAGE_MIMES = setOf(
        "image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence",
        "image/avif", "image/x-adobe-dng", "image/tiff", "image/bmp", "image/gif",
    )

    /**
     * A cap rather than the whole gallery: DocumentsUI loads the cursor in
     * one go, and a listing this long is already far past what anyone scrolls
     * before using the picker's own search.
     */
    private const val MAX_ROWS = 5000

    private fun filesUri(): Uri = MediaStore.Files.getContentUri("external")

    fun list(context: Context, convertUnsupported: Boolean): List<Item> {
        val mimes = supportedMimes(convertUnsupported)
        val placeholders = mimes.joinToString(",") { "?" }
        val selection =
            "${MediaStore.Files.FileColumns.MIME_TYPE} IN ($placeholders) OR " +
                "${MediaStore.Files.FileColumns.MEDIA_TYPE} = ?"
        val args = mimes.toTypedArray() +
            MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString()
        val out = ArrayList<Item>()
        query(context, selection, args, "${MediaStore.Files.FileColumns.DATE_ADDED} DESC") { cursor ->
            while (cursor.moveToNext() && out.size < MAX_ROWS) {
                val mime = cursor.getString(1) ?: continue
                out += Item(cursor.getLong(0), mime, cursor.getString(2))
            }
        }
        return out
    }

    fun item(context: Context, mediaId: Long): Item? {
        var found: Item? = null
        query(
            context,
            "${MediaStore.Files.FileColumns._ID} = ?",
            arrayOf(mediaId.toString()),
            null,
        ) { cursor ->
            if (cursor.moveToFirst()) {
                val mime = cursor.getString(1)
                if (mime != null) found = Item(cursor.getLong(0), mime, cursor.getString(2))
            }
        }
        return found
    }

    /**
     * The typed MediaStore row — images under Images, videos under Video.
     * The generic files collection would do for reading bytes, but
     * `setRequireOriginal` and `loadThumbnail` are both specified against the
     * typed collections, and those are exactly the two calls this app needs.
     */
    fun uri(item: Item): Uri = Uri.withAppendedPath(
        if (isVideo(item.mimeType)) {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        },
        item.id.toString(),
    )

    fun isVideo(mimeType: String): Boolean = mimeType.startsWith("video/")

    /** True when cleaning this type means re-encoding it to JPEG. */
    fun needsConversion(mimeType: String): Boolean = mimeType in CONVERTIBLE_IMAGE_MIMES

    private fun supportedMimes(convertUnsupported: Boolean): List<String> =
        if (convertUnsupported) {
            (IN_PLACE_IMAGE_MIMES + CONVERTIBLE_IMAGE_MIMES).toList()
        } else {
            // Offering a file the engine would refuse only produces a dead
            // entry in someone's upload dialog.
            IN_PLACE_IMAGE_MIMES.toList()
        }

    private inline fun query(
        context: Context,
        selection: String,
        args: Array<String>,
        sortOrder: String?,
        body: (android.database.Cursor) -> Unit,
    ) {
        try {
            context.contentResolver.query(
                filesUri(),
                arrayOf(
                    MediaStore.Files.FileColumns._ID,
                    MediaStore.Files.FileColumns.MIME_TYPE,
                    MediaStore.Files.FileColumns.DISPLAY_NAME,
                ),
                selection,
                args,
                sortOrder,
            )?.use(body)
        } catch (e: Exception) {
            // A missing or unreadable volume just means an empty listing.
        }
    }
}
