package jp.handwriting.note.editor

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class HoldLineTest {
    private fun point(radius: Float, angle: Float, origin: WorldPoint = WorldPoint(0f, 0f)): WorldPoint {
        val r = Math.toRadians(angle.toDouble())
        return WorldPoint(origin.x + radius * cos(r).toFloat(), origin.y - radius * sin(r).toFloat())
    }

    @Test fun holdAllowsJitterButMovementRestartsTimeoutAndDotsStayFreehand() {
        val line = HoldLine(WorldPoint(0f, 0f), 1f, true, 0L)
        assertFalse(line.activate(600L))
        line.sample(WorldPoint(60f, 0f), 700L)
        line.sample(WorldPoint(62f, 1f), 1000L)
        assertFalse(line.activate(1299L))
        assertTrue(line.activate(1300L))
        line.sample(WorldPoint(-50f, 20f), 1400L)
        assertTrue(line.active)
        assertEquals(WorldPoint(0f, 0f), line.start)
    }

    @Test fun mildWobbleQualifiesAtDifferentAnglesZoomsAndLengths() {
        for (angle in listOf(0f, 45f, 90f, -135f)) for (scale in listOf(0.5f, 2f)) for (length in listOf(20f, 150f, 500f)) {
            val origin = WorldPoint(25000f, 40000f)
            val line = HoldLine(origin, scale, true, 0L)
            val direction = point(1f, angle)
            val normal = point(1f, angle + 90f)
            for (i in 1..20) {
                val along = length * i / 20f / scale
                val jitter = if (i == 20) 0f else (if (i % 2 == 0) 0.8f else -0.8f) / scale
                line.sample(WorldPoint(origin.x + direction.x * along + normal.x * jitter,
                    origin.y + direction.y * along + normal.y * jitter), i * 10L)
            }
            assertTrue("angle=$angle scale=$scale length=$length", line.activate(800L))
            // Entry requirements must not cancel an already activated line.
            line.sample(WorldPoint(origin.x - 50f, origin.y + 70f), 900L)
            assertTrue(line.active)
        }
    }

    @Test fun bendsCurvesLoopsAndOvershootsStayFreehandEvenWhenHeldAgain() {
        val paths = listOf(
            listOf(WorldPoint(0f, 75f), WorldPoint(100f, 75f)),
            (1..20).map { i -> WorldPoint(i * 10f, 30f * sin(i / 20f * PI).toFloat()) },
            listOf(WorldPoint(50f, 50f), WorldPoint(100f, 0f), WorldPoint(50f, -50f), WorldPoint(100f, 0f)),
            listOf(WorldPoint(150f, 0f), WorldPoint(100f, 0f)),
            listOf(WorldPoint(250f, 50f), WorldPoint(500f, 0f)),
        )
        for (snap in listOf(true, false)) for (path in paths) {
            val line = HoldLine(WorldPoint(0f, 0f), 1f, snap, 0L)
            path.forEachIndexed { i, p -> line.sample(p, (i + 1) * 10L) }
            assertFalse(line.activate(1000L))
            line.sample(path.last(), 1100L)
            assertFalse(line.activate(2000L))
        }
    }

    @Test fun shortLineSnapsBy45AndLongLineBy15() {
        for ((radius, expected) in listOf(60f to 45f, 150f to 30f)) {
            val line = HoldLine(WorldPoint(10f, 20f), 1f, true, 0L)
            line.sample(point(radius, if (radius < 120f) 43f else 32f, line.start), 10L)
            assertTrue(line.activate(610L))
            assertEquals(expected, line.angle, 0.001f)
            assertEquals(radius, hypot(line.end.x - line.start.x, line.end.y - line.start.y), 0.001f)
        }
    }

    @Test fun longThenShortKeeps15DegreeStepsEvenWhenAngleChanges() {
        val line = HoldLine(WorldPoint(0f, 0f), 1f, true, 0L)
        line.sample(point(150f, 32f), 10L)
        assertTrue(line.activate(610L))
        for ((angle, expected) in listOf(31f to 30f, 29f to 30f, 16f to 15f, 59f to 60f)) {
            line.sample(point(20f, angle), 700L)
            assertEquals(expected, line.angle, 0.001f)
        }
        line.sample(point(20f, 0f), 800L)
        assertEquals(0f, line.angle, 0.001f)
        line.sample(point(20f, 16f), 810L)
        assertEquals(16f, line.angle, 0.001f)
        line.sample(point(150f, 16f), 820L)
        line.sample(point(20f, 16f), 830L)
        assertEquals(15f, line.angle, 0.001f)
        val nextContact = HoldLine(WorldPoint(0f, 0f), 1f, true, 0L)
        nextContact.sample(point(20f, 31f), 10L)
        nextContact.activate(610L)
        assertEquals(31f, nextContact.angle, 0.001f)
    }

    @Test fun retainedAngleWrapsAt180AndReturnsToCoarseAt30Degrees() {
        val line = HoldLine(WorldPoint(0f, 0f), 1f, true, 0L)
        line.sample(point(150f, 179f), 10L); line.activate(610L)
        line.sample(point(20f, -165f), 700L)
        assertEquals(-165f, line.angle, 0.001f)
        line.sample(WorldPoint(-10f * sqrt(3f), 10f), 710L)
        assertEquals(-150f, line.angle, 0.001f)
        line.sample(point(20f, -149f), 720L)
        assertEquals(-149f, line.angle, 0.001f)
    }

    @Test fun snappingOnlyAttractsWithinTwoPointFiveDegrees() {
        for ((radius, target) in listOf(60f to 45f, 150f to 30f, 150f to -30f, 60f to 180f, 60f to -180f)) {
            for (offset in listOf(-2.51f, -2.5f, -2f, 0f, 2f, 2.5f, 2.51f, 6f)) {
                val line = HoldLine(WorldPoint(0f, 0f), 1f, true, 0L)
                val raw = point(radius, target + offset)
                line.sample(raw, 10L); line.activate(610L)
                val expected = if (abs(offset) <= 2.5f) target else target + offset
                val difference = abs(((line.angle - expected + 540f) % 360f) - 180f)
                assertEquals("target=$target offset=$offset", 0f, difference, 0.001f)
                if (abs(offset) > 2.5f) assertEquals(raw, line.end)
                assertTrue(line.angle in -180f..180f)
            }
        }
    }

    @Test fun polarAngleUsesUpwardPositiveAndPrincipalValuesWithoutSnapping() {
        for (angle in listOf(0f, 90f, -90f, 179f, -179f, 32f)) {
            val line = HoldLine(WorldPoint(25000f, 40000f), 1f, false, 0L)
            val raw = point(150f, angle, line.start)
            line.sample(raw, 10L); line.activate(610L)
            assertEquals(angle, line.angle, 0.01f)
            assertEquals(raw, line.end)
            assertTrue(line.angle in -180f..180f)
        }
    }

    @Test fun thresholdsUseScreenDpAndNonFiniteSamplesAreIgnored() {
        val line = HoldLine(WorldPoint(0f, 0f), 2f, true, 0L)
        line.sample(point(61f, 32f), 10L); line.activate(610L)
        assertEquals(30f, line.angle, 0.001f)
        val end = line.end
        line.sample(WorldPoint(Float.NaN, 0f), 700L)
        assertEquals(end, line.end)
    }
}
