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
    fun createForDisplay(): LocalRenderer = LocalRenderer(CanvasStrokeRenderer.create(forcePathRendering = true), display = true)

    @SuppressLint("RestrictedApi")
    @Suppress("DEPRECATION")
    fun configure(view: InProgressStrokesView) {
        view.rendererFactory = { create() }
    }

    /** Live strokes keep Ink's latency-sensitive path; completed tips are smoothed in local space. */
    @OptIn(ExperimentalInkCustomBrushApi::class)
    @SuppressLint("RestrictedApi")
    internal class LocalRenderer(private val delegate: CanvasStrokeRenderer, private val display: Boolean = false) : CanvasStrokeRenderer by delegate {
        private var retained: Set<Stroke>? = null
        private val cache = object : LinkedHashMap<Stroke, LocalStrokeGeometry>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Stroke, LocalStrokeGeometry>?) = retained == null && size > 128
        }

        @Synchronized fun retainVisible(strokes: Collection<Stroke>) {
            retained = strokes.toSet()
            cache.keys.retainAll(retained!!)
        }

        @Synchronized fun clear() { retained = emptySet(); cache.clear() }

        /** Outline generation may run on a worker; Canvas and the delegate remain UI-only. */
        fun prefetch(stroke: Stroke) {
            if (synchronized(this) { retained?.contains(stroke) == true }) geometry(stroke)
        }

        internal fun geometry(stroke: Stroke): LocalStrokeGeometry {
            synchronized(this) { cache[stroke]?.let { return it } }
            // Refine directly in local coordinates instead of generating two native outlines.
            val epsilon = if (display) StrokeFidelity.displayEpsilon(stroke.brush.size) else stroke.brush.epsilon
            val local = StrokeLocalCoordinates.create(stroke, epsilon)
            return synchronized(this) {
                if (retained?.contains(stroke) == false) local else cache.getOrPut(stroke) { local }
            }
        }

        override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: Matrix) =
            draw(canvas, stroke, strokeToScreenTransform, 0f)

        override fun draw(canvas: Canvas, stroke: Stroke, strokeToScreenTransform: Matrix, textureAnimationProgress: Float) {
            val local = geometry(stroke)
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
