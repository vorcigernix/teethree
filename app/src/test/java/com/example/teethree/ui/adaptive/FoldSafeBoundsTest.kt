package com.example.teethree.ui.adaptive

import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldSafeBoundsTest {
    @Test
    fun focusedFormsChooseOneSideOfASeparatingBookHinge() {
        val viewport = Rect(20f, 40f, 1220f, 1000f)
        val hinge = Rect(560f, 0f, 584f, 1100f)
        val result = foldSafeBounds(viewport, emptyList(), listOf(hinge), null)
        assertEquals(Rect(584f, 40f, 1220f, 1000f), result)
        assertTrue(result.left >= hinge.right)
    }

    @Test
    fun tabletopContentAvoidsBothPhysicalFoldAndSoftwareKeyboard() {
        val viewport = Rect(0f, 40f, 1000f, 1240f)
        val hinge = Rect(0f, 630f, 1000f, 650f)
        val result = foldSafeBounds(viewport, listOf(hinge), emptyList(), 900f)
        assertEquals(Rect(0f, 40f, 1000f, 630f), result)
        assertTrue(result.bottom <= hinge.top)
        assertTrue(result.bottom <= 900f)
    }

    @Test
    fun viewportOffsetsAndFlatWindowsKeepCorrectGeometry() {
        val viewport = Rect(100f, 300f, 1100f, 1100f)
        val hinge = Rect(0f, 600f, 1200f, 620f)
        assertEquals(
            Rect(100f, 620f, 1100f, 1100f),
            foldSafeBounds(viewport, listOf(hinge), emptyList(), null),
        )
        assertEquals(viewport, foldSafeBounds(viewport, emptyList(), emptyList(), 700f))
        val occluded = foldSafeBounds(viewport, listOf(hinge), emptyList(), 200f)
        assertEquals(0f, occluded.height)
    }
}
