package jp.handwriting.note.editor

/** Retention applies to refined stroke geometry; render lists still cover every visible stroke. */
internal enum class StrokeCacheMode {
    LIMITED, VISIBLE, NEARBY, ALL;
}
