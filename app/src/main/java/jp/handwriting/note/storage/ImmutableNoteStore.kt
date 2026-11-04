package jp.handwriting.note.storage

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Format 3. Objects are immutable; a local head is installed only after its dependencies exist. */
internal class ImmutableNoteStore(val directory: File, internal var fetch: ((String) -> InputStream)? = null) {
    data class Body(val objectId: String, val size: Int, val hash: String)
    data class Row(val ref: StrokeRef, val entity: String, val placement: StrokePlacement,
        val body: Body, val heads: List<String>, val order: Long)
    data class Tombstone(val id: Long, val entity: String, val heads: List<String>, val order: Long)
    data class Page(val key: String, val objectId: String, val count: Int, val top: Float, val bottom: Float)
    data class Root(val id: String, val info: NoteInfo, val nextId: Long, val lastOrder: Long,
        val pages: List<Page>, val history: String?, val parents: List<String>, val deleted: Boolean = false,
        val thumbnail: String? = null, val thumbnailKey: String? = null, val tombstones: Map<Long, String> = emptyMap()) {
        val count get() = if (deleted) 0 else pages.sumOf { it.count }
        val bottom get() = pages.maxOfOrNull { it.bottom } ?: 0f
    }
    private val objects = File(directory, "objects").also { check(it.isDirectory || it.mkdirs()) }
    private val heads = File(directory, "heads").also { check(it.isDirectory || it.mkdirs()) }
    init { syncNoteDirectory(directory); directory.parentFile?.let(::syncNoteDirectory) }
    private val pageCache = object : LinkedHashMap<String, List<Row>>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Row>>?) = size > 8
    }
    private val recoveredHeads = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun recovered(id: String) = id in recoveredHeads
    private fun uuid(id: String) = id.also { require(UUID.fromString(it).toString() == it) }
    private fun objectFile(id: String) = File(objects, id.also { require(it.matches(OBJECT)) })
    fun objectNames(): Set<String> = objects.listFiles().orEmpty().filter { it.isFile && it.name.matches(OBJECT) }.map { it.name }.toSet()
    fun objectBytes(id: String): ByteArray = openObject(id).use { it.readBytes() }.also { require(NotebookArchive.digest(it) == id.removePrefix("o-")) { "Damaged format 3 object: $id" } }
    fun put(bytes: ByteArray): String {
        val id = "o-${NotebookArchive.digest(bytes)}"; val file = objectFile(id)
        if (file.exists()) { check(objectBytes(id).contentEquals(bytes)); return id }
        val temporary = File(objects, ".write-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE)
            syncNoteDirectory(objects)
        } finally { temporary.delete() }
        return id
    }
    fun importObject(id: String, input: InputStream, checkpoint: () -> Unit = {}) {
        val file = objectFile(id)
        if (file.exists()) { require(NotebookArchive.digest(file) == id.removePrefix("o-")); return }
        val temporary = File(objects, ".receive-${UUID.randomUUID()}")
        try {
            FileOutputStream(temporary).use { output ->
                val buffer = ByteArray(8192); var bytes = 0L
                while (true) { checkpoint(); val n = input.read(buffer); if (n < 0) break; bytes += n; require(bytes <= MAX_RECORD.toLong() + 8 * 1024 * 1024); output.write(buffer, 0, n) }
                output.fd.sync()
            }
            require(NotebookArchive.digest(temporary) == id.removePrefix("o-")) { "Damaged incoming object" }
            Files.move(temporary.toPath(), file.toPath(), ATOMIC_MOVE)
            syncNoteDirectory(objects)
        } finally { temporary.delete() }
    }
    fun openObject(id: String, checkpoint: () -> Unit = {}): InputStream {
        checkpoint()
        if (!objectFile(id).exists()) checkNotNull(fetch) { "Missing storage object: $id" }(id).use { importObject(id, it, checkpoint) }
        return objectFile(id).inputStream()
    }
    fun current(id: String): Root? {
        val file = File(heads, uuid(id)); val backup = File(file.path + ".bak")
        var failure: Exception? = null
        for (candidate in listOf(file, backup)) {
            if (!candidate.exists()) continue
            try {
                val hash = String(NoteFiles.readEnvelope(candidate), Charsets.US_ASCII)
                val root = root(hash).also { require(it.info.id == id) }
                if (candidate == backup) recoveredHeads += id else recoveredHeads.remove(id)
                return root
            } catch (error: Exception) { failure = error }
        }
        if (failure != null) throw IOException("Invalid notebook head: $id", failure)
        return null
    }
    fun ids(): Set<String> = heads.listFiles().orEmpty().filter { it.isFile }.map { it.name.removeSuffix(".bak") }
        .filter { runCatching { uuid(it) }.isSuccess }.toSet()
    fun allRoots(): List<Root> = ids().mapNotNull(::current)
    fun list(): List<Root> = allRoots().filter { !it.deleted }
    fun create(info: NoteInfo): Root = publish(Root("", info, 1, 0, emptyList(), null, emptyList()))
    fun find(root: Root, ids: Set<Long>): Map<Long, Row> {
        val buckets = ids.map { it / 256 }.toSet()
        return root.pages.filter { it.key.substringAfter(':').toLong() in buckets }.flatMap(::rows).filter { it.ref.id in ids }.associateBy { it.ref.id }
    }
    fun install(root: Root, validate: Boolean = true) {
        if (validate) verify(root)
        NoteFiles.atomicWrite(File(heads, uuid(root.info.id)), root.id.toByteArray(Charsets.US_ASCII))
        syncNoteDirectory(heads)
        recoveredHeads.remove(root.info.id)
    }
    private fun json(id: String, kind: String): JSONObject {
        if (!objectFile(id).exists()) openObject(id).close()
        require(objectFile(id).length() <= 16L * 1024 * 1024) { "Storage metadata exceeds supported size" }
        return JSONObject(String(objectBytes(id), Charsets.UTF_8)).also {
        require(it.getInt("format") == 3 && it.getInt("minReader") == 3 && it.getInt("minWriter") == 3 && it.getString("kind") == kind) { "Unsupported storage format" }
        }
    }
    private fun envelope(kind: String) = JSONObject().put("format", 3).put("minReader", 3).put("minWriter", 3).put("kind", kind)
    private fun canonical(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Number -> JSONObject.numberToString(value)
        is Boolean -> value.toString()
        else -> error("Unsupported storage value")
    }
    private fun putJson(json: JSONObject): String {
        val bytes = canonical(json).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 16 * 1024 * 1024) { "Storage metadata exceeds supported size; previous state has been retained" }
        return put(bytes)
    }
    private fun strings(array: JSONArray) = List(array.length()) { array.getString(it) }
    private fun header(info: NoteInfo, next: Long) = java.util.Base64.getEncoder().encodeToString(NoteManifestCodec.encodeManifest(NoteManifest(info, emptyList(), next)))
    fun root(id: String): Root {
        val json = json(id, "root"); val noteId = uuid(json.getString("note"))
        val manifest = NoteManifestCodec.decodeManifest(noteId, java.util.Base64.getDecoder().decode(json.getString("header")))
        val pages = json.getJSONArray("pages").let { array -> List(array.length()) { i ->
            val p = array.getJSONObject(i)
            Page(p.getString("key"), p.getString("object"), p.getInt("count"), p.getDouble("top").toFloat(), p.getDouble("bottom").toFloat()).also {
                require(it.key.matches(PAGE_KEY) && it.objectId.matches(OBJECT) && it.count in 1..256 && it.top.isFinite() && it.bottom.isFinite() && it.bottom >= it.top)
            }
        } }
        require(pages.map { it.key }.distinct().size == pages.size && pages.sumOf { it.count.toLong() } <= 1_000_000L)
        val parents = strings(json.getJSONArray("parents")); require(parents.distinct().size == parents.size && parents.all { it.matches(OBJECT) && it != id })
        val history = json.optString("history").takeIf { it.isNotEmpty() }; require(history == null || history.matches(OBJECT))
        val tombstones = json.optJSONObject("tombstones")?.let { value -> value.keys().asSequence().associate { key ->
            key.toLong().also { require(it >= 0) } to value.getString(key).also { require(it.matches(OBJECT)) }
        } }.orEmpty()
        return Root(id, manifest.info, manifest.nextId, json.getLong("lastOrder"), pages, history, parents, json.getBoolean("deleted"),
            json.optString("thumbnail").takeIf { it.isNotEmpty() }, json.optString("thumbnailKey").takeIf { it.isNotEmpty() }, tombstones)
    }
    private fun publish(value: Root): Root {
        val parents = value.parents.distinct().sorted()
        parents.forEach { require(root(it).info.id == value.info.id) { "Invalid cross-notebook parent" } }
        val json = envelope("root").put("note", value.info.id).put("header", header(value.info, value.nextId))
            .put("lastOrder", value.lastOrder).put("parents", JSONArray(parents)).put("deleted", value.deleted)
            .put("history", value.history.orEmpty()).put("thumbnail", value.thumbnail.orEmpty()).put("thumbnailKey", value.thumbnailKey.orEmpty())
            .put("tombstones", JSONObject().also { obj -> value.tombstones.forEach { (bucket, id) -> obj.put(bucket.toString(), id) } })
            .put("pages", JSONArray().also { array -> value.pages.sortedBy { it.key }.forEach { p -> array.put(JSONObject()
                .put("key", p.key).put("object", p.objectId).put("count", p.count).put("top", p.top).put("bottom", p.bottom)) } })
        return value.copy(id = putJson(json), parents = parents)
    }
    @Synchronized fun rows(page: Page): List<Row> = pageCache[page.objectId] ?: json(page.objectId, "page").let { json ->
        require(json.getString("key") == page.key)
        val array = json.getJSONArray("rows"); require(array.length() == page.count)
        List(array.length()) { i ->
            val row = array.getJSONObject(i); val p = row.getJSONArray("placement")
            val ref = StrokeRef(row.getLong("id"), uuid(row.getString("storage")))
            val body = Body(row.getString("object"), row.getInt("size"), row.getString("hash"))
            val placement = StrokePlacement(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat())
            require(ref.id > 0 && pageKey(ref.id, placement) == page.key && body.objectId.matches(OBJECT) && body.size in 1..MAX_RECORD && body.hash.matches(HASH))
            val versions = strings(row.getJSONArray("heads")); require(versions.isNotEmpty() && versions.distinct().size == versions.size)
            Row(ref, uuid(row.getString("entity")), placement, body, versions.map(::uuid), row.getLong("order"))
        }.also { rows ->
            require(rows.map { it.ref.id }.distinct().size == rows.size && rows.map { it.ref.storageId }.distinct().size == rows.size)
            require(rows.minOf { it.placement.top } == page.top && rows.maxOf { it.placement.bottom } == page.bottom)
            pageCache[page.objectId] = rows
        }
    }
    private fun page(key: String, rows: List<Row>): Page {
        require(rows.isNotEmpty() && rows.size <= 256)
        val json = envelope("page").put("key", key).put("rows", JSONArray().also { array -> rows.sortedBy { it.ref.id }.forEach { row ->
            array.put(JSONObject().put("id", row.ref.id).put("storage", row.ref.storageId).put("entity", row.entity)
                .put("placement", JSONArray().put(row.placement.startY).put(row.placement.top).put(row.placement.bottom))
                .put("object", row.body.objectId).put("size", row.body.size).put("hash", row.body.hash)
                .put("heads", JSONArray(row.heads)).put("order", row.order))
        } })
        return Page(key, putJson(json), rows.size, rows.minOf { it.placement.top }, rows.maxOf { it.placement.bottom })
    }
    private fun pageKey(id: Long, placement: StrokePlacement) = "${placement.band}:${id / 256}"
    private fun tombstones(id: String): List<Tombstone> {
        val value = json(id, "tombstones"); val array = value.getJSONArray("rows"); require(array.length() in 1..256)
        return List(array.length()) { index ->
            val row = array.getJSONObject(index); val heads = strings(row.getJSONArray("heads")).map(::uuid)
            Tombstone(row.getLong("id"), uuid(row.getString("entity")), heads, row.getLong("order")).also {
                require(it.id > 0 && it.order > 0 && it.heads.isNotEmpty() && it.heads.distinct().size == it.heads.size)
            }
        }
    }
    private fun saveTombstones(rows: Collection<Tombstone>): String = putJson(envelope("tombstones").put("rows", JSONArray().also { array ->
        rows.sortedBy { it.id }.forEach { row -> array.put(JSONObject().put("id", row.id).put("entity", row.entity).put("heads", JSONArray(row.heads)).put("order", row.order)) }
    }))
    fun manifest(root: Root): NoteManifest = NoteManifest(root.info, object : AbstractList<StrokeRef>() {
        override val size = root.count
        override fun get(index: Int): StrokeRef {
            require(index in 0 until size)
            var offset = index
            root.pages.forEach { page -> if (offset < page.count) return rows(page)[offset].ref else offset -= page.count }
            error("Invalid format 3 index")
        }
    }, root.nextId)
    fun visible(root: Root, top: Float, bottom: Float, loaded: Set<Long>, checkpoint: () -> Unit = {}, onePage: Boolean = false): List<Pair<StrokeRef, ByteArray>> {
        if (root.deleted) return emptyList()
        val pages = root.pages.filter { it.bottom >= top && it.top <= bottom }.sortedBy { kotlin.math.abs((it.top.toDouble() + it.bottom) / 2 - (top.toDouble() + bottom) / 2) }
        val selected = mutableListOf<Row>()
        for (page in pages) {
            checkpoint()
            val wanted = rows(page).filter { it.ref.id !in loaded && it.placement.bottom >= top && it.placement.top <= bottom }
            selected += wanted
            if (onePage && wanted.isNotEmpty()) break
        }
        val bodies = readBodies(selected.associate { it.ref.storageId to it.body }, checkpoint)
        return selected.sortedBy { it.ref.id }.map { it.ref to bodies.getValue(it.ref.storageId) }
    }
    fun pending(root: Root, top: Float, bottom: Float, loaded: Set<Long>) = !root.deleted && root.pages.any { page ->
        page.bottom >= top && page.top <= bottom && rows(page).any { it.ref.id !in loaded && it.placement.bottom >= top && it.placement.top <= bottom }
    }
    fun read(root: Root, refs: List<StrokeRef>): List<Pair<StrokeRef, ByteArray>> {
        val wanted = refs.associateBy { it.id }; val buckets = wanted.keys.map { it / 256 }.toSet()
        val rows = root.pages.filter { it.key.substringAfter(':').toLong() in buckets }.flatMap(::rows)
            .filter { wanted[it.ref.id] == it.ref }
        require(rows.size == refs.size) { "Missing referenced stroke" }
        val bytes = readBodies(rows.associate { it.ref.storageId to it.body })
        return refs.map { it to bytes.getValue(it.storageId) }
    }
    private fun pack(records: Map<String, ByteArray>): Map<String, Body> {
        val temporary = File(objects, ".pack-${UUID.randomUUID()}")
        val id = try {
        FileOutputStream(temporary).use { raw -> DataOutputStream(raw).also { out ->
            out.writeInt(PACK_MAGIC); out.writeInt(records.size)
            records.forEach { (id, bytes) -> uuid(id); require(bytes.size in 1..MAX_RECORD); out.writeUTF(id); out.writeInt(bytes.size); out.write(bytes) }
            out.flush(); raw.fd.sync()
        } }
        val name = "o-${NotebookArchive.digest(temporary)}"; val target = objectFile(name)
        if (target.exists()) check(NotebookArchive.digest(target) == name.removePrefix("o-"))
        else { Files.move(temporary.toPath(), target.toPath(), ATOMIC_MOVE); syncNoteDirectory(objects) }
        name
        } finally { temporary.delete() }
        return records.mapValues { (_, bytes) -> Body(id, bytes.size, NotebookArchive.digest(bytes)) }
    }
    private fun readBodies(wanted: Map<String, Body>, checkpoint: () -> Unit = {}): Map<String, ByteArray> {
        val result = mutableMapOf<String, ByteArray>()
        wanted.entries.groupBy { it.value.objectId }.forEach { (objectId, members) ->
            val requested = members.associate { it.key to it.value }; val seen = mutableSetOf<String>()
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            java.security.DigestInputStream(openObject(objectId, checkpoint), digest).use { stream -> DataInputStream(stream).use { input ->
                require(input.readInt() == PACK_MAGIC)
                val count = input.readInt(); require(count in 1..1_000_000)
                val buffer = ByteArray(8192)
                repeat(count) {
                    checkpoint()
                    val id = uuid(input.readUTF()); require(seen.add(id))
                    val size = input.readInt(); require(size in 1..MAX_RECORD)
                    val ref = requested[id]
                    if (ref != null) {
                        require(ref.size == size)
                        val bytes = ByteArray(size).also(input::readFully); require(NotebookArchive.digest(bytes) == ref.hash)
                        result[id] = bytes
                    } else {
                        var remaining = size
                        while (remaining > 0) { checkpoint(); val n = input.read(buffer, 0, minOf(buffer.size, remaining)); require(n > 0); remaining -= n }
                    }
                }
                require(input.read() == -1)
            } }
            require(NotebookArchive.hex(digest.digest()) == objectId.removePrefix("o-")) { "Damaged body pack" }
            require(requested.keys.all { it in result })
        }
        return result
    }
    private fun eventJson(event: MisqFile.Event) = JSONObject().put("version", event.version).put("entity", event.entity)
        .put("parents", JSONArray(event.parents)).put("blob", event.blob ?: JSONObject.NULL).put("order", event.order).put("at", event.at)
    private fun event(json: JSONObject) = MisqFile.Event(uuid(json.getString("version")), uuid(json.getString("entity")),
        strings(json.getJSONArray("parents")).map(::uuid), if (json.isNull("blob")) null else uuid(json.getString("blob")), json.getLong("order"), json.getLong("at"))
    private fun history(parent: String?, events: List<MisqFile.Event>, entities: Map<Long, String>, bodies: Map<String, Body>, placements: Map<String, StrokePlacement> = emptyMap()): String {
        val eventChunks = events.chunked(256); val entityChunks = entities.entries.sortedBy { it.key }.chunked(256)
        val bodyChunks = bodies.entries.sortedBy { it.key }.chunked(256); val placementChunks = placements.entries.sortedBy { it.key }.chunked(256)
        var prior = parent
        repeat(maxOf(1, eventChunks.size, entityChunks.size, bodyChunks.size, placementChunks.size)) { part ->
            val json = envelope("history").put("parent", prior.orEmpty()).put("events", JSONArray().also { array -> eventChunks.getOrNull(part).orEmpty().forEach { array.put(eventJson(it)) } })
                .put("entities", JSONObject().also { obj -> entityChunks.getOrNull(part).orEmpty().forEach { (id, entity) -> obj.put(id.toString(), entity) } })
                .put("bodies", JSONObject().also { obj -> bodyChunks.getOrNull(part).orEmpty().forEach { (id, body) -> obj.put(id, JSONObject().put("object", body.objectId).put("size", body.size).put("hash", body.hash)) } })
                .put("placements", JSONObject().also { obj -> placementChunks.getOrNull(part).orEmpty().forEach { (id, p) -> obj.put(id, JSONArray().put(p.startY).put(p.top).put(p.bottom)) } })
            prior = putJson(json)
        }
        return checkNotNull(prior)
    }
    fun history(root: Root): Triple<List<MisqFile.Event>, Map<Long, String>, Map<String, Body>> {
        val chunks = mutableListOf<JSONObject>(); val seen = mutableSetOf<String>(); var cursor = root.history
        while (cursor != null) {
            require(seen.add(cursor))
            val json = json(cursor, "history"); chunks += json; cursor = json.optString("parent").takeIf { it.isNotEmpty() }
        }
        val events = mutableListOf<MisqFile.Event>(); val entities = mutableMapOf<Long, String>(); val bodies = mutableMapOf<String, Body>()
        chunks.asReversed().forEach { chunk ->
            val array = chunk.getJSONArray("events"); repeat(array.length()) { events += event(array.getJSONObject(it)) }
            val ids = chunk.getJSONObject("entities"); ids.keys().forEach { entities[it.toLong()] = uuid(ids.getString(it)) }
            val blobs = chunk.getJSONObject("bodies"); blobs.keys().forEach { id ->
                val body = blobs.getJSONObject(id); val value = Body(body.getString("object"), body.getInt("size"), body.getString("hash"))
                require(value.objectId.matches(OBJECT) && value.size in 1..MAX_RECORD && value.hash.matches(HASH))
                bodies.put(uuid(id), value)?.let { require(it.hash == value.hash && it.size == value.size) { "Conflicting immutable body" } }
            }
        }
        val versions = mutableMapOf<String, String>()
        events.forEach { e -> require(e.version !in versions && e.parents.all { versions[it] == e.entity } && (e.blob == null || e.blob in bodies)); versions[e.version] = e.entity }
        return Triple(events, entities, bodies)
    }
    fun importNote(source: MisqFile, parents: List<String> = emptyList(), checkpoint: () -> Unit = {}): Root {
        val index = source.index(); source.validate()
        val bodies = mutableMapOf<String, Body>(); var records = linkedMapOf<String, ByteArray>(); var size = 0L
        val entityBands = index.manifest.strokes.associate { ref -> index.entities.getValue(ref.id) to index.placements[ref.storageId]?.band }
        val historicalBands = index.events.mapNotNull { event -> event.blob?.let { it to entityBands[event.entity] } }.toMap()
        val groups = index.records.keys.groupBy { index.placements[it]?.band ?: historicalBands[it] ?: Long.MIN_VALUE }.toSortedMap()
        for (group in groups.values) {
        for (id in group.sorted()) {
            checkpoint(); val bytes = source.stroke(id)
            if (records.isNotEmpty() && size + bytes.size > PACK_TARGET) { bodies += pack(records); records = linkedMapOf(); size = 0 }
            records[id] = bytes; size += bytes.size
            if (size >= PACK_TARGET) { bodies += pack(records); records = linkedMapOf(); size = 0 }
        }
        if (records.isNotEmpty()) bodies += pack(records)
        records = linkedMapOf(); size = 0
        }
        val byEntity = index.events.groupBy { it.entity }
        val rows = index.manifest.strokes.map { ref ->
            val entity = index.entities.getValue(ref.id); val events = byEntity.getValue(entity); val p = index.placements[ref.storageId]
                ?: throw IOException("Migration requires stroke placements")
            Row(ref, entity, p, bodies.getValue(ref.storageId), MisqFile.frontier(events).map { it.version }, events.last().order)
        }
        val pages = rows.groupBy { pageKey(it.ref.id, it.placement) }.map { (key, entries) -> page(key, entries) }
        val history = history(null, index.events, index.entities, bodies, index.placements)
        val active = index.manifest.strokes.map { it.id }.toSet()
        val removed = index.entities.filterKeys { it !in active }.mapNotNull { (id, entity) ->
            val frontier = MisqFile.frontier(byEntity[entity].orEmpty())
            if (frontier.isNotEmpty() && frontier.all { it.blob == null }) Tombstone(id, entity, frontier.map { it.version }, frontier.first().order) else null
        }.groupBy { it.id / 256 }.mapValues { saveTombstones(it.value) }
        val thumb = source.thumbnail()?.let(::put)
        val root = publish(Root("", index.manifest.info, index.manifest.nextId, index.events.maxOfOrNull { it.order } ?: 0, pages, history, parents,
            thumbnail = thumb, tombstones = removed))
        verify(root); return root
    }
    /** Only observed IDs may be deleted; unread pages are retained by reference. */
    fun edit(base: Root, info: NoteInfo, nextId: Long, observed: Set<Long>, entries: List<Pair<StrokeRef, Pair<StrokePlacement, ByteArray>>>,
        addedSince: Long = Long.MAX_VALUE): Root {
        require(!base.deleted && info.id == base.info.id && nextId >= base.nextId)
        val incoming = entries.associateBy { it.first.id }; require(incoming.size == entries.size && entries.all { it.first.id > 0 && it.first.id < nextId })
        val touchedBuckets = (observed + incoming.keys).map { it / 256 }.toSet()
        val affected = base.pages.filter { it.key.substringAfter(':').toLong() in touchedBuckets || it.key.substringAfter(':').toLong() >= addedSince / 256 }
        val oldRows = affected.flatMap(::rows).associateBy { it.ref.id }; val retained = oldRows.toMutableMap()
        val removed = base.tombstones.filterKeys { it in touchedBuckets || it >= addedSince / 256 }
            .values.flatMap(::tombstones).associateBy { it.id }.toMutableMap()
        val tombstoneBuckets = (touchedBuckets + base.tombstones.keys.filter { it >= addedSince / 256 }).toSet()
        val events = mutableListOf<MisqFile.Event>(); val entities = mutableMapOf<Long, String>(); val newBodies = mutableMapOf<String, Body>()
        var lastOrder = base.lastOrder
        val replacements = incoming.values.filter { oldRows[it.first.id]?.ref != it.first }
        var batch = linkedMapOf<String, ByteArray>(); var bytes = 0L
        replacements.groupBy { it.second.first.band }.toSortedMap().values.forEach { group ->
        group.forEach { entry ->
            if (batch.isNotEmpty() && bytes + entry.second.second.size > PACK_TARGET) { newBodies += pack(batch); batch = linkedMapOf(); bytes = 0 }
            batch[entry.first.storageId] = entry.second.second; bytes += entry.second.second.size
        }
        if (batch.isNotEmpty()) newBodies += pack(batch)
        batch = linkedMapOf(); bytes = 0
        }
        (observed + oldRows.keys.filter { it >= addedSince } + incoming.keys).forEach { id ->
            val old = oldRows[id]; val entry = incoming[id]
            if (entry == null) {
                if (old != null) {
                    retained.remove(id)
                    val version = MisqFile.Event(UUID.randomUUID().toString(), old.entity, old.heads, null, old.order, info.updatedAt)
                    events += version; removed[id] = Tombstone(id, old.entity, listOf(version.version), old.order)
                }
            } else {
                val dead = removed.remove(id)
                val entity = old?.entity ?: dead?.entity ?: entry.first.storageId; val order = old?.order ?: dead?.order ?: ++lastOrder
                val version = if (old?.ref == entry.first) null else MisqFile.Event(UUID.randomUUID().toString(), entity, old?.heads ?: dead?.heads.orEmpty(), entry.first.storageId, order, info.updatedAt)
                if (version != null) { events += version; entities[id] = entity }
                retained[id] = Row(entry.first, entity, entry.second.first, if (version == null) checkNotNull(old).body else newBodies.getValue(entry.first.storageId), version?.let { listOf(it.version) } ?: checkNotNull(old).heads, order)
            }
        }
        val unchanged = base.pages.filter { it !in affected }
        val pages = unchanged + retained.values.groupBy { pageKey(it.ref.id, it.placement) }.map { (key, rows) -> page(key, rows) }
        require(pages.map { it.key }.distinct().size == pages.size)
        val history = if (events.isEmpty()) base.history else history(base.history, events, entities, newBodies, replacements.associate { it.first.storageId to it.second.first })
        val tombstones = base.tombstones.filterKeys { it !in tombstoneBuckets } + removed.values.groupBy { it.id / 256 }.mapValues { saveTombstones(it.value) }
        return publish(base.copy(id = "", info = info, nextId = nextId, lastOrder = lastOrder, pages = pages, history = history, parents = listOf(base.id), thumbnailKey = null, tombstones = tombstones))
    }
    fun remove(base: Root): Root {
        if (base.deleted) return base
        val removed = edit(base, base.info, base.nextId, base.pages.flatMap(::rows).map { it.ref.id }.toSet(), emptyList())
        return publish(removed.copy(id = "", deleted = true, parents = listOf(base.id)))
    }
    fun resolve(base: Root, parents: List<String>): Root = publish(base.copy(id = "", parents = parents.distinct().sorted()))
    fun equivalent(a: Root, b: Root): Boolean = a.deleted == b.deleted && a.info.id == b.info.id && a.info.title == b.info.title &&
        a.info.createdAt == b.info.createdAt && a.info.paper == b.info.paper && a.nextId == b.nextId && a.lastOrder == b.lastOrder && a.pages == b.pages && a.history == b.history && a.tombstones == b.tombstones
    fun previewKey(root: Root) = NotebookArchive.digest((root.info.paper.color.toString() + root.pages.joinToString { it.objectId }).toByteArray())
    fun thumbnail(root: Root): ByteArray? = root.thumbnail?.let(::objectBytes)
    fun saveThumbnail(root: Root, bytes: ByteArray): Root = publish(root.copy(id = "", thumbnail = put(bytes), thumbnailKey = previewKey(root), parents = listOf(root.id)))
    /** Expensive verification is explicit, never part of opening a viewport. */
    fun verify(root: Root, bodies: Boolean = true) {
        val rows = root.pages.flatMap(::rows); require(rows.size == root.count || root.deleted)
        require(rows.all { it.ref.id < root.nextId } && rows.map { it.ref.id }.distinct().size == rows.size && rows.map { it.ref.storageId }.distinct().size == rows.size)
        val (events, entities, records) = history(root); val histories = events.groupBy { it.entity }
        rows.forEach { row -> require(entities[row.ref.id] == row.entity && records[row.ref.storageId] == row.body)
            val frontier = MisqFile.frontier(histories[row.entity].orEmpty()); require(frontier.map { it.version }.toSet() == row.heads.toSet() && frontier.all { it.blob == row.ref.storageId }) }
        val activeIds = rows.map { it.ref.id }.toSet()
        root.tombstones.forEach { (bucket, objectId) -> tombstones(objectId).forEach { dead ->
            require(dead.id / 256 == bucket && dead.id < root.nextId && dead.id !in activeIds && entities[dead.id] == dead.entity)
            val frontier = MisqFile.frontier(histories[dead.entity].orEmpty()); require(frontier.all { it.blob == null } && frontier.map { it.version }.toSet() == dead.heads.toSet())
        } }
        if (bodies) {
            records.entries.groupBy { it.value.objectId }.values.forEach { batch -> readBodies(batch.associate { it.toPair() }) }
            root.thumbnail?.let(::objectBytes)
        }
    }
    fun export(root: Root, target: File, checkpoint: () -> Unit = {}) {
        val manifest = manifest(root).copy(strokes = manifest(root).strokes.sortedBy { it.id })
        val (events, entities, bodies) = history(root)
        val placements = mutableMapOf<String, StrokePlacement>(); val chunks = mutableListOf<JSONObject>(); var cursor = root.history
        while (cursor != null) { val value = json(cursor, "history"); chunks += value; cursor = value.optString("parent").takeIf { it.isNotEmpty() } }
        chunks.asReversed().forEach { chunk -> chunk.optJSONObject("placements")?.let { values -> values.keys().forEach { id ->
            val p = values.getJSONArray(id); placements[id] = StrokePlacement(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.getDouble(2).toFloat())
        } } }
        root.pages.flatMap(::rows).forEach { placements[it.ref.storageId] = it.placement }
        val groups = bodies.entries.groupBy { it.value.objectId }
        var loadedPack: String? = null; var loaded = emptyMap<String, ByteArray>()
        val ordered = groups.values.flatMap { group -> group.map { it.key } }.toCollection(linkedSetOf())
        MisqFile(target).importSnapshot(manifest, events, entities, placements, ordered, thumbnail(root), checkpoint) { id ->
            val pack = bodies.getValue(id).objectId
            if (loadedPack != pack) { loaded = readBodies(groups.getValue(pack).associate { it.toPair() }, checkpoint); loadedPack = pack }
            loaded.getValue(id)
        }
    }
    /** Immutable dependency graph; there is deliberately no physical garbage collection. */
    fun dependencies(id: String): Set<String> {
        val file = objectFile(id)
        file.inputStream().use { if (it.read() != '{'.code) return emptySet() }
        val value = JSONObject(String(objectBytes(id), Charsets.UTF_8))
        require(value.getInt("format") == 3 && value.getInt("minReader") == 3 && value.getInt("minWriter") == 3)
        return when (value.getString("kind")) {
            "root" -> root(id).let { root -> root.pages.map { it.objectId }.toSet() + listOfNotNull(root.history, root.thumbnail) }
            "page" -> value.getJSONArray("rows").let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("object") }.toSet() }
            "history" -> listOfNotNull(value.optString("parent").takeIf { it.isNotEmpty() }).toSet() + value.getJSONObject("bodies").let { bodies -> bodies.keys().asSequence().map { bodies.getJSONObject(it).getString("object") }.toSet() }
            else -> error("Unknown storage object")
        }.also { require(it.all { name -> name.matches(OBJECT) }) }
    }
    fun closure(root: Root, stop: Set<String> = emptySet()): Set<String> {
        val found = linkedSetOf<String>(); val pending = java.util.ArrayDeque<Pair<String, String>>(); pending.add(root.id to "root")
        while (pending.isNotEmpty()) {
            val (id, kind) = pending.removeFirst(); if (!found.add(id)) continue
            if (id in stop) continue
            if (kind == "body") continue
            val value = json(id, kind)
            when (kind) {
                "root" -> root(id).let { r ->
                    require(r.info.id == root.info.id) { "Invalid cross-notebook ancestor" }
                    r.pages.forEach { pending.add(it.objectId to "page") }
                    r.tombstones.values.forEach { pending.add(it to "tombstones") }
                    r.parents.forEach { pending.add(it to "root") }
                    r.history?.let { pending.add(it to "history") }; r.thumbnail?.let { pending.add(it to "body") }
                }
                "page" -> value.getJSONArray("rows").let { rows -> repeat(rows.length()) { pending.add(rows.getJSONObject(it).getString("object") to "body") } }
                "history" -> {
                    value.optString("parent").takeIf { it.isNotEmpty() }?.let { pending.add(it to "history") }
                    value.getJSONObject("bodies").let { records -> records.keys().forEach { pending.add(records.getJSONObject(it).getString("object") to "body") } }
                }
            }
        }
        return found
    }
    companion object {
        private const val PACK_MAGIC = 0x4D535033
        private const val MAX_RECORD = 128 * 1024 * 1024
        private const val PACK_TARGET = 4L * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        internal val OBJECT = Regex("o-[a-f0-9]{64}")
        private val PAGE_KEY = Regex("-?[0-9]+:[0-9]+")
    }
}
