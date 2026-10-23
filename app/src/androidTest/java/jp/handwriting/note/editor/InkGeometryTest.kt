package jp.handwriting.note.editor

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InkGeometryTest {
    @Test fun circularEraserRejectsSquareCornersAndCoversFastSweeps() {
        fun line(x: Float, y: Float): Stroke {
            val inputs = MutableStrokeInputBatch()
                .add(InputToolType.STYLUS, x, y, 0)
                .add(InputToolType.STYLUS, x + 2f, y, 20)
            return Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(), 0xFF202B33.toInt(), 0.2f, 0.01f), inputs)
        }
        assertFalse(CircularEraser.hits(line(9f, 9f).shape, 0f, 0f, 0f, 0f, 10f))
        assertTrue(CircularEraser.hits(line(6f, 6f).shape, 0f, 0f, 0f, 0f, 10f))
        assertTrue(CircularEraser.hits(line(50f, 9f).shape, 0f, 0f, 100f, 0f, 10f))
        assertFalse(CircularEraser.hits(line(50f, 11f).shape, 0f, 0f, 100f, 0f, 10f))
        assertTrue(CircularEraser.hits(line(50f, 11f).shape, 0f, 0f, 100f, 0f, 12f))
    }
    private fun stroke(): EditorStroke {
        val inputs = MutableStrokeInputBatch()
        inputs.add(InputToolType.STYLUS, 50f, 100f, 0, pressure = 0.2f)
        inputs.add(InputToolType.STYLUS, 150f, 100f, 100, pressure = 0.8f)
        return EditorStroke(Stroke(Brush.createWithColorIntArgb(StockBrushes.pressurePen(), 0xFF202B33.toInt(), 3f, 0.1f), inputs))
    }
    @Test fun sweptEraserHitsBetweenSamplesAndUndoRestoresTheWholeStroke() {
        val model = EditorViewModel()
        val op = model.edits.reserveStroke()
        model.edits.resolveStroke(op, stroke())
        val erase = model.edits.beginErase()
        model.erase(erase, 100f, 50f, 100f, 150f, 10f)
        model.edits.endErase(erase)
        assertEquals(0, model.edits.entries.size)
        model.edits.undo()
        assertEquals(1, model.edits.entries.size)
    }
    @Test fun distantEraserDoesNotDeleteStroke() {
        val model = EditorViewModel()
        val op = model.edits.reserveStroke()
        model.edits.resolveStroke(op, stroke())
        val erase = model.edits.beginErase()
        model.erase(erase, 600f, 500f, 700f, 500f, 10f)
        model.edits.endErase(erase)
        assertEquals(1, model.edits.entries.size)
    }
    @Test fun inkStoragePreservesCoordinatesAndPressureWithoutMeshData() {
        val original = stroke().ink.inputs
        val output = ByteArrayOutputStream()
        original.encode(output)
        val decoded = StrokeInputBatch.decode(ByteArrayInputStream(output.toByteArray()))
        assertEquals(original.size, decoded.size)
        for (i in 0 until original.size) {
            assertEquals(original[i].x, decoded[i].x, 0.01f)
            assertEquals(original[i].y, decoded[i].y, 0.01f)
            assertEquals(original[i].pressure, decoded[i].pressure, 0.001f)
        }
    }
}
