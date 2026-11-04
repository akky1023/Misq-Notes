package jp.handwriting.note.storage

import java.io.*
import java.security.MessageDigest
import java.util.UUID

/** Rebuildable spatial projection of a committed note. Recovery data remains authoritative. */
internal class SpatialNoteCache(private val files: PackedFiles) {
    internal data class Member(val ref: StrokeRef, val placement: StrokePlacement, val size: Int, val hash: String)
    internal data class Pack(val name: String, val members: List<Member>)
    private val verified = mutableSetOf<String>()
    private val knownIndexes = object : LinkedHashMap<String, List<Pack>>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pack>>?) = size > 2
    }
    private fun remember(hash: String, packs: List<Pack>) {
        if (packs.sumOf { it.members.size } <= 20_000) knownIndexes[hash] = packs
    }

    internal class Source(val manifest: NoteManifest, val packs: List<Pack>, private val files: PackedFiles) {
        private val current = manifest.strokes.associate { it.id to it.storageId }
        private fun Member.available(loaded: Set<Long>) = ref.id !in loaded && current[ref.id] == ref.storageId
        fun withManifest(manifest: NoteManifest) = Source(manifest, packs, files)
        val bottom get() = packs.maxOfOrNull { pack -> pack.members.maxOf { it.placement.bottom } } ?: 0f
        fun pending(top: Float, bottom: Float, loaded: Set<Long>) = packs.any { pack ->
            pack.members.any { it.available(loaded) && it.placement.bottom >= top && it.placement.top <= bottom }
        }
        fun <T> load(top: Float, bottom: Float, loaded: Set<Long>, checkpoint: () -> Unit = {}, decode: (StrokeRef, ByteArray) -> T): List<T> {
            val result = mutableListOf<T>()
            for (pack in packs) {
                if (pack.members.none { it.available(loaded) && it.placement.bottom >= top && it.placement.top <= bottom }) continue
                checkpoint()
                result += readPack(pack, loaded, checkpoint, decode)
            }
            return result
        }
        /** One pack at a time lets navigation and editing take priority over prefetch. */
        fun <T> next(top: Float, bottom: Float, loaded: Set<Long>, checkpoint: () -> Unit,
            decode: (StrokeRef, ByteArray) -> T): List<T> {
            val center = (top.toDouble() + bottom.toDouble()) / 2
            val pack = packs.filter { it.members.any { member -> member.available(loaded) &&
                member.placement.bottom >= top && member.placement.top <= bottom } }.minByOrNull { candidate ->
                candidate.members.asSequence().filter { it.available(loaded) }.minOf { member ->
                    when {
                        center < member.placement.top -> member.placement.top - center
                        center > member.placement.bottom -> center - member.placement.bottom
                        else -> 0.0
                    }
                }
            } ?: return emptyList()
            return readPack(pack, loaded, checkpoint, decode)
        }
        private fun <T> readPack(pack: Pack, loaded: Set<Long>, checkpoint: () -> Unit,
            decode: (StrokeRef, ByteArray) -> T): List<T> {
                val result = mutableListOf<T>()
                val digest = MessageDigest.getInstance("SHA-256")
                files.openRead(pack.name).use { raw -> DataInputStream(java.security.DigestInputStream(raw, digest)).use { input ->
                    require(input.readInt() == PACK_MAGIC && input.readInt() == pack.members.size)
                    pack.members.forEach { member ->
                        checkpoint()
                        require(input.readLong() == member.ref.id && input.readUTF() == member.ref.storageId)
                        require(input.readInt() == member.size)
                        val bytes = ByteArray(member.size).also(input::readFully)
                        require(NotebookArchive.digest(bytes) == member.hash)
                        if (member.available(loaded)) result += decode(member.ref, bytes)
                    }
                    require(input.read() == -1)
                } }
                require(NotebookArchive.hex(digest.digest()) == pack.name.takeLast(64)) { "Invalid spatial block" }
                return result
        }
    }

    fun open(hash: String): Source? {
        val names = files.names()
        val name = "index-$hash"
        if (name !in names) return null
        val body = files.openRead(name).use(::unwrap)
        return DataInputStream(ByteArrayInputStream(body)).use { input ->
            require(input.readInt() == 1 && input.readUTF() == hash)
            val headerSize = input.readInt(); require(headerSize in 1..65536)
            val headerBytes = ByteArray(headerSize).also(input::readFully)
            val id = input.readUTF(); validId(id)
            val header = NoteManifestCodec.decodeManifest(id, headerBytes)
            val count = input.readInt(); require(count in 0..1_000_000)
            var total = 0
            val packs = List(count) {
                val packName = input.readUTF(); require(packName.matches(PACK_NAME))
                val size = input.readInt(); require(size in 1..1_000_000 && total + size <= 1_000_000); total += size
                Pack(packName, List(size) {
                    val ref = StrokeRef(input.readLong(), input.readUTF()); validId(ref.storageId)
                    require(ref.id > 0 && ref.id < header.nextId)
                    val placement = StrokePlacement(input.readFloat(), input.readFloat(), input.readFloat())
                    val bytes = input.readInt(); require(bytes in 1..MAX_RECORD)
                    val recordHash = input.readUTF(); require(recordHash.matches(HASH))
                    Member(ref, placement, bytes, recordHash)
                })
            }
            val refs = packs.flatMap { it.members }.map { it.ref }.sortedBy { it.id }
            require(refs.map { it.id }.distinct().size == refs.size && refs.map { it.storageId }.distinct().size == refs.size)
            require(input.read() == -1 && packs.all { it.name in names })
            Source(header.copy(strokes = refs), packs, files)
        }
    }

    /** A failed projection cannot invalidate a successfully committed notebook. */
    fun publish(file: File, hash: String, changes: BlockChanges.Capture? = null) {
        val document = MisqFile(file, readOnlyAlias = true)
        val index = document.index(); val manifest = index.manifest
        if (manifest.strokes.any { it.storageId !in index.placements }) return
        var inventory = files.names()
        // A clean band can reuse its last acknowledged immutable pack even after restart.
        // Loading still verifies every payload; this projection never replaces the recovery data.
        val baseline = changes?.takeIf { it.complete }?.baseline?.let { root ->
            knownIndexes[root] ?: runCatching { open(root)?.packs }.getOrNull()?.also { remember(root, it) }
        }
        val reusable = baseline?.associateBy { NotebookArchive.digest(memberBytes(it.members)) }.orEmpty()
        val members = manifest.strokes.map { ref ->
            val record = index.records.getValue(ref.storageId)
            Member(ref, index.placements.getValue(ref.storageId), record.size, record.hash)
        }
        val groups = mutableListOf<List<Member>>()
        members.groupBy { it.placement.band }.toSortedMap().values.forEach { band ->
            var group = mutableListOf<Member>(); var bytes = 0L
            for (member in band.sortedBy { it.ref.id }) {
                if (group.isNotEmpty() && bytes + member.size > PACK_TARGET) { groups += group; group = mutableListOf(); bytes = 0 }
                group += member; bytes += member.size
            }
            if (group.isNotEmpty()) groups += group
        }
        val packs = groups.map { group ->
            val signature = NotebookArchive.digest(memberBytes(group))
            val clean = changes?.complete == true && group.first().placement.band !in changes.dirty
            val reused = reusable[signature]?.takeIf { clean && it.name in inventory }
            if (reused != null) return@map Pack(reused.name, group)
            val prefix = "region-$signature-"
            val existing = inventory.filter { it.startsWith(prefix) && it.matches(PACK_NAME) }.singleOrNull()
            if (existing != null && (existing in verified || runCatching { files.digest(existing) == existing.takeLast(64) }.getOrDefault(false))) {
                verified += existing
                Pack(existing, group)
            } else {
                val temporary = File(file.parentFile, ".spatial-${UUID.randomUUID()}.tmp")
                try {
                    FileOutputStream(temporary).use { raw -> DataOutputStream(raw).use { output ->
                        output.writeInt(PACK_MAGIC); output.writeInt(group.size)
                        document.readStrokes(group.map { it.ref }) { ref, bytes ->
                            output.writeLong(ref.id); output.writeUTF(ref.storageId); output.writeInt(bytes.size); output.write(bytes)
                        }
                    } }
                    val packHash = NotebookArchive.digest(temporary)
                    val name = prefix + packHash
                    if (name in inventory) files.delete(name) // Repair this exact corrupt cache object.
                    temporary.inputStream().use { files.writeStream(name, it) }
                    check(files.digest(name) == packHash)
                    verified += name; inventory = inventory + name
                    Pack(name, group)
                } finally { temporary.delete() }
            }
        }
        val body = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.writeInt(1); output.writeUTF(hash)
            val header = NoteManifestCodec.encodeManifest(manifest.copy(strokes = emptyList()))
            output.writeInt(header.size); output.write(header); output.writeUTF(manifest.info.id)
            output.writeInt(packs.size)
            packs.forEach { pack -> output.writeUTF(pack.name); output.write(memberBytes(pack.members)) }
        } }.toByteArray()
        val name = "index-$hash"; val bytes = wrap(body)
        if (name in inventory) {
            if (runCatching { files.read(name).contentEquals(bytes) }.getOrDefault(false)) { remember(hash, packs); return }
            files.delete(name)
        }
        files.write(name, bytes); check(files.read(name).contentEquals(bytes))
        remember(hash, packs)
    }

    companion object {
        private const val PACK_MAGIC = 0x4D535031
        private const val ROOT_MAGIC = 0x4D535231
        private const val PACK_TARGET = 4L * 1024 * 1024
        private const val MAX_RECORD = 128 * 1024 * 1024
        private val HASH = Regex("[a-f0-9]{64}")
        private val PACK_NAME = Regex("region-[a-f0-9]{64}-[a-f0-9]{64}")
        private fun validId(id: String) { require(UUID.fromString(id).toString() == id) }
        private fun memberBytes(members: List<Member>): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            output.writeInt(members.size)
            members.forEach { member ->
                output.writeLong(member.ref.id); output.writeUTF(member.ref.storageId)
                output.writeFloat(member.placement.startY); output.writeFloat(member.placement.top); output.writeFloat(member.placement.bottom)
                output.writeInt(member.size); output.writeUTF(member.hash)
            }
        } }.toByteArray()
        private fun wrap(body: ByteArray): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use { output ->
            require(body.size <= 64 * 1024 * 1024)
            output.writeInt(ROOT_MAGIC); output.writeInt(body.size); output.write(body)
            output.write(MessageDigest.getInstance("SHA-256").digest(body))
        } }.toByteArray()
        private fun unwrap(source: InputStream): ByteArray = DataInputStream(source).use { input ->
            require(input.readInt() == ROOT_MAGIC)
            val size = input.readInt(); require(size in 0..64 * 1024 * 1024)
            val body = ByteArray(size).also(input::readFully)
            val expected = ByteArray(32).also(input::readFully)
            require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(expected) && input.read() == -1)
            body
        }
    }
}
