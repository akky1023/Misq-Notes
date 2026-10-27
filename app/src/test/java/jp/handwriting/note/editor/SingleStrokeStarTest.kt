package jp.handwriting.note.editor

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class SingleStrokeStarTest {
    private fun supplied(): List<List<SingleStrokeStar.Point>> {
        val json = javaClass.getResourceAsStream("/single-stroke-stars.json")!!.bufferedReader().use { it.readText() }
        val samples = JSONArray(json)
        return List(samples.length()) { i ->
            val points = samples.getJSONArray(i)
            List(points.length()) { j -> points.getJSONArray(j).let {
                SingleStrokeStar.Point(it.getDouble(0).toFloat(), it.getDouble(1).toFloat())
            } }
        }
    }

    @Test fun recognizesAllTwentySuppliedSingleStrokeStars() {
        val samples = supplied()
        assertEquals(20, samples.size)
        samples.forEachIndexed { i, points -> assertTrue("Supplied star ${i + 1}", SingleStrokeStar.matches(points)) }
    }

    @Test fun directionPositionSizeAndModestRotationDoNotChangeRecognition() {
        supplied().forEachIndexed { i, points ->
            for (scale in listOf(0.5f, 3f)) for (angle in listOf(-0.3f, 0.3f)) {
                val moved = points.reversed().map {
                    SingleStrokeStar.Point(180f + scale * (it.x * cos(angle) - it.y * sin(angle)),
                        8000f + scale * (it.x * sin(angle) + it.y * cos(angle)))
                }
                assertTrue("Transformed star ${i + 1}, $scale, $angle", SingleStrokeStar.matches(moved))
            }
        }
    }

    @Test fun twoOrMoreStrokesAreNeverCombinedIntoAStar() {
        supplied().forEach { points ->
            for (parts in 2..5) points.chunked(ceil(points.size.toDouble() / parts).toInt()).forEach {
                assertFalse("A fragment of a multi-stroke star", SingleStrokeStar.matches(it))
            }
        }
    }

    private fun path(vararg vertices: Pair<Float, Float>): List<SingleStrokeStar.Point> =
        vertices.toList().zipWithNext().flatMap { (a, b) -> (0 until 20).map { i ->
            SingleStrokeStar.Point(a.first + (b.first - a.first) * i / 20,
                a.second + (b.second - a.second) * i / 20)
        } } + SingleStrokeStar.Point(vertices.last().first, vertices.last().second)

    @Test fun ordinaryShapesLettersAndScribblesAreNotStars() {
        val ordinary = listOf(
            path(0f to 0f, 100f to 100f),
            path(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f, 0f to 0f),
            path(0f to 100f, 50f to 0f, 100f to 100f, 0f to 100f),
            path(0f to 100f, 0f to 0f, 50f to 100f, 100f to 0f, 100f to 100f),
            path(0f to 100f, 100f to 0f, 0f to 0f, 100f to 100f, 0f to 100f),
            path(0f to 0f, 100f to 20f, 0f to 40f, 100f to 60f, 0f to 80f, 100f to 100f),
            (0..128).map { val t = it * PI / 64; SingleStrokeStar.Point((100 * cos(t)).toFloat(), (100 * sin(t)).toFloat()) },
            (0..128).map { val t = it * PI / 64; SingleStrokeStar.Point((100 * sin(t)).toFloat(), (100 * sin(2 * t)).toFloat()) },
        )
        ordinary.forEachIndexed { i, points -> assertFalse("Ordinary shape $i", SingleStrokeStar.matches(points)) }
        assertFalse(SingleStrokeStar.matches(emptyList()))
        assertFalse(SingleStrokeStar.matches(List(10) { SingleStrokeStar.Point(1f, 1f) }))
        assertFalse(SingleStrokeStar.matches(List(10) { SingleStrokeStar.Point(Float.NaN, 1f) }))
    }
}
