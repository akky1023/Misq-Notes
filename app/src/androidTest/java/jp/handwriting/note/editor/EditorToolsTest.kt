package jp.handwriting.note.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import jp.handwriting.note.storage.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorToolsTest {
    @Test fun canvasEdgesUseOrdinaryFingerPanningWithoutDrawing() = runBlocking {
        withModel { model ->
            withContext(Dispatchers.Main) {
                val target = InstrumentationRegistry.getInstrumentation().targetContext
                val display = target.getSystemService(android.hardware.display.DisplayManager::class.java)
                    .getDisplay(android.view.Display.DEFAULT_DISPLAY)
                val context = target.createDisplayContext(display)
                val density = context.resources.displayMetrics.density
                val canvas = InkCanvasView(context, model)
                assertFalse(canvas.isVerticalScrollBarEnabled)
                assertFalse(canvas.isHorizontalScrollBarEnabled)
                val width = (600 * density).toInt()
                val height = (800 * density).toInt()
                canvas.layout(0, 0, width, height)
                model.viewport.restore(ViewPosition(2f, 100f, 500f))
                model.edits.restore(listOf(OrderedEdits.Entry(1L, stroke(100f, 10000f))))
                val initial = model.viewport.position
                val count = model.edits.snapshot().size
                fun touch(action: Int, x: Float, y: Float) {
                    val properties = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
                    val coordinates = android.view.MotionEvent.PointerCoords().apply {
                        this.x = x
                        this.y = y; pressure = 1f
                    }
                    val event = android.view.MotionEvent.obtain(0L, 10L, action, 1, arrayOf(properties), arrayOf(coordinates),
                        0, 0, 1f, 1f, 1, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
                    try { assertTrue(canvas.onTouchEvent(event)) } finally { event.recycle() }
                }
                for (left in listOf(false, true)) {
                    model.viewport.restore(initial)
                    val x = if (left) 8 * density else width - 8 * density
                    touch(android.view.MotionEvent.ACTION_DOWN, x, 100 * density)
                    touch(android.view.MotionEvent.ACTION_MOVE, x, 200 * density)
                    assertTrue(model.viewport.position.top < initial.top)
                    touch(android.view.MotionEvent.ACTION_CANCEL, x, 200 * density)
                    assertEquals(initial, model.viewport.position)
                    touch(android.view.MotionEvent.ACTION_DOWN, x, 100 * density)
                    touch(android.view.MotionEvent.ACTION_MOVE, x, 200 * density)
                    touch(android.view.MotionEvent.ACTION_UP, x, 200 * density)
                    assertTrue(model.viewport.position.top < initial.top)
                    assertEquals(initial.zoom, model.viewport.position.zoom, 0f)
                    assertEquals(initial.left, model.viewport.position.left, 0.01f)
                    assertEquals(count, model.edits.snapshot().size)
                }
                canvas.settleForPause()
            }
        }
    }

    @Test fun insertedSpaceMovesAllLowerStrokesAndSupportsUndoRedoAndReload() = runBlocking {
        withModel { model ->
            withContext(Dispatchers.Main) {
                model.viewport.size(1000f, 800f)
                model.edits.restore(listOf(
                    OrderedEdits.Entry(1L, stroke(100f, 100f)),
                    OrderedEdits.Entry(2L, stroke(300f, 300f)),
                    OrderedEdits.Entry(3L, stroke(200f, 900f, true)),
                    OrderedEdits.Entry(4L, stroke(400f, 245f)),
                ))
                assertTrue(model.beginSpaceInsertion(250f))
                model.previewSelectionMove(0f, 80f)
                model.commitSelectionMove(clearAfter = true)
            }
            idle(model)
            suspend fun positions() = withContext(Dispatchers.Main) {
                model.edits.snapshot().map { it.value.ink.inputs[0].y }
            }
            assertEquals(listOf(100f, 380f, 980f, 325f), positions())
            withContext(Dispatchers.Main) {
                assertEquals(listOf(1L, 2L, 3L, 4L), model.edits.snapshot().map { it.id })
                assertNull(model.selectionBounds)
                model.edits.undo()
            }
            assertEquals(listOf(100f, 300f, 900f, 245f), positions())
            withContext(Dispatchers.Main) { model.edits.redo() }
            assertEquals(listOf(100f, 380f, 980f, 325f), positions())
            withContext(Dispatchers.Main) {
                val revision = model.edits.revision
                assertFalse(model.beginSpaceInsertion(2000f))
                assertTrue(model.beginSpaceInsertion(250f))
                model.previewSelectionMove(0f, 100f)
                model.cancelSpaceInsertion()
                assertFalse(model.ui.value.busy)
                assertEquals(revision, model.edits.revision)
            }
            val id = model.workspace.value.current!!.id
            withContext(Dispatchers.Main) { model.closeNote() }
            withTimeout(15000) { model.workspace.first { it.current == null && !it.working } }
            withContext(Dispatchers.Main) { model.openNote(id) }
            withTimeout(15000) { model.workspace.first { it.current?.id == id && !it.working } }
            assertEquals(listOf(100f, 380f, 980f, 325f), positions())
        }
    }

    @Test fun selectedStrokesSurviveToolChangesUntilAnOutsideContact() = runBlocking {
        withModel { model ->
            withContext(Dispatchers.Main) {
                model.viewport.size(1000f, 800f)
                model.edits.restore(listOf(OrderedEdits.Entry(1L, stroke(100f, 100f))))
                model.chooseTool(EditorTool.LASSO)
                model.selectLasso(listOf(WorldPoint(80f, 80f), WorldPoint(180f, 80f), WorldPoint(180f, 180f), WorldPoint(80f, 180f)))
            }
            idle(model)
            withContext(Dispatchers.Main) {
                val revision = model.edits.revision
                for (tool in listOf(EditorTool.PEN, EditorTool.HIGHLIGHTER, EditorTool.ERASER)) {
                    model.chooseTool(tool)
                    assertEquals(tool, model.ui.value.tool)
                    assertEquals(1, model.ui.value.selectionCount)
                    assertTrue(model.beginCanvasContact(105f, 105f))
                    assertEquals(EditorTool.LASSO, model.effectiveTool())
                }
                assertFalse(model.beginCanvasContact(500f, 500f))
                assertNull(model.selectionBounds)
                assertEquals(EditorTool.ERASER, model.effectiveTool())
                assertEquals(revision, model.edits.revision)
            }
        }
    }

    @Test fun temporaryLassoSelectionOutlivesTheShortcutAndResumesThePreviousToolOutside() = runBlocking {
        withModel { model ->
            withContext(Dispatchers.Main) {
                model.viewport.size(1000f, 800f)
                model.edits.restore(listOf(OrderedEdits.Entry(1L, stroke(100f, 100f))))
                model.chooseTool(EditorTool.HIGHLIGHTER)
                model.buttonAction(PenButton.UPPER, PenButtonAction.TEMPORARY_LASSO)
                model.handlePenButton(18, "Xiaomi Focus Pen Mouse", 92, true, 0)
                model.penButton.beginLassoContact()
                model.selectLasso(listOf(WorldPoint(80f, 80f), WorldPoint(180f, 80f), WorldPoint(180f, 180f), WorldPoint(80f, 180f)))
                model.handlePenButton(18, "Xiaomi Focus Pen Mouse", 92, false, 0)
                model.penButton.endContact(); model.buttonChanged()
            }
            idle(model)
            withContext(Dispatchers.Main) {
                assertFalse(model.penButton.lassoHeld)
                assertEquals(EditorTool.HIGHLIGHTER, model.ui.value.tool)
                assertEquals(EditorTool.LASSO, model.effectiveTool())
                assertTrue(model.beginCanvasContact(105f, 105f))
                assertFalse(model.beginCanvasContact(500f, 500f))
                assertEquals(EditorTool.HIGHLIGHTER, model.effectiveTool())
            }
        }
    }

    private fun stroke(x: Float, y: Float, highlighter: Boolean = false): EditorStroke {
        val inputs = MutableStrokeInputBatch()
            .add(InputToolType.STYLUS, x, y, 0, pressure = 0.4f, tiltRadians = 0.2f, orientationRadians = 0.8f)
            .add(InputToolType.STYLUS, x + 10, y + 10, 20, pressure = 0.7f, tiltRadians = 0.3f, orientationRadians = 0.9f)
        return EditorStroke(Stroke(Brush.createWithColorIntArgb(
            if (highlighter) InkBrushes.highlighter else StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
            0x66123456, 20f, 0.1f), inputs))
    }
    @Test fun translatedHighlighterPreservesBrushAndInputMetadataThroughStorage() {
        val original = stroke(100f, 200f, highlighter = true)
        val moved = original.translated(50f, 80f)
        assertNotEquals(original.storageId, moved.storageId)
        val loaded = NoteRepository.decodeStroke(moved.storageId, NoteRepository.encodeStroke(moved))
        assertEquals(InkBrushes.highlighter, loaded.ink.brush.family)
        assertEquals(original.ink.brush.colorIntArgb, loaded.ink.brush.colorIntArgb)
        assertEquals(original.ink.brush.size, loaded.ink.brush.size, 0f)
        for (i in 0 until original.ink.inputs.size) {
            val before = original.ink.inputs[i]; val after = loaded.ink.inputs[i]
            assertEquals(before.x + 50f, after.x, 0.05f); assertEquals(before.y + 80f, after.y, 0.05f)
            assertEquals(before.elapsedTimeMillis, after.elapsedTimeMillis)
            assertEquals(before.pressure, after.pressure, 0.001f)
            assertEquals(before.tiltRadians, after.tiltRadians, 0.001f)
            assertEquals(before.orientationRadians, after.orientationRadians, 0.001f)
        }
    }
    @Test fun lassoMoveDeleteUndoAndReloadKeepStrokeOrderAndPositions() = runBlocking {
        withModel { model ->
            withContext(Dispatchers.Main) {
                model.viewport.size(1000f, 800f)
                model.edits.restore(listOf(
                    OrderedEdits.Entry(1L, stroke(100f, 100f)),
                    OrderedEdits.Entry(2L, stroke(300f, 300f)),
                    OrderedEdits.Entry(3L, stroke(130f, 150f, true)),
                ))
                model.chooseTool(EditorTool.LASSO)
                model.selectLasso(listOf(WorldPoint(80f, 80f), WorldPoint(180f, 80f), WorldPoint(180f, 180f), WorldPoint(80f, 180f)))
            }
            idle(model); assertEquals(2, model.ui.value.selectionCount)
            withContext(Dispatchers.Main) {
                val before = model.selectionOverlay.value!!
                model.previewSelectionMove(50f, 80f)
                val moved = model.selectionOverlay.value!!
                assertEquals(before.left + 50f, moved.left, 0.01f)
                assertEquals(before.top + 80f, moved.top, 0.01f)
                model.commitSelectionMove()
            }
            idle(model)
            fun positions() = model.edits.snapshot().map { it.value.ink.inputs[0].x }
            assertEquals(listOf(150f, 300f, 180f), withContext(Dispatchers.Main) { positions() })
            withContext(Dispatchers.Main) { model.edits.undo() }
            assertEquals(listOf(100f, 300f, 130f), withContext(Dispatchers.Main) { positions() })
            withContext(Dispatchers.Main) { model.edits.redo(); model.deleteSelection() }
            assertNull(model.selectionOverlay.value)
            assertEquals(listOf(300f), withContext(Dispatchers.Main) { positions() })
            withContext(Dispatchers.Main) { model.edits.undo() }
            val id = model.workspace.value.current!!.id
            withContext(Dispatchers.Main) { model.closeNote() }
            withTimeout(15000) { model.workspace.first { it.current == null && !it.working } }
            withContext(Dispatchers.Main) { model.openNote(id) }
            withTimeout(15000) { model.workspace.first { it.current?.id == id && !it.working } }
            assertEquals(listOf(150f, 300f, 180f), withContext(Dispatchers.Main) { positions() })
        }
    }
    @Test fun penHighlighterAndEraserKeepSeparatePresetsAndNewFortyMeansPreviousFifty() = runBlocking {
        withModel { model ->
            withContext(Dispatchers.Main) {
                model.style(color = 0xFF123456.toInt(), size = 40f)
                model.saveColor(0x123456)
                model.saveWidth(2, 40)
                assertEquals(12.5f, model.brush.size, 0f)
                model.chooseTool(EditorTool.HIGHLIGHTER)
                assertFalse(0x123456 in model.palette.value.colors)
                model.saveColor(0xABCDEF)
                assertEquals(InkBrushes.highlighter, model.brush.family)
                model.style(color = 0x40ABCDEF, size = 36f); model.saveWidth(2, 36)
                model.chooseTool(EditorTool.ERASER)
                model.style(size = 24f); model.saveWidth(2, 24)
                assertEquals(12f, model.eraserRadius, 0f)
                assertEquals(listOf(40, 36, 24), model.preferences.value.value.let { listOf(it.widths[2], it.highlighterWidths[2], it.eraserWidths[2]) })
                model.chooseTool(EditorTool.PEN)
                assertTrue(0x123456 in model.palette.value.colors)
                assertFalse(0xABCDEF in model.palette.value.colors)
                model.removeColor(0x123456)
                assertEquals(0xFF123456.toInt(), model.ui.value.color)
                assertEquals(40f, model.ui.value.size, 0f)
                model.chooseTool(EditorTool.HIGHLIGHTER)
                assertTrue(0xABCDEF in model.palette.value.colors)
                assertEquals(0x40ABCDEF, model.ui.value.color)
                assertEquals(PenStyle.brushWidth(36f), model.brush.size, 0f)
                assertEquals(36, model.preferences.value.value.highlighterWidths[2])
                model.chooseTool(EditorTool.ERASER)
                assertEquals(24f, model.ui.value.size, 0f)
            }
        }
    }
    @Test fun pressureCurveControlsRenderedWidthAndSurvivesMoveAndStorage() {
        val curve = PressureCurve(20, 200)
        fun line(pressure: Float): EditorStroke {
            val inputs = MutableStrokeInputBatch()
            for (i in 0..20) inputs.add(InputToolType.STYLUS, 100f + i * 5f, 100f, i * 10L, pressure = pressure)
            return EditorStroke(Stroke(Brush.createWithColorIntArgb(InkBrushes.pressurePen(curve), 0xFF123456.toInt(), 8f, 0.1f), inputs))
        }
        val thin = line(0f).bounds!!; val thick = line(1f).bounds!!
        assertTrue((thick.yMax - thick.yMin) > (thin.yMax - thin.yMin) * 3f)
        val original = line(0.5f)
        val moved = original.translated(10f, 20f)
        val loaded = NoteRepository.decodeStroke(moved.storageId, NoteRepository.encodeStroke(moved))
        assertEquals(curve, InkBrushes.curveFor(loaded.ink.brush.family))
        assertEquals(moved.ink.brush, loaded.ink.brush)
        assertEquals(moved.bounds!!.yMax, loaded.bounds!!.yMax, 0.05f)
        assertEquals(original.ink.inputs[0].x + 10f, loaded.ink.inputs[0].x, 0.05f)
        assertEquals(0.5f, loaded.ink.inputs[0].pressure, 0.001f)
    }
    @Test fun minimumPressureWidthAtOneHundredCreatesConstantInkAndReloads() {
        // Regression: Ink rejects a TargetNode modifier range of {1, 1}.
        for (curve in listOf(25, 100, 400).map { PressureCurve(100, it) } +
            listOf(PressureCurve(100, bezier = BezierControls(0, 0, 100, 0)), PressureCurve(100, bezier = BezierControls()),
                PressureCurve(100, spline = PressureSpline(listOf(PressureControlPoint(0, 100), PressureControlPoint(30, 100), PressureControlPoint(100, 100)))),
                PressureCurve(100, spline = PressureSpline(listOf(PressureControlPoint(0, 100), PressureControlPoint(30, 100), PressureControlPoint(100, 100)), PressureBasis.BEZIER_CONTROLS)))) {
            val family = InkBrushes.pressurePen(curve)
            val heights = listOf(0f, 0.5f, 1f).map { pressure ->
                val inputs = MutableStrokeInputBatch()
                    .add(InputToolType.STYLUS, 100f, 100f, 0, pressure = pressure)
                    .add(InputToolType.STYLUS, 200f, 100f, 100, pressure = pressure)
                val original = EditorStroke(Stroke(Brush.createWithColorIntArgb(family, 0xFF123456.toInt(), 8f, 0.1f), inputs))
                val loaded = NoteRepository.decodeStroke(original.storageId, NoteRepository.encodeStroke(original))
                assertEquals(curve, InkBrushes.curveFor(loaded.ink.brush.family))
                val bounds = loaded.bounds!!
                bounds.yMax - bounds.yMin
            }
            assertTrue(heights.first() > 7.5f)
            heights.forEach { assertEquals(heights.first(), it, 0.05f) }
        }
    }
    @Test fun bezierShapeMatchesGraphAndControlPointsSurviveMoveAndStorage() {
        val curve = PressureCurve(20, bezier = BezierControls(0, 0, 100, 0))
        for (pressure in listOf(0f, 0.5f, 1f)) {
            val inputs = MutableStrokeInputBatch()
            for (i in 0..20) inputs.add(InputToolType.STYLUS, 100f + i * 5f, 100f, i * 10L, pressure = pressure)
            val original = EditorStroke(Stroke(Brush.createWithColorIntArgb(InkBrushes.pressurePen(curve), 0xFF123456.toInt(), 8f, 0.1f), inputs))
            val moved = original.translated(10f, 20f)
            val loaded = NoteRepository.decodeStroke(moved.storageId, NoteRepository.encodeStroke(moved))
            assertEquals(curve, InkBrushes.curveFor(loaded.ink.brush.family))
            assertEquals(8f * curve.factor(pressure), loaded.bounds!!.let { it.yMax - it.yMin }, 0.25f)
            assertEquals(original.ink.inputs[0].x + 10f, loaded.ink.inputs[0].x, 0.05f)
            assertEquals(original.ink.inputs[0].y + 20f, loaded.ink.inputs[0].y, 0.05f)
        }
    }
    @Test fun zoomClearsLassoSelectionWhilePanKeepsItWithoutChangingStrokes() = runBlocking {
        withModel { model ->
            fun select() = model.selectLasso(listOf(WorldPoint(80f, 80f), WorldPoint(180f, 80f),
                WorldPoint(180f, 180f), WorldPoint(80f, 180f)))
            withContext(Dispatchers.Main) {
                model.viewport.size(1000f, 800f)
                model.edits.restore(listOf(OrderedEdits.Entry(1L, stroke(100f, 100f))))
                model.chooseTool(EditorTool.LASSO); select()
            }
            idle(model)
            val revision = model.edits.revision
            withContext(Dispatchers.Main) {
                model.transformViewport(500f, 400f, 500f, 380f, 1f); model.viewportChanged()
                assertEquals(1, model.ui.value.selectionCount)
                assertNotNull(model.selectionOverlay.value)
                model.transformViewport(0f, 0f, 0f, 0f, 2f); model.viewportChanged()
                assertEquals(200, model.zoomPercent.value)
                assertEquals(0, model.ui.value.selectionCount)
                assertNull(model.selectionBounds)
                assertNull(model.selectionOverlay.value)
                assertEquals(EditorTool.LASSO, model.ui.value.tool)
                assertEquals(revision, model.edits.revision)
                select()
            }
            idle(model)
            withContext(Dispatchers.Main) {
                assertEquals(1, model.ui.value.selectionCount)
                model.fitWidth()
                assertEquals(100, model.zoomPercent.value)
                assertEquals(0, model.ui.value.selectionCount)
                assertEquals(revision, model.edits.revision)
            }
        }
    }
    @Test fun arbitraryCurvePointsMatchInkAndSurviveTranslationAndReload() {
        val spline = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(100, 100)))
            .added(20, 60).added(50, 75).added(80, 95).moved(2, 45, 80)
        val curve = PressureCurve(spline.minimumPercent, spline = spline)
        for (pressure in listOf(0f, 0.2f, 0.45f, 0.7f, 1f)) {
            val inputs = MutableStrokeInputBatch()
            for (i in 0..20) inputs.add(InputToolType.STYLUS, 100f + i * 5f, 100f, i * 10L, pressure = pressure)
            val original = EditorStroke(Stroke(Brush.createWithColorIntArgb(InkBrushes.pressurePen(curve), 0xFF123456.toInt(), 8f, 0.1f), inputs))
            val moved = original.translated(10f, 20f)
            val loaded = NoteRepository.decodeStroke(moved.storageId, NoteRepository.encodeStroke(moved))
            assertEquals(curve, InkBrushes.curveFor(loaded.ink.brush.family))
            assertEquals(8f * curve.factor(pressure), loaded.bounds!!.let { it.yMax - it.yMin }, 0.25f)
            assertEquals(original.ink.inputs[0].x + 10f, loaded.ink.inputs[0].x, 0.05f)
        }
    }
    @Test fun controlPolygonBezierDoesNotPassThroughHandlesAndSurvivesStorage() {
        val controls = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(50, 100),
            PressureControlPoint(100, 100)), PressureBasis.BEZIER_CONTROLS)
        for (spline in listOf(controls, controls.dragged(1, 121f, 50f))) {
            val curve = PressureCurve(spline.minimumPercent, spline = spline)
            val inputs = MutableStrokeInputBatch()
            for (i in 0..20) inputs.add(InputToolType.STYLUS, 100f + i * 5f, 100f, i * 10L, pressure = 0.5f)
            val original = EditorStroke(Stroke(Brush.createWithColorIntArgb(InkBrushes.pressurePen(curve), 0xFF123456.toInt(), 8f, 0.1f), inputs))
            val moved = original.translated(10f, 20f)
            val loaded = NoteRepository.decodeStroke(moved.storageId, NoteRepository.encodeStroke(moved))
            assertEquals(curve, InkBrushes.curveFor(loaded.ink.brush.family))
            assertEquals(8f * curve.factor(0.5f), loaded.bounds!!.let { it.yMax - it.yMin }, 0.25f)
            assertEquals(if (spline.points.size == 3) 0.8f else 0.6f, curve.factor(0.5f), 0.00001f)
        }
    }
    @Test fun passingCurveKeepsEdgeDragsAndDeletesOnlyFarOutsideThenReloadsInk() {
        val spline = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(50, 100), PressureControlPoint(100, 100)))
        val near = spline.dragged(1, 50f, 110f)
        assertEquals(spline, near)
        val far = near.dragged(1, 50f, 121f)
        assertEquals(2, far.points.size)
        for (points in listOf(near, far)) {
            val curve = PressureCurve(points.minimumPercent, spline = points)
            val inputs = MutableStrokeInputBatch()
            for (i in 0..20) inputs.add(InputToolType.STYLUS, 100f + i * 5f, 100f, i * 10L, pressure = 0.5f)
            val original = EditorStroke(Stroke(Brush.createWithColorIntArgb(InkBrushes.pressurePen(curve), 0xFF123456.toInt(), 8f, 0.1f), inputs))
            val loaded = NoteRepository.decodeStroke(original.storageId, NoteRepository.encodeStroke(original))
            assertEquals(curve, InkBrushes.curveFor(loaded.ink.brush.family))
            assertEquals(8f * curve.factor(0.5f), loaded.bounds!!.let { it.yMax - it.yMin }, 0.25f)
        }
    }
    @Test fun manualHandlesControlActualInkAndSurviveMoveAndStorage() {
        val spline = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(40, 50), PressureControlPoint(100, 100)))
            .withHandles().movedHandle(1, false, 65f, 55f)
        for (shape in listOf(spline, spline.moved(0, 0, 100))) {
            val curve = PressureCurve(shape.minimumPercent, spline = shape)
            for (pressure in listOf(0f, 0.2f, 0.4f, 0.65f, 1f)) {
                val inputs = MutableStrokeInputBatch()
                for (i in 0..20) inputs.add(InputToolType.STYLUS, 100f + i * 5f, 100f, i * 10L, pressure = pressure)
                val original = EditorStroke(Stroke(Brush.createWithColorIntArgb(InkBrushes.pressurePen(curve), 0xFF123456.toInt(), 8f, 0.1f), inputs))
                val moved = original.translated(10f, 20f)
                val loaded = NoteRepository.decodeStroke(moved.storageId, NoteRepository.encodeStroke(moved))
                assertEquals(curve, InkBrushes.curveFor(loaded.ink.brush.family))
                assertEquals(8f * curve.factor(pressure), loaded.bounds!!.let { it.yMax - it.yMin }, 0.25f)
            }
        }
    }
    private suspend fun idle(model: EditorViewModel) = withTimeout(15000) { model.ui.first { !it.busy } }
    private suspend fun withModel(block: suspend (EditorViewModel) -> Unit) {
        val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "tools-${UUID.randomUUID()}")
        val store = ViewModelStore()
        try {
            val model = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[EditorViewModel::class.java].also { it.start(root) }
            }
            withTimeout(15000) { model.workspace.first { !it.working } }
            withContext(Dispatchers.Main) { model.createNote("tools") }
            withTimeout(15000) { model.workspace.first { it.current != null && !it.working } }
            block(model)
            withContext(Dispatchers.Main) { model.closeNote() }
            withTimeout(15000) { model.workspace.first { it.current == null && !it.working } }
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            root.deleteRecursively()
        }
    }
}
