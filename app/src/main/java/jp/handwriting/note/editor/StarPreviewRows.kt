package jp.handwriting.note.editor

/** Full-width strips in document order; nearby stars share one strip. */
internal object StarPreviewRows {
    data class Row(val top: Float, val bottom: Float)

    fun group(stars: List<StrokeIndex.Bounds>): List<Row> {
        val rows = mutableListOf<Row>()
        for (star in stars.sortedBy { it.top }) {
            val previous = rows.lastOrNull()
            val tolerance = maxOf(8f, minOf(star.bottom - star.top,
                previous?.let { it.bottom - it.top } ?: 0f) * 0.25f)
            if (previous != null && star.top <= previous.bottom + tolerance) {
                rows[rows.lastIndex] = Row(previous.top, maxOf(previous.bottom, star.bottom))
            } else rows.add(Row(star.top, star.bottom))
        }
        return rows.mapIndexed { index, row ->
            val padding = maxOf(24f, (row.bottom - row.top) * 0.4f)
            // Neighbouring rows keep their own ink without rendering the same gap twice.
            val topLimit = if (index == 0) 0f else (rows[index - 1].bottom + row.top) / 2f
            val bottomLimit = rows.getOrNull(index + 1)?.let { (row.bottom + it.top) / 2f } ?: Float.MAX_VALUE
            Row(maxOf(topLimit, row.top - padding), minOf(bottomLimit, row.bottom + padding))
        }
    }
}
