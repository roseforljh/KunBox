package com.kunk.singbox.repository

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardMascotStoreTest {
    @Test
    fun imageSamplingKeepsSmallPhotosAndBoundsLargeDecodes() {
        assertEquals(1, mascotSampleSize(768))
        assertEquals(2, mascotSampleSize(769))
        assertEquals(8, mascotSampleSize(4032))

        val extreme = Int.MAX_VALUE.toLong()
        val sample = mascotSampleSize(Int.MAX_VALUE)
        assertTrue((extreme + sample - 1) / sample <= 768)
    }

    @Test
    fun photoCropStaysInsideTheImageAndZoomsAroundTheGesture() {
        val image = IntSize(800, 600)
        val initial = MascotCrop.centered(image)
        assertEquals(MascotCrop(100f, 0f, 600f), initial)

        val zoomed = initial.transform(image, Offset(0.25f, 0.75f), Offset.Zero, 2f)
        assertEquals(MascotCrop(175f, 225f, 300f), zoomed)
        assertEquals(MascotCrop(500f, 0f, 300f), zoomed.transform(image, Offset.Zero, Offset(-2f, 2f), 1f))
        assertEquals(150f, zoomed.transform(image, Offset.Zero, Offset.Zero, 20f).side, 0f)
    }

    @Test
    fun onlyVisibleTransparencyAtTheBorderKeepsTheOriginalShape() {
        val border = IntArray(100) { 0xff303030.toInt() }
        assertFalse(hasTransparentMascotBorder(border))
        border[0] = 0
        assertFalse(hasTransparentMascotBorder(border))
        border.fill(0, fromIndex = 0, toIndex = 20)
        assertTrue(hasTransparentMascotBorder(border))
    }
}
