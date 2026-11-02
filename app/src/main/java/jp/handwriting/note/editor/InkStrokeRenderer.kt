package jp.handwriting.note.editor

import android.annotation.SuppressLint
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.core.graphics.withSave
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.populateMatrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke

/** Ink 1.0's mesh renderer can drop round cap geometry depending on stroke position. */
internal object InkStrokeRenderer {
    // Pinned Ink 1.0 exposes the path fallback through this restricted factory overload.
    @SuppressLint("RestrictedApi")
    fun create(): CanvasStrokeRenderer = LocalRenderer(CanvasStrokeRenderer.create(forcePathRendering = true))

    @SuppressLint("RestrictedApi")
    @Suppress("DEPRECATION")
    fun configure(view: InProgressStrokesView) {
        view.rendererFactory = { create() }
    }

    /** Live strokes keep Ink's latency-sensitive path; completed tips are smoothed in local space. */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    @SuppressLint("RestrictedApi")
    private class LocalRenderer(private val delegate: CanvasStrokeRenderer) : CanvasStrokeRenderer by delegate {
        private val cache = object : LinkedHashMap<Stroke, LocalStrokeGeometry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stroke, LocalStrokeGeometry>?) = size > 128
        }

        override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: Matrix) =
            draw(canvas, stroke, strokeToScreenTransform, 0f)

        override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: Matrix, textureAnimationProgress: Float) {
            val local = cache.getOrPut(stroke) { StrokeLocalCoordinates.create(stroke) }
            canvas.withSave {
                translate(local.x, local.y)
                // Ink's AA transform uses the linear part; Canvas carries this translation.
                delegate.draw(this, local.stroke, strokeToScreenTransform, textureAnimationProgress)
            }
        }

        override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: AffineTransform) =
            draw(canvas, stroke, strokeToScreenTransform, 0f)

        override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: AffineTransform, textureAnimationProgress: Float) {
            val matrix = Matrix()
            strokeToScreenTransform.populateMatrix(matrix)
            draw(canvas, stroke, matrix, textureAnimationProgress)
        }
    }
}
