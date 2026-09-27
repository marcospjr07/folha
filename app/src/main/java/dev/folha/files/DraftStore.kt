package dev.folha.files

import dev.folha.editor.Document
import java.io.*
import java.nio.file.Files
import java.nio.file.StandardCopyOption.*

/** A durable snapshot, independent of the original document. Writes are ordered even across lifecycle flushes. */
class DraftStore(private val directory: File) {
    private val current = File(directory, "recovery.bin")
    private val backup = File(directory, "recovery.previous")
    private var lastSequence = -1L

    @Synchronized fun write(document: Document?, sequence: Long) {
        if (sequence <= lastSequence) return
        directory.mkdirs()
        val temporary = File(directory, "recovery.tmp")
        // A tombstone is also atomic; an interrupted delete must not resurrect a discarded document.
        FileOutputStream(temporary).use { file ->
            val out = DataOutputStream(BufferedOutputStream(file))
            out.writeInt(1)
            out.writeBoolean(document != null)
            if (document != null) {
                out.string(document.id); out.string(document.uri.orEmpty()); out.string(document.name)
                out.string(document.content); out.string(document.savedContent); out.string(document.diskHash.orEmpty())
                out.writeBoolean(document.hasBom); out.writeBoolean(document.canWrite)
                out.writeInt(document.selectionStart); out.writeInt(document.selectionEnd)
            }
            out.flush()
            file.fd.sync()
        }
        if (current.exists()) Files.copy(current.toPath(), backup.toPath(), REPLACE_EXISTING)
        Files.move(temporary.toPath(), current.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        lastSequence = sequence
    }

    @Synchronized fun read(): Document? {
        if (!current.exists()) return null
        return try { decode(current) } catch (failure: Exception) {
            // Retain damaged data for diagnosis; never replace the only copy with an empty buffer.
            Files.copy(current.toPath(), File(directory, "recovery-damaged-${System.currentTimeMillis()}.bin").toPath(), REPLACE_EXISTING)
            if (backup.exists()) {
                val previous = decode(backup)
                Files.copy(backup.toPath(), current.toPath(), REPLACE_EXISTING)
                previous
            } else throw IOException("Não foi possível ler o recovery local.", failure)
        }
    }

    private fun decode(file: File): Document? = DataInputStream(BufferedInputStream(FileInputStream(file))).use { input ->
        require(input.readInt() == 1) { "Versão de recovery desconhecida" }
        if (!input.readBoolean()) return null
        Document(
            id = input.string(), uri = input.string().ifEmpty { null }, name = input.string(),
            content = input.string(), savedContent = input.string(), diskHash = input.string().ifEmpty { null },
            hasBom = input.readBoolean(), canWrite = input.readBoolean(),
            selectionStart = input.readInt(), selectionEnd = input.readInt(),
        )
    }
    private fun DataOutputStream.string(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeInt(bytes.size); write(bytes)
    }
    private fun DataInputStream.string(): String {
        val count = readInt()
        require(count in 0..(TextCodec.MAX_EDITOR_CHARS * 4)) { "Recovery inválido" }
        val bytes = ByteArray(count)
        readFully(bytes)
        return Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }
}
