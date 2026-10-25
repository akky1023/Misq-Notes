package jp.handwriting.note.storage

import java.io.File
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import jp.handwriting.note.editor.EditorTool
import jp.handwriting.note.editor.PenStyle
import jp.handwriting.note.editor.PressureCurve
import jp.handwriting.note.editor.BezierControls
import jp.handwriting.note.editor.PressureControlPoint
import jp.handwriting.note.editor.PressureSpline
import jp.handwriting.note.editor.PressureBasis
import jp.handwriting.note.editor.PenButtonAction
import jp.handwriting.note.editor.LassoMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolPreferencesTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun rectangleLassoSurvivesRestartAndVersionNineDefaultsToFree() {
        val file = File(temp.newFolder(), "tools.hwp")
        val value = ToolPreferences(lassoMode = LassoMode.RECTANGLE)
        ToolPreferencesFile(file).write(value)
        assertEquals(value, ToolPreferencesFile(file).read())
        val current = NoteFiles.readEnvelope(file)
        // Version 9 omits the lasso mode and the two media preferences.
        val previous = current.copyOf(current.size - 6)
        previous[3] = 9
        NoteFiles.atomicWrite(file, previous)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(value.copy(lassoMode = LassoMode.FREE), loaded)
    }
    @Test fun mediaPreferencesSurviveRestartAndVersionTenUsesSafeDefaults() {
        val file = File(temp.newFolder(), "tools.hwp")
        assertTrue(ToolPreferencesFile(file).read().pinchResizeMedia)
        assertFalse(ToolPreferencesFile(file).read().lassoIncludeMedia)
        val value = ToolPreferences(lassoMode = LassoMode.RECTANGLE, pinchResizeMedia = false, lassoIncludeMedia = true)
        ToolPreferencesFile(file).write(value)
        assertEquals(value, ToolPreferencesFile(file).read())
        val current = NoteFiles.readEnvelope(file)
        val previous = current.copyOf(current.size - 2)
        previous[3] = 10
        NoteFiles.atomicWrite(file, previous)
        assertEquals(value.copy(pinchResizeMedia = true, lassoIncludeMedia = false), ToolPreferencesFile(file).read())
    }
    @Test fun lassoMediaToggleRemembersBothStatesAcrossRestartsAndOtherPreferenceChanges() {
        val file = File(temp.newFolder(), "tools.hwp")
        for (enabled in listOf(true, false, true)) {
            val current = ToolPreferencesFile(file).read()
            ToolPreferencesFile(file).write(current.copy(lassoIncludeMedia = enabled))
            val restored = ToolPreferencesFile(file).read()
            assertEquals(enabled, restored.lassoIncludeMedia)
            ToolPreferencesFile(file).write(restored.copy(pinchResizeMedia = !restored.pinchResizeMedia))
            assertEquals(enabled, ToolPreferencesFile(file).read().lassoIncludeMedia)
        }
    }
    @Test fun allThreeButtonActionsSurviveRestart() {
        val file = File(temp.newFolder(), "tools.hwp")
        val value = ToolPreferences(buttonActions = listOf(PenButtonAction.REDO, PenButtonAction.TEMPORARY_ERASER, PenButtonAction.LASSO))
        ToolPreferencesFile(file).write(value)
        assertEquals(value, ToolPreferencesFile(file).read())
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(buttonActions = emptyList()) }
    }
    @Test fun reorderedToolsSurviveRestartAndDuplicateOrMissingToolsAreRejected() {
        val file = File(temp.newFolder(), "tools.hwp")
        val value = ToolPreferences(toolOrder = listOf(EditorTool.LASSO, EditorTool.ERASER, EditorTool.PEN, EditorTool.HIGHLIGHTER))
        ToolPreferencesFile(file).write(value)
        assertEquals(value, ToolPreferencesFile(file).read())
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(toolOrder = List(4) { EditorTool.PEN }) }
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(toolOrder = listOf(EditorTool.PEN)) }
    }
    @Test fun editedHandlesAndTheirCurveSurviveRestartExactly() {
        val file = File(temp.newFolder(), "tools.hwp")
        val spline = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(40, 50), PressureControlPoint(100, 100)))
            .withHandles().movedHandle(1, false, 65f, 55f)
        val value = ToolPreferences(pressureCurve = PressureCurve(20, spline = spline))
        ToolPreferencesFile(file).write(value)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(value, loaded)
        for (i in 0..100) assertEquals(spline.factor(i / 100f), loaded.pressureCurve.factor(i / 100f), 0f)
    }
    @Test fun versionSixAutomaticallyAddsHandlesWithoutMovingPointsOrChangingWidths() {
        val file = File(temp.newFolder(), "tools.hwp")
        val old = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(40, 50), PressureControlPoint(100, 100)))
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(6); listOf(1, 20, 40).forEach(out::writeInt)
            out.writeInt(0x40ABCDEF); out.writeInt(40)
            listOf(10, 25, 40).forEach(out::writeInt); listOf(10, 20, 35).forEach(out::writeInt)
            out.writeInt(20); PressureCurveCodec.write(out, PressureCurve(20, spline = old))
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(listOf(1, 20, 40), loaded.widths)
        assertEquals(old.points, loaded.pressureCurve.spline!!.points)
        assertNotNull(loaded.pressureCurve.spline!!.tangents)
        for (i in 0..100) assertEquals(old.factor(i / 100f), loaded.pressureCurve.factor(i / 100f), 0.002f)
    }
    @Test fun separateToolSlotsEraserAndPressureCurveSurviveRestart() {
        val file = File(temp.newFolder(), "tools.hwp")
        assertEquals(ToolPreferences(), ToolPreferencesFile(file).read())
        val value = ToolPreferences(listOf(1, 25, 50), 0x40ABCDEF, 36,
            listOf(2, 30, 49), listOf(10, 20, 35), 18, PressureCurve(10, bezier = BezierControls(10, 25, 70, 90)))
        ToolPreferencesFile(file).write(value)
        assertEquals(value, ToolPreferencesFile(file).read())
        assertEquals(listOf(1, 25, 50), value.widthsFor(EditorTool.PEN))
        assertEquals(listOf(2, 30, 49), value.widthsFor(EditorTool.HIGHLIGHTER))
        assertEquals(listOf(10, 20, 35), value.widthsFor(EditorTool.ERASER))
    }
    @Test fun legacySharedTemplatesMigrateByPhysicalWidthAndSplitForEachPen() {
        val file = File(temp.newFolder(), "tools.hwp")
        val oldWidths = listOf(1, 6, 100)
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(1); oldWidths.forEach(out::writeInt); out.writeInt(0x40ABCDEF); out.writeInt(25)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val migrated = ToolPreferencesFile(file).read()
        assertEquals(oldWidths.map(PenStyle::legacyWidth), migrated.widths)
        assertEquals(migrated.widths, migrated.highlighterWidths)
        assertEquals(40, migrated.highlighterSize)
        assertEquals(0x40ABCDEF, migrated.highlighterColor)
        assertEquals(ToolPreferences().eraserWidths, migrated.eraserWidths)
        ToolPreferencesFile(file).write(migrated)
        assertEquals(migrated, ToolPreferencesFile(file).read())
    }
    @Test fun interruptedOrCorruptWriteRecoversPreviousPreferences() {
        val file = File(temp.newFolder(), "tools.hwp")
        val before = ToolPreferences(listOf(2, 12, 50))
        ToolPreferencesFile(file).write(before)
        ToolPreferencesFile(file).write(ToolPreferences())
        file.writeBytes(byteArrayOf(0))
        assertEquals(before, ToolPreferencesFile(file).read())
        assertTrue(file.delete())
        assertEquals(before, ToolPreferencesFile(file).read())
    }
    @Test fun oneHundredPercentPressureSettingReloadsAsConstantWidth() {
        val file = File(temp.newFolder(), "tools.hwp")
        val value = ToolPreferences(pressureCurve = PressureCurve(100, bezier = BezierControls(10, 25, 70, 90)))
        ToolPreferencesFile(file).write(value)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(value, loaded)
        for (pressure in listOf(0f, 0.01f, 0.5f, 1f)) assertEquals(1f, loaded.pressureCurve.factor(pressure), 0f)
    }
    @Test fun slotCountAndWidthRangeAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(listOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(listOf(1, 2, 51)) }
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(highlighterWidths = listOf(1, 2)) }
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(eraserWidths = listOf(0, 2, 3)) }
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(highlighterSize = 0) }
        assertThrows(IllegalArgumentException::class.java) { ToolPreferences(eraserSize = 51) }
    }
    @Test fun versionTwoMigratesPenWidthsAndCurveWhileKeepingEraserSize() {
        val file = File(temp.newFolder(), "tools.hwp")
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(2); listOf(1, 10, 50).forEach(out::writeInt)
            out.writeInt(0x40ABCDEF); out.writeInt(50)
            listOf(10, 25, 50).forEach(out::writeInt); listOf(10, 20, 35).forEach(out::writeInt)
            out.writeInt(20); out.writeInt(100); out.writeInt(400)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(listOf(1, 1, 40), loaded.widths)
        assertEquals(listOf(10, 25, 50).map(PenStyle::migratePreviousWidth), loaded.highlighterWidths)
        assertEquals(40, loaded.highlighterSize)
        assertEquals(listOf(10, 20, 35), loaded.eraserWidths)
        assertEquals(20, loaded.eraserSize)
        assertNotNull(loaded.pressureCurve.spline)
        assertEquals(1f, loaded.pressureCurve.factor(0.25f), 0f)
        ToolPreferencesFile(file).write(loaded)
        assertEquals(loaded, ToolPreferencesFile(file).read())
    }
    @Test fun addedAndMovedControlPointsSurviveRestart() {
        val file = File(temp.newFolder(), "tools.hwp")
        val spline = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(100, 100)))
            .added(25, 50).added(60, 75).moved(1, 30, 55)
        val value = ToolPreferences(pressureCurve = PressureCurve(spline.minimumPercent, spline = spline))
        ToolPreferencesFile(file).write(value)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(value, loaded)
        assertEquals(spline.points, loaded.pressureCurve.spline!!.points)
    }
    @Test fun versionThreeBezierSettingsBecomeEditablePointsWithoutChangingWidths() {
        val file = File(temp.newFolder(), "tools.hwp")
        val curve = PressureCurve(20, bezier = BezierControls(10, 25, 70, 90))
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(3); listOf(1, 20, 40).forEach(out::writeInt)
            out.writeInt(0x40ABCDEF); out.writeInt(40)
            listOf(10, 25, 40).forEach(out::writeInt); listOf(10, 20, 35).forEach(out::writeInt)
            out.writeInt(20); PressureCurveCodec.write(out, curve)
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(listOf(1, 20, 40), loaded.widths)
        assertEquals(40, loaded.highlighterSize)
        assertNotNull(loaded.pressureCurve.spline)
        for (i in 0..100) assertEquals(curve.factor(i / 100f), loaded.pressureCurve.factor(i / 100f), 0.02f)
        ToolPreferencesFile(file).write(loaded)
        assertEquals(loaded, ToolPreferencesFile(file).read())
    }
    @Test fun controlBezierBasisAndDraggedDeletionSurviveRestart() {
        val file = File(temp.newFolder(), "tools.hwp")
        val curve = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(50, 100),
            PressureControlPoint(100, 100)), PressureBasis.BEZIER_CONTROLS)
        val value = ToolPreferences(pressureCurve = PressureCurve(curve.minimumPercent, spline = curve))
        ToolPreferencesFile(file).write(value)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(value, loaded)
        assertEquals(0.8f, loaded.pressureCurve.factor(0.5f), 0.00001f)
        val deleted = curve.dragged(1, -21f, 50f)
        ToolPreferencesFile(file).write(value.copy(pressureCurve = PressureCurve(deleted.minimumPercent, spline = deleted)))
        assertEquals(2, ToolPreferencesFile(file).read().pressureCurve.spline!!.points.size)
    }
    @Test fun versionFourKeepsItsPassingCurveAndPointPositions() {
        val file = File(temp.newFolder(), "tools.hwp")
        val old = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(50, 100), PressureControlPoint(100, 100)))
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(4); listOf(1, 20, 40).forEach(out::writeInt)
            out.writeInt(0x40ABCDEF); out.writeInt(40)
            listOf(10, 25, 40).forEach(out::writeInt); listOf(10, 20, 35).forEach(out::writeInt)
            out.writeInt(20); PressureCurveCodec.write(out, PressureCurve(old.minimumPercent, spline = old))
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(listOf(1, 20, 40), loaded.widths)
        assertEquals(old.points, loaded.pressureCurve.spline!!.points)
        assertEquals(PressureBasis.INTERPOLATED, loaded.pressureCurve.spline!!.basis)
        assertEquals(1f, loaded.pressureCurve.factor(0.5f), 0.00001f)
    }
    @Test fun versionFiveControlPointsBecomePassingPointsWithoutChangingWidths() {
        val file = File(temp.newFolder(), "tools.hwp")
        val old = PressureSpline(listOf(PressureControlPoint(0, 20), PressureControlPoint(50, 100),
            PressureControlPoint(100, 100)), PressureBasis.BEZIER_CONTROLS)
        val bytes = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { out ->
            out.writeInt(5); listOf(1, 20, 40).forEach(out::writeInt)
            out.writeInt(0x40ABCDEF); out.writeInt(40)
            listOf(10, 25, 40).forEach(out::writeInt); listOf(10, 20, 35).forEach(out::writeInt)
            out.writeInt(20); PressureCurveCodec.write(out, PressureCurve(old.minimumPercent, spline = old))
        } }.toByteArray()
        NoteFiles.atomicWrite(file, bytes)
        val loaded = ToolPreferencesFile(file).read()
        assertEquals(listOf(1, 20, 40), loaded.widths)
        assertEquals(old.points, loaded.pressureCurve.spline!!.points)
        assertEquals(PressureBasis.INTERPOLATED, loaded.pressureCurve.spline!!.basis)
        assertEquals(1f, loaded.pressureCurve.factor(0.5f), 0f)
        ToolPreferencesFile(file).write(loaded)
        assertEquals(loaded, ToolPreferencesFile(file).read())
    }
}
