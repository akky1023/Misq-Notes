package jp.handwriting.note.storage

import java.io.*
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Append-only payloads and two checksummed commit slots. A preview never decodes stroke data. */
internal class MisqFile(val file: File) {
    private var cachedStamp: Pair<Long, Long>? = null
    private var cachedIndexes: List<Index>? = null
    internal data class Record(val offset: Long, val size: Int, val hash: String)
    private data class Slot(val generation: Long, val index: Record, val thumbnail: Record?, val summary: Record?)
    internal data class Event(val version: String, val entity: String, val parents: List<String>,
        val blob: String?, val order: Long, val at: Long)
    internal data class Index(val generation: Long, val manifest: NoteManifest, val records: Map<String, Record>,
        val events: List<Event>, val entities: Map<Long, String>, val thumbnail: Record? = null, val thumbnailKey: String? = null,
        val previousManifest: NoteManifest? = null)
    private val codec get() = NoteFiles(file.parentFile!!, false)
    private fun key(note: NoteManifest) = NotebookArchive.digest((note.info.paper.color.toString() + ":" +
        note.strokes.joinToString { it.storageId }).toByteArray())

    private fun record(input: RandomAccessFile, record: Record): ByteArray {
        require(record.offset >= LEGACY_HEADER && record.size in 0..MAX_RECORD && record.offset <= input.length() - record.size)
        input.seek(record.offset)
        return ByteArray(record.size).also { input.readFully(it); require(NotebookArchive.digest(it) == record.hash) }
    }
    private fun slots(input: RandomAccessFile): List<Slot> {
        input.seek(0); val magic = input.readLong(); require(magic == MAGIC || magic == LEGACY_MAGIC)
        val size = if (magic == LEGACY_MAGIC) LEGACY_SLOT else SLOT
        return (0..1).mapNotNull { position -> runCatching {
            input.seek(8L + position * size)
            val bytes = ByteArray(size).also(input::readFully)
            if (magic == MAGIC) require(MessageDigest.getInstance("SHA-256").digest(bytes.copyOf(size - 32)).contentEquals(bytes.takeLast(32).toByteArray()))
            val data = ByteBuffer.wrap(bytes)
            val generation = data.long; require(generation > 0)
            fun ref(): Record? {
                val offset = data.long; val length = data.int; val digest = ByteArray(32).also(data::get)
                if (offset == 0L) return null
                return Record(offset, length, digest.joinToString("") { "%02x".format(it) })
            }
            val index = checkNotNull(ref())
            Slot(generation, index, if (magic == MAGIC) ref() else null, if (magic == MAGIC) ref() else null)
        }.getOrNull() }.sortedByDescending { it.generation }
    }
    internal fun indexes(): List<Index> {
        val stamp = file.length() to file.lastModified()
        if (cachedStamp == stamp) cachedIndexes?.let { return it }
        try {
            return RandomAccessFile(file, "r").use { input ->
                require(input.length() >= LEGACY_HEADER)
                slots(input).mapNotNull { slot -> runCatching {
                    val body = record(input, slot.index)
                    val parsed = decode(JSONObject(String(body, Charsets.UTF_8)))
                    require(parsed.generation == slot.generation)
                    parsed
                }.getOrNull() }.sortedByDescending { it.generation }.also { require(it.isNotEmpty()) }
            }.also { cachedStamp = stamp; cachedIndexes = it }
        } catch (error: Exception) { throw IOException("Invalid Misq note: ${file.name}", error) }
    }
    internal fun index() = indexes().first()
    fun readManifest(): ReadManifest {
        val index = index()
        // A higher committed slot with a damaged body means the previous generation was recovered.
        val newest = RandomAccessFile(file, "r").use { input ->
            val size = if (input.readLong() == LEGACY_MAGIC) LEGACY_SLOT else SLOT
            (0..1).maxOf { input.seek(8L + it * size); input.readLong() }
        }
        return ReadManifest(index.manifest, newest > index.generation)
    }
    fun previous() = index().previousManifest ?: indexes().getOrNull(1)?.manifest
    fun stroke(id: String): ByteArray = try {
        val index = index()
        RandomAccessFile(file, "r").use { record(it, index.records.getValue(id)) }
    } catch (error: Exception) { throw IOException("Invalid Misq stroke: $id", error) }
    fun thumbnailCurrent() = index().let { it.thumbnail != null && it.thumbnailKey == key(it.manifest) }
    fun thumbnail(): ByteArray? = RandomAccessFile(file, "r").use { input ->
        val committed = slots(input)
        if (committed.isEmpty()) throw IOException("Invalid Misq commit")
        for (slot in committed) {
            val ref = slot.thumbnail ?: if (slot.summary == null) index().thumbnail else null
            if (ref != null) runCatching { record(input, ref) }.getOrNull()?.let { return@use it }
        }
        null
    }
    fun summary(): NoteSummary = RandomAccessFile(file, "r").use { input ->
        for (slot in slots(input)) {
            val result = runCatching {
                if (slot.summary == null) {
                    val manifest = index().manifest
                    NoteSummary(manifest.info.id, manifest.info.title, manifest.info.updatedAt, manifest.strokes.size,
                        createdAt = manifest.info.createdAt, paper = manifest.info.paper)
                } else {
                    val json = JSONObject(String(record(input, slot.summary), Charsets.UTF_8))
                    val id = json.getString("id")
                    val manifest = codec.decodeManifest(id, Base64.getDecoder().decode(json.getString("manifest")))
                    require(id == file.name.substringBefore('.'))
                    NoteSummary(id, manifest.info.title, manifest.info.updatedAt, json.getInt("strokes"),
                        createdAt = manifest.info.createdAt, paper = manifest.info.paper)
                }
            }.getOrNull()
            if (result != null) return@use result
        }
        throw IOException("Invalid Misq summary")
    }
    private fun append(output: RandomAccessFile, bytes: ByteArray): Record {
        require(bytes.size <= MAX_RECORD)
        val offset = output.length().coerceAtLeast(HEADER.toLong())
        output.seek(offset); output.write(bytes)
        return Record(offset, bytes.size, NotebookArchive.digest(bytes))
    }
    private fun commit(output: RandomAccessFile, index: Index) {
        val body = encode(index).toString().toByteArray(Charsets.UTF_8)
        val ref = append(output, body)
        val summary = JSONObject().put("id", index.manifest.info.id).put("strokes", index.manifest.strokes.size)
            .put("manifest", Base64.getEncoder().encodeToString(codec.encodeManifest(index.manifest.copy(strokes = emptyList(), nextId = 1))))
        val summaryRef = append(output, summary.toString().toByteArray())
        output.fd.sync()
        val slot = index.generation % 2
        val slotData = ByteBuffer.allocate(SLOT - 32).putLong(index.generation)
        fun put(record: Record?) {
            slotData.putLong(record?.offset ?: 0).putInt(record?.size ?: 0)
            slotData.put(if (record == null) ByteArray(32) else record.hash.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
        }
        put(ref); put(index.thumbnail); put(summaryRef)
        val slotBody = slotData.array() + MessageDigest.getInstance("SHA-256").digest(slotData.array())
        output.seek(8L + slot * SLOT); output.write(slotBody); output.fd.sync()
        cachedStamp = null; cachedIndexes = null
    }
    private fun open(): RandomAccessFile {
        file.parentFile!!.mkdirs()
        return RandomAccessFile(file, "rw").also { if (it.length() == 0L) { it.setLength(HEADER.toLong()); it.writeLong(MAGIC); it.fd.sync() } }
    }
    fun save(note: NoteManifest, encode: (String) -> ByteArray) {
        upgradeLegacy()
        if (file.exists() && file.length() > 0) {
            if (file.length() < HEADER || RandomAccessFile(file, "r").use { it.readLong() != MAGIC })
                throw IOException("Invalid Misq header; original data retained")
        }
        require(note.nextId > (note.strokes.maxOfOrNull { it.id } ?: 0) && note.strokes.map { it.id }.distinct().size == note.strokes.size)
        // Validate through the same parser used when reopening.
        codec.decodeManifest(note.info.id, codec.encodeManifest(note))
        val previous = if (file.exists() && file.length() >= HEADER) runCatching { index() }.getOrElse {
            // A file containing a damaged commit must never be replaced with an empty note.
            val committed = RandomAccessFile(file, "r").use { input -> (0..1).any { input.seek(8L + it * SLOT); input.readLong() != 0L } }
            if (committed) throw it else null
        } else null
        if (previous?.manifest == note) return
        val events = previous?.events.orEmpty().toMutableList()
        val histories = events.groupBy { it.entity }
        var lastOrder = events.maxOfOrNull { it.order } ?: 0L
        val entities = previous?.entities.orEmpty().toMutableMap()
        val old = previous?.manifest?.strokes.orEmpty().associateBy { it.id }
        val current = note.strokes.associateBy { it.id }
        for (id in old.keys + current.keys) {
            if (old[id]?.storageId == current[id]?.storageId) continue
            val entity = entities.getOrPut(id) { checkNotNull(current[id]).storageId }
            val heads = frontier(histories[entity].orEmpty())
            val parents = heads.map { it.version }
            val order = heads.firstOrNull()?.order ?: if (previous == null) id else ++lastOrder
            val version = if (previous == null) UUID.nameUUIDFromBytes((note.info.id + ":" + entity).toByteArray()).toString() else UUID.randomUUID().toString()
            events += Event(version, entity, parents, current[id]?.storageId, order, if (previous == null) note.info.updatedAt else System.currentTimeMillis())
        }
        open().use { output ->
            val records = previous?.records.orEmpty().toMutableMap()
            note.strokes.forEach { ref -> if (ref.storageId !in records) records[ref.storageId] = append(output, encode(ref.storageId)) }
            commit(output, Index((previous?.generation ?: 0) + 1, note, records, events, entities,
                previous?.thumbnail, previous?.thumbnailKey, previous?.manifest))
        }
    }
    fun saveThumbnail(png: ByteArray) {
        upgradeLegacy()
        val index = index()
        open().use { output -> commit(output, index.copy(generation = index.generation + 1,
            thumbnail = append(output, png), thumbnailKey = key(index.manifest))) }
    }
    private fun upgradeLegacy() {
        if (file.exists() && file.length() >= 8 && RandomAccessFile(file, "r").use { it.readLong() == LEGACY_MAGIC }) rewrite(index())
    }
    fun validate() {
        val index = index()
        RandomAccessFile(file, "r").use { input ->
            index.manifest.strokes.forEach { record(input, index.records.getValue(it.storageId)) }
            index.thumbnail?.let { record(input, it) }
        }
    }
    /** Retain tombstones/causality; only obsolete payloads and old index bodies are compacted. */
    fun compactIfNeeded() {
        val index = index()
        val live = index.records.values.sumOf { it.size.toLong() } + (index.thumbnail?.size ?: 0) +
            encode(index).toString().toByteArray().size * 2L
        if (file.length() <= live * 3 + 8L * 1024 * 1024) return
        // Keep every historical blob named by an event, for conflict copies and undo after merge.
        rewrite(index)
    }
    internal fun rewrite(index: Index, other: MisqFile? = null) {
        val temporary = File(file.path + ".compact")
        temporary.delete()
        try {
            val target = MisqFile(temporary)
            target.open().use { output ->
                val needed = index.events.mapNotNull { it.blob }.toSet() + index.manifest.strokes.map { it.storageId }
                val mine = if (file.exists()) index() else null
                val previous = if (other == null) indexes().getOrNull(1) else mine
                val records = needed.associateWith { id ->
                    val bytes = if (mine?.records?.containsKey(id) == true) stroke(id) else checkNotNull(other).stroke(id)
                    target.append(output, bytes)
                }
                val thumb = if (other == null) thumbnail()?.let { target.append(output, it) } else null
                previous?.let { prior ->
                    val priorThumbnail = if (other == null) prior.thumbnail?.let { ref ->
                        RandomAccessFile(file, "r").use { target.append(output, record(it, ref)) }
                    } else null
                    target.commit(output, prior.copy(generation = 1, records = records, thumbnail = priorThumbnail,
                        thumbnailKey = if (priorThumbnail != null) prior.thumbnailKey else null))
                }
                target.commit(output, index.copy(generation = if (previous == null) 1 else 2, records = records, thumbnail = thumb,
                    thumbnailKey = if (thumb != null) index.thumbnailKey else null))
            }
            target.validate()
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            cachedStamp = null; cachedIndexes = null
        } finally { temporary.delete() }
    }
    private fun encode(index: Index): JSONObject = JSONObject().put("format", 1).put("generation", index.generation)
        .put("id", index.manifest.info.id).put("manifest", Base64.getEncoder().encodeToString(codec.encodeManifest(index.manifest)))
        .put("records", JSONObject().also { obj -> index.records.forEach { (id, ref) -> obj.put(id, recordJson(ref)) } })
        .put("events", JSONArray().also { array -> index.events.forEach { event -> array.put(JSONObject()
            .put("version", event.version).put("entity", event.entity).put("parents", JSONArray(event.parents))
            .put("blob", event.blob ?: JSONObject.NULL).put("order", event.order).put("at", event.at)) } })
        .put("entities", JSONObject().also { obj -> index.entities.forEach { (id, entity) -> obj.put(id.toString(), entity) } })
        .put("thumbnail", index.thumbnail?.let(::recordJson) ?: JSONObject.NULL)
        .put("thumbnailKey", index.thumbnailKey ?: JSONObject.NULL)
        .put("previousManifest", index.previousManifest?.let { Base64.getEncoder().encodeToString(codec.encodeManifest(it)) } ?: JSONObject.NULL)
    private fun recordJson(ref: Record) = JSONObject().put("offset", ref.offset).put("size", ref.size).put("sha256", ref.hash)
    private fun recordJson(obj: JSONObject) = Record(obj.getLong("offset"), obj.getInt("size"), obj.getString("sha256")).also {
        require(it.offset >= LEGACY_HEADER && it.size in 0..MAX_RECORD && it.hash.matches(Regex("[a-f0-9]{64}")))
    }
    private fun decode(obj: JSONObject): Index {
        require(obj.getInt("format") == 1)
        fun uuid(value: String) = value.also { require(UUID.fromString(it).toString() == it) }
        val note = codec.decodeManifest(uuid(obj.getString("id")), Base64.getDecoder().decode(obj.getString("manifest")))
        require(file.name.substringBefore('.') == note.info.id || file.name.endsWith(".compact") || file.name.endsWith(".tmp"))
        val records = obj.getJSONObject("records").let { value -> value.keys().asSequence().associate { uuid(it) to recordJson(value.getJSONObject(it)) } }
        val events = obj.getJSONArray("events").let { value -> List(value.length()) { i ->
            val event = value.getJSONObject(i); val parents = event.getJSONArray("parents")
            Event(uuid(event.getString("version")), uuid(event.getString("entity")), List(parents.length()) { uuid(parents.getString(it)) },
                if (event.isNull("blob")) null else uuid(event.getString("blob")), event.getLong("order"), event.getLong("at"))
        } }
        require(events.size <= 1_000_000 && events.map { it.version }.distinct().size == events.size)
        val seen = mutableMapOf<String, String>()
        events.forEach { event ->
            require(event.order > 0 && event.parents.all { seen[it] == event.entity })
            require(event.blob == null || event.blob in records)
            seen[event.version] = event.entity
        }
        val entities = obj.getJSONObject("entities").let { value -> value.keys().asSequence().associate { it.toLong() to uuid(value.getString(it)) } }
        require(note.strokes.all { it.storageId in records && it.id in entities })
        return Index(obj.getLong("generation"), note, records, events, entities,
            if (obj.isNull("thumbnail")) null else recordJson(obj.getJSONObject("thumbnail")),
            if (obj.isNull("thumbnailKey")) null else obj.getString("thumbnailKey"),
            if (obj.isNull("previousManifest")) null else codec.decodeManifest(note.info.id, Base64.getDecoder().decode(obj.getString("previousManifest"))))
    }
    companion object {
        private const val LEGACY_MAGIC = 0x4D49535130303031L
        private const val LEGACY_SLOT = 64
        private const val LEGACY_HEADER = 8 + LEGACY_SLOT * 2
        private const val MAGIC = 0x4D49535130303032L // MISQ0002
        private const val SLOT = 192
        private const val HEADER = 8 + SLOT * 2
        private const val MAX_RECORD = 128 * 1024 * 1024
        internal fun frontier(events: List<Event>): List<Event> {
            val parents = events.flatMap { it.parents }.toSet()
            return events.filter { it.version !in parents }
        }
        /** Never use wall clocks to resolve concurrent changes. */
        internal fun merge(base: MisqFile?, local: MisqFile, remote: MisqFile): Index {
            val a = local.index(); val b = remote.index(); val original = base?.index()?.manifest?.info
            require(a.manifest.info.id == b.manifest.info.id)
            fun <T> choose(old: T?, left: T, right: T): T = when {
                left == right || right == old -> left
                left == old -> right
                else -> throw ArchiveConflictException()
            }
            val left = a.manifest.info; val right = b.manifest.info
            val info = left.copy(title = choose(original?.title, left.title, right.title),
                paper = choose(original?.paper, left.paper, right.paper),
                updatedAt = maxOf(left.updatedAt, right.updatedAt))
            val combined = linkedMapOf<String, Event>()
            (a.events + b.events).forEach { event ->
                val previous = combined.put(event.version, event)
                require(previous == null || previous == event)
            }
            // Both logs are topologically ordered; shared ancestors were inserted by the first log.
            val events = combined.values.toMutableList()
            val live = events.groupBy { it.entity }.mapNotNull { (entity, history) ->
                val heads = frontier(history)
                if (heads.map { it.blob }.distinct().size > 1) throw ArchiveConflictException()
                val head = if (heads.size == 1) heads.single() else heads.first().copy(version = UUID.randomUUID().toString(),
                    parents = heads.map { it.version }, at = heads.maxOf { it.at }).also { events += it }
                head.takeIf { it.blob != null }?.let { entity to it }
            }.sortedWith(compareBy<Pair<String, Event>> { it.second.order }.thenBy { it.first })
            val refs = live.mapIndexed { i, (_, event) -> StrokeRef(i + 1L, checkNotNull(event.blob)) }
            return a.copy(manifest = NoteManifest(info, refs, refs.size + 1L), events = events,
                entities = live.mapIndexed { i, (entity, _) -> i + 1L to entity }.toMap(), thumbnail = null, thumbnailKey = null,
                previousManifest = a.manifest)
        }
    }
}
