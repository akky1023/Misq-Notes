package jp.handwriting.note.editor

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class StarPreviewRowsTest {
    private fun star(top: Float, bottom: Float, x: Float = 0f) = StrokeIndex.Bounds(x, top, x + 40f, bottom)

    @Test fun nearbyStarsAcrossTheWidthBecomeOneRow() {
        val rows = StarPreviewRows.group(listOf(star(500f, 550f), star(506f, 560f, 400f), star(493f, 543f, 800f)))
        assertEquals(1, rows.size)
        assertTrue(rows.single().top < 493f)
        assertTrue(rows.single().bottom > 560f)
    }

    @Test fun differentRowsStaySeparateAndAppearInDocumentOrder() {
        val rows = StarPreviewRows.group(listOf(star(4500f, 4550f), star(100f, 150f), star(110f, 160f)))
        assertEquals(2, rows.size)
        assertTrue(rows[0].top < 100f && rows[0].bottom > 160f)
        assertTrue(rows[1].top < 4500f && rows[1].bottom > 4550f)
        assertTrue(rows[0].bottom < rows[1].top)
    }

    @Test fun closeRowsDoNotRepeatTheSameGapAndTopIsClamped() {
        val rows = StarPreviewRows.group(listOf(star(0f, 30f), star(37f, 70f), star(95f, 125f)))
        assertEquals(2, rows.size)
        assertEquals(0f, rows.first().top, 0f)
        assertTrue(rows.first().bottom <= rows.last().top)
        assertTrue(StarPreviewRows.group(emptyList()).isEmpty())
    }

    @Test fun suppliedTwentyStarsFormFourRows() {
        val source = javaClass.getResourceAsStream("/single-stroke-stars.json")!!.bufferedReader().use { it.readText() }
        // The detector fixture is translated to each stroke's origin; restore original row positions here.
        val samples = JSONArray(source)
        val positions = listOf(131.938f, 127.344f, 128.219f, 137.031f, 142f,
            257.368f, 262.864f, 260.897f, 247.968f, 247.374f,
            368.917f, 379.910f, 380.503f, 382.127f, 377.786f,
            507.167f, 507.573f, 504.159f, 514.586f, 512.400f)
        val stars = List(samples.length()) { i ->
            val points = samples.getJSONArray(i)
            val height = (0 until points.length()).maxOf { points.getJSONArray(it).getDouble(1) }.toFloat()
            star(positions[i], positions[i] + height)
        }
        assertEquals(4, StarPreviewRows.group(stars).size)
    }
}
