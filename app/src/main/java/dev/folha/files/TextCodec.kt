package dev.folha.files

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

object TextCodec {
    const val MAX_FILE_BYTES = 2 * 1024 * 1024
    const val MAX_EDITOR_CHARS = 2 * 1024 * 1024
    private val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    data class Decoded(val text: String, val hasBom: Boolean)
    fun readBounded(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (output.size() <= MAX_FILE_BYTES) {
            val count = input.read(chunk, 0, minOf(chunk.size, MAX_FILE_BYTES + 1 - output.size()))
            if (count < 0) break
            if (count == 0) break
            output.write(chunk, 0, count)
        }
        val bytes = output.toByteArray()
        require(bytes.size <= MAX_FILE_BYTES) { "Este arquivo excede o limite de 2 MiB desta versão." }
        return bytes
    }
    fun decode(bytes: ByteArray): Decoded {
        val hasBom = bytes.size >= 3 && bytes.take(3).toByteArray().contentEquals(bom)
        val offset = if (hasBom) 3 else 0
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
        require('\u0000' !in text) { "O arquivo contém dados binários ou usa uma codificação diferente de UTF-8." }
        return Decoded(text, hasBom)
    }
    fun encode(text: String, hasBom: Boolean): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(java.nio.CharBuffer.wrap(text))
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        require(bytes.size + (if (hasBom) 3 else 0) <= MAX_FILE_BYTES) {
            "O texto excede 2 MiB em UTF-8. Divida-o antes de salvar; o rascunho local continua protegido."
        }
        return if (hasBom) bom + bytes else bytes
    }
}
