package jp.handwriting.note.editor

import jp.handwriting.note.AppMessages
import jp.handwriting.note.R

import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.Box
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import jp.handwriting.note.storage.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

enum class EditorTool { PEN, HIGHLIGHTER, ERASER, LASSO }
data class EditorUi(
    val tool: EditorTool = EditorTool.PEN,
    val temporaryEraser: Boolean = false,
    val temporaryLasso: Boolean = false,
    val color: Int = 0xFF202B33.toInt(),
    val size: Float = PenStyle.displayWidth(3f),
    val selectionCount: Int = 0,
    val strokes: Int = 0,
    val busy: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val contentRevision: Long = 0,
)
class EditorStroke private constructor(val inkOrNull: Stroke?, val image: ImageRecord?, val storageId: String) {
    constructor(ink: Stroke, storageId: String = UUID.randomUUID().toString()) : this(ink, null, storageId)
    constructor(image: ImageRecord, storageId: String = UUID.randomUUID().toString()) : this(null, image, storageId)
    val ink: Stroke get() = checkNotNull(inkOrNull)
    val bounds: Box? = image?.let { androidx.ink.geometry.ImmutableBox.fromTwoPoints(
        androidx.ink.geometry.ImmutableVec(it.left, it.top), androidx.ink.geometry.ImmutableVec(it.left + it.width, it.top + it.height))
    } ?: inkOrNull?.shape?.computeBoundingBox()
    internal fun translated(dx: Float, dy: Float): EditorStroke {
        image?.let { return EditorStroke(it.translated(dx, dy)) }
        val moved = MutableStrokeInputBatch()
        for (i in 0 until ink.inputs.size) {
            val p = ink.inputs[i]
            require((p.x + dx).isFinite() && (p.y + dy).isFinite())
            moved.add(p.toolType, p.x + dx, p.y + dy, p.elapsedTimeMillis,
                p.strokeUnitLengthCm, p.pressure, p.tiltRadians, p.orientationRadians)
        }
        return EditorStroke(Stroke(ink.brush, moved))
    }
}
data class WorkspaceUi(
    val current: NoteInfo? = null, val notes: List<NoteSummary> = emptyList(),
    val working: Boolean = false, val saving: Boolean = false, val error: String? = null,
    val recovered: Boolean = false,
    val saveFailed: Boolean = false,
    val syncing: Boolean = false, val syncError: String? = null,
)

internal data class PaletteUi(val colors: List<Int> = SavedColors.defaults, val saving: Boolean = false, val error: String? = null)
internal data class ToolPreferencesUi(val value: ToolPreferences = ToolPreferences(), val saving: Boolean = false, val error: String? = null)
internal data class LibraryUi(val value: LibraryPreferences = LibraryPreferences(), val saving: Boolean = false, val error: String? = null)
internal data class SelectionOverlay(val left: Float, val top: Float, val right: Float, val bottom: Float, val enabled: Boolean, val canCapture: Boolean = true)
internal data class StorageLocationUi(val label: String = AppMessages.text(R.string.storage_default), val custom: Boolean = false)

class EditorViewModel : ViewModel() {
    val edits = OrderedEdits<EditorStroke>()
    val penButton = PenButtonState()
    private val mutableUi = MutableStateFlow(EditorUi())
    val ui = mutableUi.asStateFlow()
    private val mutableWorkspace = MutableStateFlow(WorkspaceUi(working = true))
    val workspace = mutableWorkspace.asStateFlow()
    private val mutablePalette = MutableStateFlow(PaletteUi())
    internal val palette = mutablePalette.asStateFlow()
    private var paletteSaves: SaveQueue<SavedPalettes>? = null
    private var savedPalettes = SavedPalettes()
    private var paletteTool = EditorTool.PEN
    private val mutablePreferences = MutableStateFlow(ToolPreferencesUi())
    internal val preferences = mutablePreferences.asStateFlow()
    private val mutableSelectionOverlay = MutableStateFlow<SelectionOverlay?>(null)
    internal val selectionOverlay = mutableSelectionOverlay.asStateFlow()
    private val mutableZoomPercent = MutableStateFlow(100)
    internal val zoomPercent = mutableZoomPercent.asStateFlow()
    private val mutableZoomInteraction = MutableStateFlow(0L)
    internal val zoomInteraction = mutableZoomInteraction.asStateFlow()
    private val mutablePenNavigationActive = MutableStateFlow(false)
    internal val penNavigationActive = mutablePenNavigationActive.asStateFlow()
    internal fun penNavigationActive(active: Boolean) { mutablePenNavigationActive.value = active }
    private var preferenceSaves: SaveQueue<ToolPreferences>? = null
    private val mutableLibrary = MutableStateFlow(LibraryUi())
    internal val library = mutableLibrary.asStateFlow()
    private var librarySaves: SaveQueue<LibraryPreferences>? = null
    internal val contentBottom get() = index.bottom
    private var penColor = mutableUi.value.color
    private var penSize = mutableUi.value.size
    private var selectionWorking = false
    private var selectionGesture = false
    internal var insertingSpace = false
        private set
    private val selection = linkedMapOf<Long, EditorStroke>()
    private var selectedStorageIds = emptySet<String>()
    internal var selectionOffset = WorldPoint(0f, 0f)
        private set
    internal var selectionBounds: StrokeIndex.Bounds? = null
        private set
    internal var selectionScale = 1f
        private set
    internal val resizableImage get() = selection.values.singleOrNull()?.image
    val viewport = Viewport()
    private val mutableVisibleRegion = MutableStateFlow(StrokeIndex.Bounds(0f, 0f, Viewport.WIDTH, 1000f))
    internal val visibleRegion = mutableVisibleRegion.asStateFlow()
    private val index = StrokeIndex<EditorStroke>()
    internal var canvasRevision = 0L
        private set
    private var repository: NoteRepository? = null
    private data class PendingSave(val info: NoteInfo, val nextId: Long, val entries: List<OrderedEdits.Entry<EditorStroke>>)
    private var saves: SaveQueue<PendingSave>? = null
    private var previewSaves: SaveQueue<NoteSnapshot>? = null
    private var restoring = false
    private var lastRevision = -1L
    private var starting = false
    private var storageDirectory: File? = null
    private var notebookStorage: NotebookStorage? = null
    private val mutableStorageLocation = MutableStateFlow(StorageLocationUi())
    internal val storageLocation = mutableStorageLocation.asStateFlow()
    internal fun configureStorage(context: android.content.Context) {
        if (notebookStorage == null) {
            notebookStorage = NotebookStorage(context.applicationContext, context.filesDir, viewModelScope)
            viewModelScope.launch { notebookStorage!!.syncState.collect { state ->
                val custom = notebookStorage!!.uri != null
                mutableWorkspace.value = mutableWorkspace.value.copy(syncing = custom && state.saving,
                    syncError = state.error?.takeIf { custom }?.let { AppMessages.text(R.string.save_failed, it) })
            } }
        }
        refreshStorageLocation()
    }
    private fun refreshStorageLocation() {
        notebookStorage?.let { mutableStorageLocation.value = StorageLocationUi(it.label, it.uri != null) }
        if (notebookStorage?.uri == null) mutableWorkspace.value = mutableWorkspace.value.copy(syncing = false, syncError = null)
    }
    private var closing = false
    private data class SaveStamp(val note: String, val revision: Long, val position: ViewPosition, val color: Int, val size: Float, val paper: NotePaper)
    private var lastStamp: SaveStamp? = null
    private var lastGeneration: Long? = null
    var onCanvasChange: (() -> Unit)? = null
    var brush: Brush = createBrush(mutableUi.value)
        private set
    private var fixedWidthBrush: Brush = createBrush(mutableUi.value, pressure = false)

