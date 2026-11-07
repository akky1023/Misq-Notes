package jp.handwriting.note.storage

/** A fixed Format 3 revision used while a viewport is loaded in batches. */
internal class SpatialNoteSource(private val store: ImmutableNoteStore, private val root: ImmutableNoteStore.Root,
    val recovered: Boolean = false) {
    val manifest = store.manifest(root)
    val revisionId get() = root.id
    val bottom get() = root.bottom
    fun pending(top: Float, bottom: Float, loaded: Set<Long>) = store.pending(root, top, bottom, loaded)
    fun <T> load(top: Float, bottom: Float, loaded: Set<Long>, checkpoint: () -> Unit = {}, decode: (StrokeRef, ByteArray) -> T): List<T> =
        store.visible(root, top, bottom, loaded, checkpoint).map { decode(it.first, it.second) }
    fun <T> next(top: Float, bottom: Float, loaded: Set<Long>, checkpoint: () -> Unit, decode: (StrokeRef, ByteArray) -> T): List<T> =
        store.visible(root, top, bottom, loaded, checkpoint, onePage = true).map { decode(it.first, it.second) }
}
