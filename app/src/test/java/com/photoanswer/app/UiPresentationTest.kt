package com.photoanswer.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar

class UiPresentationTest {
    private fun contrast(a: Color, b: Color): Float =
        (maxOf(a.luminance(), b.luminance()) + .05f) / (minOf(a.luminance(), b.luminance()) + .05f)

    @Test fun `fallback themes meet AA text contrast for reading and status surfaces`() {
        for (theme in listOf(LightColors, DarkColors)) {
            for ((foreground, background) in listOf(theme.onSurface to theme.surface,
                theme.onBackground to theme.background, theme.onSurfaceVariant to theme.surface,
                theme.onSurfaceVariant to theme.surfaceVariant, theme.onPrimary to theme.primary,
                theme.onPrimaryContainer to theme.primaryContainer, theme.onErrorContainer to theme.errorContainer,
                theme.onTertiaryContainer to theme.tertiaryContainer)) {
                assertTrue("Contrast must be at least 4.5:1, got ${contrast(foreground, background)}", contrast(foreground, background) >= 4.5f)
            }
        }
    }
    private fun date(year: Int, month: Int, day: Int) = Calendar.getInstance().apply {
        clear(); set(year, month - 1, day, 12, 0)
    }.timeInMillis
    @Test fun `history preserves yesterday across year boundaries`() {
        val now = date(2026, 1, 1)
        assertEquals("昨天", historyBucket(date(2025, 12, 31), now))
        assertTrue(historyTimeLabel(date(2025, 12, 31), now).startsWith("昨天"))
    }
    @Test fun `history does not group all current year entries under this month`() {
        val now = date(2026, 10, 21)
        assertEquals("本月", historyBucket(date(2026, 10, 4), now))
        assertEquals("9月", historyBucket(date(2026, 9, 4), now))
    }
}
