package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test

class StrokeIndexTest {
    @Test fun queriesMatchFullScanAfterRandomReplacementsAndRemovals() {
        val random = kotlin.random.Random(814)
        val index = StrokeIndex<Int>()
        val expected = mutableMapOf<Long, StrokeIndex.Bounds>()
        repeat(1000) { step ->
            val id = random.nextLong(-200, 200)
            if (step % 5 == 0) { index.remove(id); expected.remove(id) }
            else {
                val x = random.nextInt(-2000, 2000).toFloat()
                val y = random.nextInt(-100000, 100000).toFloat()
                val bounds = StrokeIndex.Bounds(x, y, x + 100f, y + if (step % 7 == 0) 100000f else 800f)
                index.add(id, id.toInt(), bounds); expected[id] = bounds
            }
            val top = random.nextInt(-100000, 100000).toFloat()
            val rect = StrokeIndex.Bounds(-500f, top, 500f, top + if (step % 11 == 0) 1000000f else 3000f)
            val matches = expected.filterValues { b ->
                b.left <= rect.right && b.right >= rect.left && b.top <= rect.bottom && b.bottom >= rect.top
            }.keys.sorted().map { it to it.toInt() }
            assertEquals(matches, index.query(rect))
        }
    }
    @Test fun scrollExtentTracksTheLastStrokeAcrossRemovalReplacementAndEmptyNotes() {
        val index = StrokeIndex<String>()
        assertEquals(0f, index.bottom, 0f)
        index.add(1, "a", StrokeIndex.Bounds(0f, 100f, 100f, 500f))
        index.add(2, "b", StrokeIndex.Bounds(0f, 200f, 100f, 500f))
        index.add(3, "c", StrokeIndex.Bounds(0f, 500f, 100f, 900f))
        assertEquals(900f, index.bottom, 0f)
        index.remove(3); assertEquals(500f, index.bottom, 0f)
        index.remove(1); assertEquals(500f, index.bottom, 0f)
        index.add(2, "moved", StrokeIndex.Bounds(0f, 0f, 100f, 100f))
        assertEquals(100f, index.bottom, 0f)
        index.remove(2); assertEquals(0f, index.bottom, 0f)
    }
    @Test fun selectsVisibleStrokesInZOrderWithoutDuplicates() {
        val index = StrokeIndex<String>()
        repeat(10000) { index.add(it.toLong() + 1, "$it", StrokeIndex.Bounds(0f, it * 100f, 100f, it * 100f + 80f)) }
        val matches = index.query(StrokeIndex.Bounds(0f, 1000f, 200f, 1300f))
        assertEquals(listOf("10", "11", "12", "13"), matches.map { it.second })
        index.remove(12)
        assertEquals(listOf("10", "12", "13"), index.query(StrokeIndex.Bounds(0f, 1000f, 200f, 1300f)).map { it.second })
    }
    @Test fun veryLongStrokesDoNotRequireMillionsOfBuckets() {
        val index = StrokeIndex<String>()
        index.add(1, "long", StrokeIndex.Bounds(1f, 0f, 20f, 10000000f))
        assertEquals(listOf("long"), index.query(StrokeIndex.Bounds(0f, 9000000f, 100f, 9000100f)).map { it.second })
        index.remove(1)
        assertTrue(index.query(StrokeIndex.Bounds(0f, 0f, 100f, 100f)).isEmpty())
    }
}
