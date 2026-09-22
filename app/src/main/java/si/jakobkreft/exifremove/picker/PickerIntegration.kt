// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.DocumentsContract
import si.jakobkreft.exifremove.data.Template

/**
 * The app's entry in the system file picker. Any app or website asking for a
 * file goes through that picker, so appearing there is the one way to hand a
 * cleaned copy straight into an upload without a detour through the share
 * sheet and back.
 *
 * Nothing is cleaned when the picker lists a file; the bytes are produced on
 * [CleanDocumentsProvider.openDocument], so the picking app never has a
 * handle on the original.
 */
object PickerIntegration {

    const val AUTHORITY = "si.jakobkreft.exifremove.documents"

    /**
     * A document id is `<templateId>` for a root, `<templateId>/d/<path>/`
     * for a folder below it and `<templateId>/f/<mediaId>` for a file. The
     * kind is spelled out rather than inferred, so a folder called `42`
     * cannot be read as a media id.
     */
    fun rootDocumentId(templateId: String): String = templateId

    fun folderDocumentId(templateId: String, path: String): String =
        if (path.isEmpty()) templateId else "$templateId/d/$path"

    fun documentId(templateId: String, mediaId: Long): String = "$templateId/f/$mediaId"

    fun templateIdOf(documentId: String): String = documentId.substringBefore('/')

    /** Null unless this id names a file. */
    fun mediaIdOf(documentId: String): Long? {
        val rest = documentId.substringAfter('/', "")
        if (!rest.startsWith("f/")) return null
        return rest.removePrefix("f/").toLongOrNull()
    }

    /**
     * The folder an id stands for, `""` being the storage root; null when the
     * id names a file instead.
     */
    fun folderPathOf(documentId: String): String? {
        val rest = documentId.substringAfter('/', "")
        return when {
            rest.isEmpty() -> ""
            rest.startsWith("d/") -> rest.removePrefix("d/")
            else -> null
        }
    }

    fun isRoot(documentId: String): Boolean = !documentId.contains('/')

    fun templateFor(documentId: String, templates: List<Template>): Template? {
        val id = templateIdOf(documentId)
        return templates.firstOrNull { it.id == id }
    }

    /**
     * Turning the integration off disables the provider component outright,
     * which is what removes the entry from the picker; a preference alone
     * would leave an empty source sitting in the list.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val component = ComponentName(context, CleanDocumentsProvider::class.java)
        val target = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        if (context.packageManager.getComponentEnabledSetting(component) != target) {
            context.packageManager.setComponentEnabledSetting(
                component, target, PackageManager.DONT_KILL_APP
            )
        }
        notifyRootsChanged(context)
    }

    /** Re-reads the root list, so template edits show up in the picker at once. */
    fun notifyRootsChanged(context: Context) {
        try {
            context.contentResolver.notifyChange(
                DocumentsContract.buildRootsUri(AUTHORITY), null
            )
        } catch (e: Exception) {
            // The picker simply refreshes on its own next time.
        }
    }
}
