package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.brush.*
import androidx.ink.strokes.*
import jp.handwriting.note.storage.NoteRepository
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalInkCustomBrushApi::class)
@Suppress("RestrictedApi")
class StrokeEndpointSmoothingTest {
    private val empty = ImmutableStrokeInputBatch.EMPTY
    private fun brush(curve: PressureCurve = PressureCurve()) =
        Brush.createWithColorIntArgb(InkBrushes.pressurePen(curve), Color.BLACK, 2f, 0.001f)
    private fun input(i: Int, pressure: Float, timeScale: Long = 8) = MutableStrokeInputBatch().apply {
        add(InputToolType.STYLUS, i * 0.25f, 0f, i * timeScale, 0.0125f, pressure, 0.25f, 0.75f)
    }
    private fun live(brush: Brush, count: Int = 129, timeScale: Long = 8, pressure: (Int) -> Float): InProgressStroke =
        InProgressStroke().apply {
            start(brush, 12345)
            repeat(count) { i ->
                enqueueInputs(input(i, pressure(i), timeScale), empty)
                updateShape(i * timeScale)
            }
        }
    private val matrix get() = Matrix().apply { setScale(16f, 16f) }
    private fun bitmap(draw: (Canvas) -> Unit): Bitmap = Bitmap.createBitmap(576, 96, Bitmap.Config.ARGB_8888).also {
        draw(Canvas(it).apply { translate(32f, 48f); scale(16f, 16f) })
    }
    private fun render(stroke: InProgressStroke) = bitmap { InkStrokeRenderer.create().draw(it, stroke, matrix) }
    private fun render(stroke: Stroke) = bitmap { InkStrokeRenderer.create().draw(it, stroke, matrix) }
    private fun coverage(bitmap: Bitmap, x: Int) = (0 until bitmap.height).count {
        Color.alpha(bitmap.getPixel(x, it)) > 128
    }
    private fun assertPixelsEqual(a: Bitmap, b: Bitmap, left: Int = 0, right: Int = a.width) {
        for (x in left until right) for (y in 0 until a.height) {
            assertEquals("pixel $x,$y", a.getPixel(x, y), b.getPixel(x, y))
        }
    }

    @Test fun suddenThinningIsAlreadyFilledDuringWritingAndDoesNotAnimateAfterLift() {
        val live = live(brush(), count = 49) { if (it <= 16) 1f else 0f }
        val during = render(live)
        // x=5 is one document unit after the pressure drop; full diameter is 32 pixels.
        assertTrue("The thin neck must be filled before lifting", coverage(during, 112) > 16)
        assertEquals("The thick part must retain full width", 32, coverage(during, 64))
        assertFalse("Distance damping must not wait on a timer", live.changesWithTime())
        live.finishInput()
        live.updateShape(49 * 8L)
        assertFalse(live.isUpdateNeeded())
        val completed = render(live)
        try {
            // Ink may finalize its last modeled inputs; earlier geometry must not be corrected again.
            assertPixelsEqual(during, completed, right = 160)
            live.updateShape(5000)
            val later = render(live)
            try { assertPixelsEqual(completed, later) } finally { later.recycle() }
        } finally { during.recycle(); completed.recycle() }
    }

    @Test fun thickPartsStayThickAndSuddenThinningIsFilledAtDifferentWritingSpeeds() {
        val slow = live(brush(), timeScale = 40) { if (it < 16 || it >= 96) 1f else 0f }
        val fast = live(brush(), timeScale = 4) { if (it < 16 || it >= 96) 1f else 0f }
        val a = render(slow); val b = render(fast)
        try {
            // Ink's velocity-aware input model can shift transitions. Check the visible
            // result at both speeds rather than requiring identical modeled input positions.
            for (image in listOf(a, b)) {
                assertTrue("Thin neck must be filled", coverage(image, 112) > 16)
                assertEquals(32, coverage(image, 64))
                assertEquals("Rising pressure must not be damped", 32, coverage(image, 448))
                assertTrue("Sustained light pressure must still become thin", coverage(image, 320) <= 8)
            }
        } finally { a.recycle(); b.recycle() }
    }

    @Test fun handoffReloadAndLocalCoordinatesKeepTheSamePressuresAndPixels() {
        for (curve in listOf(PressureCurve(), PressureCurve(exponentPercent = 300),
            PressureCurve(bezier = BezierControls(20, 5, 80, 95)), PressureCurve.editableDefault(),
            PressureCurve(minimumPercent = 100))) {
            val live = live(brush(curve)) { if (it < 16 || it >= 96) 1f else 0.1f }
            live.finishInput(); live.updateShape()
            val original = EditorStroke(live.toImmutable())
            val saved = NoteRepository.encodeStroke(original)
            val local = StrokeLocalCoordinates.create(original.ink)
            for (i in 0 until original.ink.inputs.size) {
                assertEquals("Rendering must not rewrite pressure", original.ink.inputs[i], local.stroke.inputs[i])
            }
            assertArrayEquals(saved, NoteRepository.encodeStroke(original))
            val loaded = NoteRepository.decodeStroke("test", saved)
            val a = render(original.ink); val b = render(loaded.ink); val wet = render(live)
            try {
                assertPixelsEqual(a, b)
                // Immutable Ink meshes quantize positions, unlike the live mesh. Allow two pixels
                // at 16x for that conversion; a post-completion pressure correction is much larger.
                val maxDifference = (32 until 544).maxOf { kotlin.math.abs(coverage(wet, it) - coverage(a, it)) }
                assertTrue("Handoff width difference: $maxDifference pixels", maxDifference <= 2)
            } finally { a.recycle(); b.recycle(); wet.recycle() }
        }
    }

    @Test fun stationaryPressureLossAtLiftDoesNotCreateAThinNeck() {
        val live = live(brush(), count = 33) { 1f }
        live.enqueueInputs(MutableStrokeInputBatch().add(InputToolType.STYLUS, 8f, 0f, 264L,
            0.0125f, 0f, 0.25f, 0.75f), empty)
        live.finishInput(); live.updateShape(264L)
        val after = render(live)
        try {
            // A final input also finalizes Ink's modeled cap. The body and the neck at the
            // release point must keep their full diameter despite the zero-pressure sample.
            for (x in 64..160) assertTrue("Release width at $x", coverage(after, x) >= 30)
        } finally { after.recycle() }
    }

    @Test fun fixedWidthAndMissingPressureRemainFullWidth() {
        val fixed = render(live(brush(PressureCurve(minimumPercent = 100))) { if (it % 2 == 0) 0f else 1f })
        val missing = render(live(brush()) { StrokeInput.NO_PRESSURE })
        val full = render(live(brush()) { 1f })
        try { assertPixelsEqual(full, fixed); assertPixelsEqual(full, missing) }
        finally { fixed.recycle(); missing.recycle(); full.recycle() }
    }

    @Test fun predictionsDoNotContaminateRealGeometry() {
        val brush = brush()
        val predicted = live(brush, count = 32) { 1f }
        predicted.enqueueInputs(empty, input(32, 0f))
        predicted.updateShape(256)
        predicted.enqueueInputs(input(32, 1f), empty)
        predicted.updateShape(256)
        val reference = live(brush, count = 33) { 1f }
        val a = render(predicted); val b = render(reference)
        try { assertPixelsEqual(a, b) } finally { a.recycle(); b.recycle() }
    }
}
