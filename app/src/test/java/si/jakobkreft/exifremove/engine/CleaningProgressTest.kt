// SPDX-FileCopyrightText: 2026 Jakob Kreft
// SPDX-License-Identifier: GPL-3.0-or-later

package si.jakobkreft.exifremove.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CleaningProgressTest {

    @Test
    fun `progress is weighted by bytes, not by file count`() {
        // Three files where the last one is most of the work: finishing the
        // two small ones is nowhere near two thirds done.
        val progress = CleaningProgress(
            completed = 2, total = 3, currentName = "big.mp4",
            bytesDone = 2_000_000, bytesTotal = 20_000_000,
        )
        assertEquals(0.1f, progress.fraction!!, 0.001f)
    }

    @Test
    fun `falls back to file count when sizes are unknown`() {
        val progress = CleaningProgress(
            completed = 1, total = 4, currentName = "b.jpg",
            bytesDone = 0, bytesTotal = 0,
        )
        assertEquals(0.25f, progress.fraction!!, 0.001f)
    }

    @Test
    fun `no fraction at all when there is nothing to measure`() {
        assertNull(
            CleaningProgress(0, 0, null, 0, 0).fraction
        )
    }

    @Test
    fun `fraction never escapes zero to one`() {
        val over = CleaningProgress(1, 1, null, bytesDone = 30, bytesTotal = 10)
        assertEquals(1f, over.fraction!!, 0.001f)
    }
}
