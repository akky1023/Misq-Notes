package jp.handwriting.note.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import jp.handwriting.note.storage.NotePaper
import jp.handwriting.note.storage.PaperPattern
import org.junit.Assert.*
import org.junit.Test

class PaperRendererTest {
    @Test fun alphaCompositesLinesOverThePaperWithoutChangingTheBackground() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val paper = NotePaper(0xFFFFFFFF.toInt(), PaperPattern.GRID, 0x80000000.toInt(), 32, 8f)
            PaperRenderer.draw(Canvas(bitmap), paper, RectF(0f, 0f, 100f, 100f), 1f)
            assertEquals(0xFF7F7F7F.toInt(), bitmap.getPixel(32, 16))
            assertEquals(paper.color, bitmap.getPixel(16, 16))
            PaperRenderer.draw(Canvas(bitmap), paper.copy(lineColor = 0), RectF(0f, 0f, 100f, 100f), 1f)
            assertEquals(paper.color, bitmap.getPixel(32, 16))
        } finally { bitmap.recycle() }
    }
    @Test fun increasedThicknessAffectsBothGridLinesAndDots() {
        for (pattern in listOf(PaperPattern.GRID, PaperPattern.DOTS)) {
            val thin = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            val thick = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
            try {
                val paper = NotePaper(0xFFFFFFFF.toInt(), pattern, 0xFF000000.toInt(), 32, 0.25f)
                PaperRenderer.draw(Canvas(thin), paper, RectF(0f, 0f, 100f, 100f), 1f)
                PaperRenderer.draw(Canvas(thick), paper.copy(lineWidth = 8f), RectF(0f, 0f, 100f, 100f), 1f)
                val x = if (pattern == PaperPattern.DOTS) 36 else 34
                val y = if (pattern == PaperPattern.DOTS) 32 else 17
                assertEquals(0xFFFFFFFF.toInt(), thin.getPixel(x, y))
                assertEquals(0xFF000000.toInt(), thick.getPixel(x, y))
            } finally { thin.recycle(); thick.recycle() }
        }
    }

    @Test fun collegeMarginHasIndependentColorWidthAndPositionAndOnlyAppearsOnCollegePaper() {
        val bitmap = Bitmap.createBitmap(200, 120, Bitmap.Config.ARGB_8888)
        try {
            val paper = NotePaper(color = -1, pattern = PaperPattern.COLLEGE_RULED,
                lineColor = 0xFF0000FF.toInt(), marginColor = 0xFFFF0000.toInt(), marginWidth = 8f, marginOffset = 50f)
            val bounds = RectF(0f, 0f, 200f, 120f)
            PaperRenderer.draw(Canvas(bitmap), paper, bounds, 1f)
            assertEquals(paper.marginColor, bitmap.getPixel(52, 17))
            assertEquals(paper.lineColor, bitmap.getPixel(120, 32))
            assertEquals(paper.color, bitmap.getPixel(60, 17))
            PaperRenderer.draw(Canvas(bitmap), paper.copy(marginOffset = 80f, marginWidth = 2f), bounds, 1f)
            assertEquals(paper.color, bitmap.getPixel(50, 17))
            assertEquals(paper.marginColor, bitmap.getPixel(80, 17))
            assertEquals(paper.color, bitmap.getPixel(82, 17))
            PaperRenderer.draw(Canvas(bitmap), paper.copy(marginColor = 0), bounds, 1f)
            assertEquals(paper.color, bitmap.getPixel(50, 17))
            PaperRenderer.draw(Canvas(bitmap), paper.copy(pattern = PaperPattern.RULED), bounds, 1f)
            assertEquals(paper.color, bitmap.getPixel(50, 17))
        } finally { bitmap.recycle() }
    }
    @Test fun collegeMarginStaysAtTheSameWorldPositionWhenZoomedAndScrolled() {
        val bitmap = Bitmap.createBitmap(200, 160, Bitmap.Config.ARGB_8888)
        try {
            val paper = NotePaper(color = -1, pattern = PaperPattern.COLLEGE_RULED, lineColor = 0,
                marginColor = 0xFFFF0000.toInt(), marginWidth = 4f, marginOffset = 100f)
            val canvas = Canvas(bitmap)
            canvas.scale(2f, 2f); canvas.translate(-60f, -500f)
            PaperRenderer.draw(canvas, paper, RectF(60f, 500f, 160f, 580f), 2f)
            assertEquals(paper.marginColor, bitmap.getPixel(80, 40))
            assertEquals(paper.color, bitmap.getPixel(84, 40))
        } finally { bitmap.recycle() }
    }

}
