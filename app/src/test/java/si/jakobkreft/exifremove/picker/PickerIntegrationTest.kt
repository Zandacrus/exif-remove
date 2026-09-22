// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.picker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import si.jakobkreft.exifremove.data.Template

class PickerIntegrationTest {

    @Test
    fun `document id round trips`() {
        val id = PickerIntegration.documentId(Template.ID_SCRAMBLE, 42L)
        assertEquals(Template.ID_SCRAMBLE, PickerIntegration.templateIdOf(id))
        assertEquals(42L, PickerIntegration.mediaIdOf(id))
        assertFalse(PickerIntegration.isRoot(id))
    }

    @Test
    fun `built-in template ids survive the separator`() {
        // Every built-in id contains hyphens; only the slash may split.
        Template.builtIns().forEach { template ->
            val id = PickerIntegration.documentId(template.id, 7L)
            assertEquals(template.id, PickerIntegration.templateIdOf(id))
            assertEquals(7L, PickerIntegration.mediaIdOf(id))
        }
    }

    @Test
    fun `root document id carries no media`() {
        val root = PickerIntegration.rootDocumentId(Template.ID_REMOVE_EVERYTHING)
        assertTrue(PickerIntegration.isRoot(root))
        assertNull(PickerIntegration.mediaIdOf(root))
    }

    @Test
    fun `malformed ids do not parse as media`() {
        assertNull(PickerIntegration.mediaIdOf("template/not-a-number"))
        assertNull(PickerIntegration.mediaIdOf("template/"))
        assertNull(PickerIntegration.mediaIdOf(""))
    }

    @Test
    fun `template lookup ignores unknown roots`() {
        val templates = Template.builtIns()
        assertNotNull(
            PickerIntegration.templateFor(
                PickerIntegration.documentId(Template.ID_SCRAMBLE, 1L), templates
            )
        )
        assertNull(PickerIntegration.templateFor("deleted-template/1", templates))
    }
}
