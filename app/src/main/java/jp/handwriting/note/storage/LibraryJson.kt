package jp.handwriting.note.storage

import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*
import java.util.zip.CRC32
import org.json.JSONArray
import org.json.JSONObject

/** Portable hierarchy. Device preferences stay in library.hwp. */
internal object LibraryJson {
    fun encode(value: LibraryPreferences): JSONObject = JSONObject().put("format", 1)
        .put("folders", JSONArray().also { array -> value.folders.forEach { folder -> array.put(JSONObject()
            .put("id", folder.id).put("name", folder.name).put("parent", folder.parent ?: JSONObject.NULL).put("color", folder.color)) } })
        .put("locations", JSONObject(value.locations)).put("sorts", JSONObject(value.sorts.mapValues { it.value.ordinal }))
        .put("orders", JSONObject().also { obj -> value.orders.forEach { (id, order) -> obj.put(id, JSONArray(order)) } })
    fun decode(obj: JSONObject, settings: LibraryPreferences = LibraryPreferences()): LibraryPreferences {
        require(obj.getInt("format") == 1)
        val folders = obj.getJSONArray("folders").let { array -> List(array.length()) { i ->
            val folder = array.getJSONObject(i)
            NoteFolder(folder.getString("id"), folder.getString("name"), if (folder.isNull("parent")) null else folder.getString("parent"), folder.getInt("color"))
        } }
        val locations = obj.getJSONObject("locations").let { obj -> obj.keys().asSequence().associateWith { obj.getString(it) } }
        val sorts = obj.getJSONObject("sorts").let { obj -> obj.keys().asSequence().associateWith { NoteSort.entries[obj.getInt(it)] } }
        val orders = obj.getJSONObject("orders").let { obj -> obj.keys().asSequence().associateWith { key ->
            obj.getJSONArray(key).let { array -> List(array.length()) { array.getString(it) } }
        } }
        return settings.copy(folders = folders, locations = locations, sorts = sorts, orders = orders)
    }
    fun write(file: File, value: LibraryPreferences) = writeRaw(file, encode(value).toString(2).toByteArray())
    fun writeRaw(file: File, bytes: ByteArray) {
        val temp = File(file.path + ".tmp"); file.parentFile!!.mkdirs()
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        if (file.exists() && runCatching { decode(JSONObject(file.readText())) }.isSuccess) {
            val backupTemp = File(file.path + ".bak.tmp")
            FileOutputStream(backupTemp).use { it.write(file.readBytes()); it.fd.sync() }
            Files.move(backupTemp.toPath(), File(file.path + ".bak").toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        }
        Files.move(temp.toPath(), file.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
    }
    fun read(file: File, settings: LibraryPreferences): LibraryPreferences = try {
        decode(JSONObject(file.readText()), settings)
    } catch (_: Exception) { decode(JSONObject(File(file.path + ".bak").readText()), settings) }
    internal fun envelope(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { buffer -> DataOutputStream(buffer).use {
        it.writeInt(0x484E4231); it.writeInt(bytes.size); it.write(bytes); it.writeLong(CRC32().also { crc -> crc.update(bytes) }.value)
    } }.toByteArray()
    internal fun unwrap(bytes: ByteArray): ByteArray = DataInputStream(ByteArrayInputStream(bytes)).use {
        require(it.readInt() == 0x484E4231); val size = it.readInt(); require(size >= 0 && size == bytes.size - 16)
        ByteArray(size).also { body -> it.readFully(body); require(it.readLong() == CRC32().also { crc -> crc.update(body) }.value) }
    }
}
