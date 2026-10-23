package jp.handwriting.note.editor

import androidx.ink.geometry.*
import androidx.ink.geometry.Intersection.intersects

internal object CircularEraser {
    fun hits(mesh: PartitionedMesh, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float): Boolean {
        if (ImmutableVec(x0, y0).intersects(mesh, AffineTransform.IDENTITY) ||
            ImmutableSegment(ImmutableVec(x0, y0), ImmutableVec(x1, y1)).intersects(mesh, AffineTransform.IDENTITY)) return true
        val point = MutableVec()
        val radiusSquared = radius * radius
        for (group in 0 until mesh.getRenderGroupCount()) for (outline in 0 until mesh.getOutlineCount(group)) {
            val count = mesh.getOutlineVertexCount(group, outline)
            if (count == 0) continue
            mesh.populateOutlinePosition(group, outline, 0, point)
            val firstX = point.x; val firstY = point.y
            var previousX = firstX; var previousY = firstY
            for (i in 1..count) {
                if (i == count) { point.x = firstX; point.y = firstY }
                else mesh.populateOutlinePosition(group, outline, i, point)
                if (SweepGeometry.segmentDistanceSquared(x0, y0, x1, y1, previousX, previousY, point.x, point.y) <= radiusSquared) return true
                previousX = point.x; previousY = point.y
            }
        }
        return false
    }
}