    init {
        edits.onChange = ::publish
        edits.onContentChange = { added, removed ->
            canvasRevision++
            val selectedIds = selection.keys.toSet()
            removed.forEach { index.remove(it.id) }
            added.forEach { entry -> entry.value.bounds?.let { b ->
                index.add(entry.id, entry.value, StrokeIndex.Bounds(b.xMin, b.yMin, b.xMax, b.yMax))
            } }
            removed.forEach { if (it.id in selection) selection.remove(it.id) }
            added.filter { it.id in selectedIds }.forEach { selection[it.id] = it.value }
            refreshSelection()
        }
    }
    fun start(directory: File) {
        if (repository != null || starting) return
        storageDirectory = directory
        starting = true
        mutableWorkspace.value = mutableWorkspace.value.copy(working = true, error = null)
        viewModelScope.launch {
            try {
                val needsRemote = withContext(Dispatchers.IO) { notebookStorage?.initialize() == true }
                if (needsRemote) notebookStorage?.initializeRemote()
                if (notebookStorage?.recovered == true) mutableWorkspace.value = mutableWorkspace.value.copy(recovered = true)
                repository = withContext(Dispatchers.IO) { NoteRepository(NoteFiles(File(directory, "notes")),
                    NoteListCache(File(directory, "notes"))) { action ->
                    notebookStorage?.write(action) ?: action()
                } }
                val libraryFile = LibraryPreferencesFile(File(directory, "library.hwp"))
                try { mutableLibrary.value = LibraryUi(withContext(Dispatchers.IO) { libraryFile.read() }) }
                catch (e: Exception) { mutableLibrary.value = LibraryUi(error = AppMessages.text(R.string.library_load_failed, e.localizedMessage)) }
                librarySaves = SaveQueue(viewModelScope) { value -> withContext(Dispatchers.IO) {
                    notebookStorage?.write { libraryFile.write(value) } ?: libraryFile.write(value)
                } }
                launch { librarySaves!!.state.collect { state ->
                    mutableLibrary.value = mutableLibrary.value.copy(saving = state.saving,
                        error = state.error?.let { AppMessages.text(R.string.library_save_failed, it) } ?: mutableLibrary.value.error?.takeIf { state.latest == 0L })
                } }
                val colors = SavedColors(File(directory, "palette.hwp"))
                try { savedPalettes = withContext(Dispatchers.IO) { colors.read() }; refreshPalette() }
                catch (e: Exception) { mutablePalette.value = PaletteUi(error = AppMessages.text(R.string.palette_load_failed, e.localizedMessage)) }
                paletteSaves = SaveQueue(viewModelScope) { values -> withContext(Dispatchers.IO) {
                    notebookStorage?.write { colors.write(values) } ?: colors.write(values)
                } }
                launch { paletteSaves!!.state.collect { state ->
                    mutablePalette.value = mutablePalette.value.copy(saving = state.saving,
                        error = state.error?.let { AppMessages.text(R.string.palette_save_failed, it) } ?: mutablePalette.value.error?.takeIf { state.latest == 0L })
                } }
                val preferencesFile = ToolPreferencesFile(File(directory, "tools.hwp"))
                try { mutablePreferences.value = ToolPreferencesUi(withContext(Dispatchers.IO) { preferencesFile.read() }) }
                catch (e: Exception) { mutablePreferences.value = ToolPreferencesUi(error = AppMessages.text(R.string.widths_load_failed, e.localizedMessage)) }
                preferenceSaves = SaveQueue(viewModelScope) { value -> withContext(Dispatchers.IO) {
                    notebookStorage?.write { preferencesFile.write(value) } ?: preferencesFile.write(value)
                } }
                launch { preferenceSaves!!.state.collect { state ->
                    mutablePreferences.value = mutablePreferences.value.copy(saving = state.saving,
                        error = state.error?.let { AppMessages.text(R.string.settings_save_failed, it) } ?: mutablePreferences.value.error?.takeIf { state.latest == 0L })
                } }
                previewSaves = SaveQueue(viewModelScope, batchMillis = 1000) { snapshot -> withContext(Dispatchers.IO) {
                    repository!!.updatePreview(snapshot)
                } }
                saves = SaveQueue(viewModelScope, batchMillis = 500) { request -> withContext(Dispatchers.IO) {
                    val refs = request.entries.map { StrokeRef(it.id, it.value.storageId) }
                    val snapshot = NoteSnapshot(NoteManifest(request.info, refs, request.nextId), request.entries)
                    repository!!.save(snapshot)
                    previewSaves?.request(snapshot)
                } }
                launch { saves!!.state.collect { state ->
                    mutableWorkspace.value = mutableWorkspace.value.copy(saving = state.saving, saveFailed = state.error != null,
                        error = if (state.error != null) AppMessages.text(R.string.save_failed, state.error) else mutableWorkspace.value.error?.takeUnless { mutableWorkspace.value.saveFailed })
                } }
                refreshList()
                notebookStorage?.startBackground()
            } catch (e: Exception) { fail(e) }
            finally { starting = false }
        }
    }
    private suspend fun refreshList() {
        val notes = withContext(Dispatchers.IO) { repository!!.list() }
        val ids = notes.map { it.id }.toSet()
        val before = mutableLibrary.value.value
        val locations = before.locations.filterKeys { it in ids }
        val orders = before.orders.mapValues { (folder, order) -> order.filter { it in ids && locations[it].orEmpty() == folder } }
        if (locations != before.locations || orders != before.orders) updateLibrary(before.copy(locations = locations, orders = orders))
        mutableWorkspace.value = mutableWorkspace.value.copy(notes = notes, working = false)
    }
    internal fun changeStorage(uri: android.net.Uri?) {
        if (mutableWorkspace.value.working || mutableWorkspace.value.current != null) return
        val storage = notebookStorage ?: return
        mutableWorkspace.value = mutableWorkspace.value.copy(working = true, error = null)
        viewModelScope.launch {
            try {
                librarySaves?.let { check(it.await(it.state.value.latest) || uri == null) { AppMessages.text(R.string.wait_library) } }
                saves?.let { check(it.await(it.state.value.latest) || uri == null) { AppMessages.text(R.string.wait_note) } }
                paletteSaves?.let { check(it.await(it.state.value.latest) || uri == null) { AppMessages.text(R.string.wait_palette) } }
                preferenceSaves?.let { check(it.await(it.state.value.latest) || uri == null) { AppMessages.text(R.string.wait_pen) } }
                if (uri != null && !storage.blockedByRemoteChange) check(storage.awaitSync()) { AppMessages.text(R.string.wait_note) }
                withContext(Dispatchers.IO) { storage.change(uri) }
                if (storage.recovered) mutableWorkspace.value = mutableWorkspace.value.copy(recovered = true)
                refreshStorageLocation()
                if (uri == null) {
                    librarySaves?.let { queue -> if (queue.state.value.error != null) {
                        queue.retry(); check(queue.await(queue.state.value.latest)) { AppMessages.text(R.string.retry_library) }
                    } }
                    saves?.let { queue -> if (queue.state.value.error != null) {
                        queue.retry(); check(queue.await(queue.state.value.latest)) { AppMessages.text(R.string.retry_note) }
                    } }
                    paletteSaves?.let { queue -> if (queue.state.value.error != null) {
                        queue.retry(); check(queue.await(queue.state.value.latest)) { AppMessages.text(R.string.retry_palette) }
                    } }
                    preferenceSaves?.let { queue -> if (queue.state.value.error != null) {
                        queue.retry(); check(queue.await(queue.state.value.latest)) { AppMessages.text(R.string.retry_pen) }
                    } }
                }
                if (repository == null) { mutableWorkspace.value = mutableWorkspace.value.copy(working = false); storageDirectory?.let(::start) }
                else {
                    val value = withContext(Dispatchers.IO) { LibraryPreferencesFile(File(storageDirectory!!, "library.hwp")).read() }
                    mutableLibrary.value = mutableLibrary.value.copy(value = value)
                    val imported = withContext(Dispatchers.IO) { SavedColors(File(storageDirectory!!, "palette.hwp")).read() to ToolPreferencesFile(File(storageDirectory!!, "tools.hwp")).read() }
                    savedPalettes = imported.first; refreshPalette()
                    mutablePreferences.value = mutablePreferences.value.copy(value = imported.second)
                    refreshList()
                }
            } catch (e: Exception) { fail(e) }
        }
    }
    fun createNote(title: String, folder: String? = null) {
        if (mutableWorkspace.value.working || repository == null) return
        mutableWorkspace.value = mutableWorkspace.value.copy(working = true, error = null)
        viewModelScope.launch {
            try {
                val manifest = withContext(Dispatchers.IO) { repository!!.create(title.trim().ifEmpty { AppMessages.text(R.string.untitled) }, mutableLibrary.value.value.paper) }
                if (folder != null) {
                    updateLibrary(mutableLibrary.value.value.moved(manifest.info.id, folder))
                    librarySaves?.let { check(it.await(it.state.value.latest)) { AppMessages.text(R.string.folder_save_failed) } }
                }
                enter(LoadedNote(manifest, emptyList(), false))
            } catch (e: Exception) { fail(e) }
        }
    }
    fun openNote(id: String) {
        if (mutableWorkspace.value.working || repository == null) return
        mutableWorkspace.value = mutableWorkspace.value.copy(working = true, error = null)
        viewModelScope.launch {
            try { enter(withContext(Dispatchers.IO) { repository!!.load(id) }) }
            catch (e: Exception) { fail(e) }
        }
    }
    private fun enter(note: LoadedNote) {
        restoring = true
        resetButton()
        viewport.restore(note.manifest.info.position)
        clearSelection()
        penColor = note.manifest.info.color; penSize = PenStyle.displayWidth(note.manifest.info.size)
        paletteTool = EditorTool.PEN; refreshPalette()
        mutableUi.value = EditorUi(color = penColor, size = penSize)
        brush = createBrush(mutableUi.value); fixedWidthBrush = createBrush(mutableUi.value, pressure = false)
        edits.restore(note.entries, note.manifest.nextId)
        lastRevision = edits.revision
        mutableWorkspace.value = mutableWorkspace.value.copy(current = note.manifest.info, working = false, error = null, recovered = note.recovered)
        lastStamp = if (note.recovered) null else SaveStamp(note.manifest.info.id, edits.revision, viewport.position, mutableUi.value.color, mutableUi.value.size, note.manifest.info.paper)
        lastGeneration = null
        restoring = false
        publish()
    }
    fun closeNote() {
        if (mutableWorkspace.value.working) return
        closing = true
        mutableWorkspace.value = mutableWorkspace.value.copy(working = true)
        viewModelScope.launch {
            try {
                check(!mutableUi.value.busy) { AppMessages.text(R.string.wait_stroke_back) }
                val generation = enqueueSave(force = true, allowClosing = true)
                if (generation != null && !saves!!.await(generation)) {
                    mutableWorkspace.value = mutableWorkspace.value.copy(working = false)
                    closing = false
                    return@launch
                }
                preferenceSaves?.let { queue -> if (queue.state.value.latest > 0) queue.await(queue.state.value.latest) }
                paletteSaves?.let { queue -> if (queue.state.value.latest > 0) queue.await(queue.state.value.latest) }
                restoring = true
                mutableWorkspace.value = mutableWorkspace.value.copy(current = null, recovered = false)
                closing = false
                edits.restore(emptyList()); restoring = false
                refreshList()
            } catch (e: Exception) { restoring = false; closing = false; fail(e) }
        }
    }
    fun renameNote(id: String, title: String) = listAction { repository!!.rename(id, title.trim().ifEmpty { AppMessages.text(R.string.untitled) }) }
    fun deleteNote(id: String) = listAction { repository!!.delete(id) }
    private fun listAction(action: () -> Unit) {
        if (mutableWorkspace.value.working || mutableWorkspace.value.current != null) return
        mutableWorkspace.value = mutableWorkspace.value.copy(working = true, error = null)
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { action() }; refreshList() } catch (e: Exception) { fail(e) }
        }
    }
    private fun fail(e: Exception) { mutableWorkspace.value = mutableWorkspace.value.copy(working = false, error = e.localizedMessage ?: AppMessages.text(R.string.operation_failed)) }
    fun clearError() { mutableWorkspace.value = mutableWorkspace.value.copy(error = null, recovered = false) }
    fun retrySave() {
        viewModelScope.launch {
            try { withContext(Dispatchers.IO) { notebookStorage?.retryPending() }; enqueueSave(force = true) }
            catch (e: Exception) { fail(e) }
        }
    }
    fun retry() {
        if (mutableWorkspace.value.current != null) retrySave()
        else if (repository == null) storageDirectory?.let(::start)
        else {
            mutableWorkspace.value = mutableWorkspace.value.copy(working = true, error = null)
            viewModelScope.launch { try { withContext(Dispatchers.IO) { notebookStorage?.retryPending() }; refreshList() } catch (e: Exception) { fail(e) } }
        }
    }
    fun viewportChanged() { updateViewportOverlay(); enqueueSave(force = true) }
    internal fun moveViewportFromPreview(left: Float, top: Float) {
        if (mutableUi.value.busy || mutableWorkspace.value.working || !left.isFinite() || !top.isFinite()) return
        viewport.restore(viewport.position.copy(left = left.coerceAtLeast(0f), top = top.coerceAtLeast(0f)))
        updateViewportOverlay()
        onCanvasChange?.invoke()
    }
    internal fun transformViewport(oldX: Float, oldY: Float, newX: Float, newY: Float, factor: Float) {
        val zoom = viewport.position.zoom
        viewport.transform(oldX, oldY, newX, newY, factor)
        if (viewport.position.zoom != zoom) { clearSelection(); mutableZoomInteraction.value++ }
    }
    fun fitWidth() {
        val zoom = viewport.position.zoom
        viewport.fitWidth()
        if (viewport.position.zoom != zoom) { clearSelection(); mutableZoomInteraction.value++ }
        onCanvasChange?.invoke(); viewportChanged()
    }
    fun visibleStrokes(): List<EditorStroke> = visibleStrokeEntries().map { it.second }
    internal fun visibleStrokeEntries(): List<Pair<Long, EditorStroke>> {
        val visible = index.query(StrokeIndex.Bounds(viewport.position.left, viewport.position.top, viewport.right, viewport.bottom))
        if (selection.isEmpty()) return visible
        if (insertingSpace) {
            val shifted = index.query(StrokeIndex.Bounds(viewport.position.left, viewport.position.top - selectionOffset.y,
                viewport.right, viewport.bottom - selectionOffset.y)).filter { it.first in selection }
            return (visible + shifted).distinctBy { it.first }.sortedBy { it.first }
        }
        return (visible + selection.map { it.key to it.value }).distinctBy { it.first }.sortedBy { it.first }
    }
    internal fun cachedStrokeEntries(mode: StrokeCacheMode): List<Pair<Long, EditorStroke>> = when (mode) {
        StrokeCacheMode.ALL -> edits.snapshot().map { it.id to it.value }
        StrokeCacheMode.NEARBY -> {
            val height = viewport.bottom - viewport.position.top
            index.query(StrokeIndex.Bounds(viewport.position.left, (viewport.position.top - height).coerceAtLeast(0f),
                viewport.right, viewport.bottom + height))
        }
        else -> visibleStrokeEntries()
    }
    internal val selectionComputing get() = selectionWorking
    private fun enqueueSave(force: Boolean = false, allowClosing: Boolean = false): Long? {
        val queue = saves ?: return null
        val info = mutableWorkspace.value.current ?: return null
        if (restoring || (closing && !allowClosing) || edits.busy || (!force && lastRevision == edits.revision)) return null
        val stamp = SaveStamp(info.id, edits.revision, viewport.position, penColor, penSize, info.paper)
        if (stamp == lastStamp && queue.state.value.error == null) return if (force) lastGeneration else null
        val entries = edits.snapshot()
        val updated = info.copy(updatedAt = System.currentTimeMillis(), position = viewport.position,
            color = penColor, size = PenStyle.brushWidth(penSize))
        mutableWorkspace.value = mutableWorkspace.value.copy(current = updated, saving = true)
        lastRevision = edits.revision
        lastStamp = stamp
        return queue.request(PendingSave(updated, edits.nextEntryId, entries)).also { lastGeneration = it }
    }

    internal suspend fun importDocument(context: android.content.Context, uri: android.net.Uri, noteId: String,
        selectPages: suspend (Int) -> List<Int> = { (0 until it).toList() }, onCommit: () -> Unit = {}) {
        check(!mutableUi.value.busy && !mutableWorkspace.value.working)
        val note = checkNotNull(mutableWorkspace.value.current)
        check(note.id == noteId)
        val images = withContext(Dispatchers.IO) { EmbeddedImages.read(context.applicationContext, uri, selectPages) }
        currentCoroutineContext().ensureActive()
        check(mutableWorkspace.value.current?.id == note.id && !edits.busy && !selectionWorking)
        clearSelection()
        val top = maxOf(viewport.position.top, if (edits.snapshot().isEmpty()) 0f else contentBottom + 24f)
        var y = top
        val content = images.map { data ->
            val height = Viewport.WIDTH * data.height / data.width
            EditorStroke(ImageRecord(data, 0f, y, Viewport.WIDTH, height)).also { y += height + 24f }
        }
        onCommit()
        edits.addAll(content)
        viewport.restore(ViewPosition(top = top))
        updateViewportOverlay(); onCanvasChange?.invoke()
        val generation = enqueueSave(force = true)
        withContext(NonCancellable) {
            check(generation != null && checkNotNull(saves).await(generation)) { AppMessages.text(R.string.import_save_failed) }
        }
    }

    fun chooseTool(tool: EditorTool) {
        if (mutableUi.value.busy) return
        val prefs = mutablePreferences.value.value
        mutableUi.value = when (tool) {
            EditorTool.PEN -> mutableUi.value.copy(tool = tool, color = penColor, size = penSize)
            EditorTool.HIGHLIGHTER -> mutableUi.value.copy(tool = tool, color = prefs.highlighterColor, size = prefs.highlighterSize.toFloat())
            EditorTool.ERASER -> mutableUi.value.copy(tool = tool, size = prefs.eraserSize.toFloat())
            else -> mutableUi.value.copy(tool = tool)
        }
        if (tool == EditorTool.PEN || tool == EditorTool.HIGHLIGHTER) { paletteTool = tool; refreshPalette() }
        brush = createBrush(mutableUi.value); fixedWidthBrush = createBrush(mutableUi.value, pressure = false)
        onCanvasChange?.invoke()
    }
    fun style(color: Int = mutableUi.value.color, size: Float = mutableUi.value.size) {
        if (!mutableUi.value.busy && mutableUi.value.tool == EditorTool.ERASER) {
            val width = PenStyle.width(size)
            mutableUi.value = mutableUi.value.copy(size = width)
            updatePreferences(mutablePreferences.value.value.copy(eraserSize = width.toInt()))
            onCanvasChange?.invoke(); return
        }
        if (mutableUi.value.busy || mutableUi.value.tool !in listOf(EditorTool.PEN, EditorTool.HIGHLIGHTER)) return
        mutableUi.value = mutableUi.value.copy(color = color, size = PenStyle.width(size))
        if (mutableUi.value.tool == EditorTool.PEN) { penColor = color; penSize = mutableUi.value.size }
        else updatePreferences(mutablePreferences.value.value.copy(highlighterColor = color, highlighterSize = mutableUi.value.size.toInt()))
        brush = createBrush(mutableUi.value)
        fixedWidthBrush = createBrush(mutableUi.value, pressure = false)
        enqueueSave(force = true)
    }
    internal fun chooseRgb(rgb: Int) = style(color = PenStyle.withRgb(mutableUi.value.color, rgb))
    internal fun transparency(percent: Int) = style(color = PenStyle.withTransparency(mutableUi.value.color, percent))
    internal fun saveColor(rgb: Int) {
        val colors = (mutablePalette.value.colors + PenStyle.rgb(rgb)).distinct()
        updatePalette(colors)
    }
    internal fun removeColor(rgb: Int) {
        val colors = mutablePalette.value.colors - PenStyle.rgb(rgb)
        updatePalette(colors)
    }
    private fun updatePalette(colors: List<Int>) {
        savedPalettes = if (paletteTool == EditorTool.HIGHLIGHTER) savedPalettes.copy(highlighter = colors) else savedPalettes.copy(pen = colors)
        refreshPalette(); paletteSaves?.request(savedPalettes)
    }
    private fun refreshPalette() {
        mutablePalette.value = mutablePalette.value.copy(colors = if (paletteTool == EditorTool.HIGHLIGHTER) savedPalettes.highlighter else savedPalettes.pen)
    }
    internal fun retryPalette() { paletteSaves?.request(savedPalettes) }
    internal fun saveWidth(slot: Int, width: Int) {
        require(slot in 0..2 && width in 1..50)
        val prefs = mutablePreferences.value.value
        val widths = prefs.widthsFor(mutableUi.value.tool).mapIndexed { i, old -> if (i == slot) width else old }
        val updated = when (mutableUi.value.tool) {
            EditorTool.PEN -> prefs.copy(widths = widths)
            EditorTool.HIGHLIGHTER -> prefs.copy(highlighterWidths = widths)
            EditorTool.ERASER -> prefs.copy(eraserWidths = widths)
            EditorTool.LASSO -> return
        }
        updatePreferences(updated)
    }
    internal fun pressureCurve(curve: PressureCurve) {
        if (mutableUi.value.busy) return
        updatePreferences(mutablePreferences.value.value.copy(pressureCurve = curve))
        brush = createBrush(mutableUi.value); fixedWidthBrush = createBrush(mutableUi.value, pressure = false)
    }
    internal fun toolOrder(order: List<EditorTool>) = updatePreferences(mutablePreferences.value.value.copy(toolOrder = order))
    internal fun notePaper(paper: NotePaper) {
        val info = mutableWorkspace.value.current ?: return
        mutableWorkspace.value = mutableWorkspace.value.copy(current = info.copy(paper = paper))
        onCanvasChange?.invoke(); enqueueSave(force = true)
    }
    internal fun defaultPaper(paper: NotePaper) = updateLibrary(mutableLibrary.value.value.copy(paper = paper))
    internal fun suppressPenGestures(enabled: Boolean) = updateLibrary(mutableLibrary.value.value.copy(suppressPenGestures = enabled))
    internal fun listGrid(grid: Boolean) = updateLibrary(mutableLibrary.value.value.copy(grid = grid))
    internal fun previewOnRight(right: Boolean) = updateLibrary(mutableLibrary.value.value.copy(previewOnRight = right))
    internal fun previewLayout(widthDp: Float, position: Float) {
        val before = mutableLibrary.value.value
        val after = before.copy(previewWidthDp = widthDp, previewHandlePosition = position)
        if (after != before) updateLibrary(after)
    }
    internal fun strokeCache(mode: StrokeCacheMode) {
        updateLibrary(mutableLibrary.value.value.copy(strokeCache = mode))
        onCanvasChange?.invoke()
    }
    internal fun theme(mode: jp.handwriting.note.storage.AppThemeMode) = updateLibrary(mutableLibrary.value.value.copy(theme = mode))
    internal fun amoled(enabled: Boolean) = updateLibrary(mutableLibrary.value.value.copy(amoled = enabled))
    internal suspend fun thumbnail(id: String): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        runCatching { repository?.preview(id)?.let { android.graphics.BitmapFactory.decodeFile(it.path) } }.getOrNull()
    }
    internal fun createFolder(name: String, parent: String?) = updateLibrary(mutableLibrary.value.value.added(name.trim().ifEmpty { AppMessages.text(R.string.new_folder) }, parent))
    internal fun renameFolder(id: String, name: String) = updateLibrary(mutableLibrary.value.value.let { value ->
        value.copy(folders = value.folders.map { if (it.id == id) it.copy(name = NoteFiles.validTitle(name.trim().ifEmpty { AppMessages.text(R.string.new_folder) })) else it })
    })
    internal fun deleteFolder(id: String) { runCatching { updateLibrary(mutableLibrary.value.value.removedFolder(id)) }.onFailure { fail(it as Exception) } }
    internal fun moveNote(id: String, folder: String?) = updateLibrary(mutableLibrary.value.value.moved(id, folder))
    internal fun sortNotes(folder: String?, sort: jp.handwriting.note.storage.NoteSort) = updateLibrary(mutableLibrary.value.value.let { it.copy(sorts = it.sorts + (folder.orEmpty() to sort)) })
    internal fun reorderNotes(folder: String?, ids: List<String>) = updateLibrary(mutableLibrary.value.value.reordered(folder, ids))
    internal fun folderColor(id: String, rgb: Int) = updateLibrary(mutableLibrary.value.value.let { value ->
        value.copy(folders = value.folders.map { if (it.id == id) it.copy(color = rgb or 0xFF000000.toInt()) else it })
    })
    internal fun retryLibrary() { librarySaves?.request(mutableLibrary.value.value) }
    private fun updateLibrary(value: LibraryPreferences) {
        mutableLibrary.value = mutableLibrary.value.copy(value = value)
        librarySaves?.request(value)
    }
    internal val eraserRadius get() = mutablePreferences.value.value.eraserSize / 2f
    internal fun retryPreferences() { preferenceSaves?.request(mutablePreferences.value.value) }
    private fun updatePreferences(value: ToolPreferences) {
        mutablePreferences.value = mutablePreferences.value.copy(value = value)
        preferenceSaves?.request(value)
    }
    internal fun chooseLassoMode(mode: LassoMode) {
        if (!mutableUi.value.busy) updatePreferences(mutablePreferences.value.value.copy(lassoMode = mode))
    }
    internal fun pinchResizeMedia(enabled: Boolean) = updatePreferences(mutablePreferences.value.value.copy(pinchResizeMedia = enabled))
    internal fun lassoIncludeMedia(enabled: Boolean) {
        if (!mutableUi.value.busy) updatePreferences(mutablePreferences.value.value.copy(lassoIncludeMedia = enabled))
    }
    internal fun isSelected(stroke: EditorStroke) = stroke.storageId in selectedStorageIds
    private fun refreshSelection() {
        canvasRevision++
        selectedStorageIds = selection.values.map { it.storageId }.toSet()
        val bounds = selection.values.mapNotNull { it.bounds }
        selectionBounds = if (bounds.isEmpty()) null else StrokeIndex.Bounds(
            bounds.minOf { it.xMin }, bounds.minOf { it.yMin }, bounds.maxOf { it.xMax }, bounds.maxOf { it.yMax })
    }
    internal fun clearSelection() {
        selection.clear(); selectionOffset = WorldPoint(0f, 0f); selectionScale = 1f; refreshSelection()
        mutableUi.value = mutableUi.value.copy(selectionCount = 0)
        updateSelectionOverlay()
        onCanvasChange?.invoke()
    }
    /** Resolve before routing DOWN, so an outside contact also starts drawing or panning. */
    internal fun beginCanvasContact(x: Float, y: Float): Boolean {
        if (SelectionHitTest.contains(selectionBounds, selectionOffset, x, y, viewport.scale)) return true
        if (selectionBounds != null) {
            penButton.dismissLasso()
            clearSelection()
            publish()
        }
        return false
    }
    internal fun beginSelectionGesture() { selectionGesture = true; publish() }
    internal fun selectImportedImage(x: Float, y: Float): Boolean {
        if (mutableUi.value.busy || selectionWorking || insertingSpace) return false
        val hit = index.query(StrokeIndex.Bounds(x, y, x, y)).lastOrNull { (_, stroke) ->
            stroke.image?.let { x in it.left..(it.left + it.width) && y in it.top..(it.top + it.height) } == true
        } ?: return false
        clearSelection()
        selection[hit.first] = hit.second
        refreshSelection(); publish()
        return true
    }
    internal fun previewImageResize(factor: Float) {
        val image = resizableImage ?: return
        if (!factor.isFinite() || selectionWorking) return
        val maximum = ((Viewport.WIDTH - image.left - selectionOffset.x) / image.width).coerceAtLeast(0.01f)
        val minimum = minOf(maximum, 24f / minOf(image.width, image.height))
        selectionScale = factor.coerceIn(minimum, maximum)
        updateSelectionOverlay(); onCanvasChange?.invoke()
    }
    internal fun previewImagePinch(factor: Float, center: WorldPoint) {
        val image = resizableImage ?: return
        if (!factor.isFinite() || !center.x.isFinite() || !center.y.isFinite() || selectionWorking) return
        // Clamp the size at the page edges without shifting the gesture's fixed image center.
        val maximum = minOf(2f * center.x / image.width, 2f * (Viewport.WIDTH - center.x) / image.width,
            2f * center.y / image.height).coerceAtLeast(0.01f)
        val minimum = minOf(maximum, 24f / minOf(image.width, image.height))
        selectionScale = factor.coerceIn(minimum, maximum)
        val resized = image.resizedFromCenter(selectionScale, center.x, center.y)
        selectionOffset = WorldPoint(resized.left - image.left, resized.top - image.top)
        updateSelectionOverlay(); onCanvasChange?.invoke()
    }
    internal fun selectLasso(points: List<WorldPoint>) {
        if (edits.busy || selectionWorking) return
        selectionGesture = false
        val polygon = LassoGeometry(points)
        if (!polygon.valid) { clearSelection(); publish(); return }
        val candidates = index.query(StrokeIndex.Bounds(points.minOf { it.x }, points.minOf { it.y }, points.maxOf { it.x }, points.maxOf { it.y }))
        val includeMedia = mutablePreferences.value.value.lassoIncludeMedia
        selectionWorking = true; publish()
        viewModelScope.launch {
            try {
                val selected = withContext(Dispatchers.Default) { candidates.filter { (_, stroke) ->
                    stroke.image?.let { image ->
                        if (!includeMedia) return@filter false
                        val corners = listOf(WorldPoint(image.left, image.top), WorldPoint(image.left + image.width, image.top),
                            WorldPoint(image.left + image.width, image.top + image.height), WorldPoint(image.left, image.top + image.height))
                        return@filter corners.all(polygon::contains)
                    }
                    val input = stroke.ink.inputs
                    var previous: WorldPoint? = null
                    (0 until input.size).any { i ->
                        val p = input[i]; val current = WorldPoint(p.x, p.y)
                        val hit = previous?.let { polygon.intersects(it, current) } ?: polygon.contains(current)
                        previous = current; hit
                    }
                } }
                selection.clear(); selected.forEach { (id, value) -> selection[id] = value }; refreshSelection()
            } catch (e: Exception) { fail(e) }
            finally { selectionWorking = false; publish() }
        }
    }
    internal fun previewSelectionMove(dx: Float, dy: Float) {
        val b = selectionBounds ?: return
        if (!dx.isFinite() || !dy.isFinite() || selectionWorking) return
        // Keep an edge-crossing legacy stroke where it is on a tap; a drag cannot
        // push it farther outside the writable area.
        selectionOffset = WorldPoint(dx.coerceIn(minOf(0f, -b.left), maxOf(0f, Viewport.WIDTH - b.right)), dy.coerceAtLeast(minOf(0f, -b.top)))
        updateSelectionOverlay()
        onCanvasChange?.invoke()
    }
    internal fun cancelSelectionMove() { selectionOffset = WorldPoint(0f, 0f); selectionScale = 1f; selectionGesture = false; publish() }
    internal fun beginSpaceInsertion(y: Float): Boolean {
        if (mutableUi.value.busy || !y.isFinite()) return false
        clearSelection()
        // A stroke crossing the insertion line moves as a whole; never cut handwriting.
        edits.snapshot().filter { (it.value.bounds?.yMax ?: Float.NEGATIVE_INFINITY) >= y }
            .forEach { selection[it.id] = it.value }
        refreshSelection()
        if (selection.isEmpty()) { publish(); return false }
        insertingSpace = true
        beginSelectionGesture()
        return true
    }
    internal fun cancelSpaceInsertion() {
        insertingSpace = false
        selectionGesture = false
        clearSelection()
        publish()
    }
    internal fun commitSelectionMove(clearAfter: Boolean = false, onFinished: (() -> Unit)? = null) {
        val delta = selectionOffset
        val factor = selectionScale
        if (edits.busy || selectionWorking) return
        selectionGesture = false
        if (selection.isEmpty() || (delta == WorldPoint(0f, 0f) && factor == 1f)) {
            if (clearAfter) cancelSpaceInsertion() else cancelSelectionMove()
            onFinished?.invoke()
            return
        }
        val before = selection.toMap()
        selectionWorking = true; publish()
        viewModelScope.launch {
            try {
                val moved = withContext(Dispatchers.Default) { before.mapValues { (_, stroke) ->
                    if (factor != 1f && stroke.image != null) EditorStroke(stroke.image.resized(factor).translated(delta.x, delta.y))
                    else stroke.translated(delta.x, delta.y)
                } }
                selectionOffset = WorldPoint(0f, 0f); selectionScale = 1f
                edits.replace(moved)
            } catch (e: Exception) { fail(e) }
            finally {
                selectionOffset = WorldPoint(0f, 0f); selectionScale = 1f; selectionWorking = false
                if (clearAfter) { insertingSpace = false; clearSelection() }
                publish()
                onFinished?.invoke()
            }
        }
    }
    internal fun deleteSelection() {
        if (mutableUi.value.busy) return
        edits.delete(selection.keys.toSet()); clearSelection()
    }
    internal fun selectedStrokes(): List<EditorStroke> {
        check(!mutableUi.value.busy) { AppMessages.text(R.string.wait_stroke_action) }
        return selection.toSortedMap().values.toList()
    }
    internal suspend fun exportSnapshot(id: String): NoteSnapshot {
        val info = mutableWorkspace.value.current
        if (info?.id == id) {
            check(!mutableUi.value.busy && !closing) { AppMessages.text(R.string.wait_stroke_export) }
            val entries = edits.snapshot()
            return NoteSnapshot(NoteManifest(info, entries.map { StrokeRef(it.id, it.value.storageId) }, edits.nextEntryId), entries)
        }
        return withContext(Dispatchers.IO) { checkNotNull(repository).load(id).let { NoteSnapshot(it.manifest, it.entries) } }
    }
    internal fun duplicateSelection() {
        if (mutableUi.value.busy || mutableWorkspace.value.current == null || selection.isEmpty()) return
        val strokes = selectedStrokes()
        val bounds = NoteExport.selectionBounds(strokes)
        val shift = 20f / viewport.scale
        val left = (bounds.left + shift).coerceIn(0f, maxOf(0f, Viewport.WIDTH - bounds.width()))
        val top = (bounds.top + shift).coerceAtLeast(0f)
        val pasted = strokes.map { it.translated(left - bounds.left, top - bounds.top) }
        val ids = edits.addAll(pasted)
        selection.clear(); ids.zip(pasted).forEach { (id, stroke) -> selection[id] = stroke }
        refreshSelection(); publish()
    }
    internal fun updateSelectionOverlay() {
        val bounds = selectionBounds
        if (bounds == null || insertingSpace) { mutableSelectionOverlay.value = null; return }
        val left = viewport.screenX(bounds.left + selectionOffset.x)
        val top = viewport.screenY(bounds.top + selectionOffset.y)
        val right = viewport.screenX(bounds.left + (bounds.right - bounds.left) * selectionScale + selectionOffset.x)
        val bottom = viewport.screenY(bounds.top + (bounds.bottom - bounds.top) * selectionScale + selectionOffset.y)
        mutableSelectionOverlay.value = if (right < 0 || bottom < 0 || left > viewport.width || top > viewport.height) null
            else SelectionOverlay(left, top, right, bottom, !mutableUi.value.busy, selection.values.none { it.image != null })
    }
    internal fun updateViewportOverlay() {
        mutableZoomPercent.value = viewport.zoomPercent
        mutableVisibleRegion.value = StrokeIndex.Bounds(viewport.position.left, viewport.position.top, viewport.right, viewport.bottom)
        updateSelectionOverlay()
    }
    fun brushForPressure(supported: Boolean) = if (supported) brush else fixedWidthBrush
    fun buttonChanged() = publish()
    internal fun buttonAction(button: PenButton, action: PenButtonAction) {
        resetButton()
        updatePreferences(mutablePreferences.value.value.let { it.copy(buttonActions = it.buttonActions.mapIndexed { index, old -> if (index == button.ordinal) action else old }) })
    }
    fun handlePenButton(device: Int, name: String?, key: Int, down: Boolean, repeat: Int): Boolean =
        penButton.handle(device, name, key, down, repeat, mutablePreferences.value.value.buttonActions) { action ->
            if (mutableWorkspace.value.current != null) when (action) {
                PenButtonAction.PEN -> chooseTool(EditorTool.PEN)
                PenButtonAction.HIGHLIGHTER -> chooseTool(EditorTool.HIGHLIGHTER)
                PenButtonAction.ERASER -> chooseTool(EditorTool.ERASER)
                PenButtonAction.LASSO -> chooseTool(EditorTool.LASSO)
                PenButtonAction.UNDO -> if (!mutableUi.value.busy) edits.undo()
                PenButtonAction.REDO -> if (!mutableUi.value.busy) edits.redo()
                PenButtonAction.FIT_WIDTH -> if (!mutableUi.value.busy) fitWidth()
                else -> Unit
            }
        }
    fun resetButton() { penButton.reset(); publish() }
    fun effectiveTool() = when {
        selectionBounds != null -> EditorTool.LASSO
        penButton.held -> EditorTool.ERASER
        penButton.lassoHeld -> EditorTool.LASSO
        else -> mutableUi.value.tool
    }

    fun erase(id: Long, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) {
        // Sweep between samples as well as through historical samples, avoiding gaps on fast moves.
        val padding = radius
        val left = min(x0, x1) - padding; val right = max(x0, x1) + padding
        val top = min(y0, y1) - padding; val bottom = max(y0, y1) + padding
        edits.erase(id, candidates = { index.query(StrokeIndex.Bounds(left, top, right, bottom)).map { it.first } }) {
            val bounds = it.bounds
            bounds != null && bounds.xMax >= left && bounds.xMin <= right &&
                bounds.yMax >= top && bounds.yMin <= bottom && it.image == null && CircularEraser.hits(it.ink.shape, x0, y0, x1, y1, radius)
        }
    }

    private fun publish() {
        mutableUi.value = mutableUi.value.copy(
            temporaryEraser = penButton.held,
            temporaryLasso = penButton.lassoHeld,
            strokes = edits.entries.size,
            contentRevision = edits.revision,
            busy = edits.busy || selectionWorking || selectionGesture,
            canUndo = edits.canUndo && !selectionWorking && !selectionGesture,
            canRedo = edits.canRedo && !selectionWorking && !selectionGesture,
            selectionCount = selection.size,
        )
        updateViewportOverlay()
        onCanvasChange?.invoke()
        enqueueSave()
    }
    private fun createBrush(ui: EditorUi, pressure: Boolean = true) = Brush.createWithColorIntArgb(
        if (ui.tool == EditorTool.HIGHLIGHTER) InkBrushes.highlighter
        else if (pressure && ui.tool == EditorTool.PEN) InkBrushes.pressurePen(mutablePreferences.value.value.pressureCurve)
        else StockBrushes.marker(StockBrushes.MarkerVersion.V1), ui.color, PenStyle.brushWidth(ui.size),
        StrokeFidelity.displayEpsilon(PenStyle.brushWidth(ui.size)),
    )
}
