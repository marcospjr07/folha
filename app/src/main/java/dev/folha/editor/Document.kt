package dev.folha.editor

import java.security.MessageDigest
import java.util.UUID

data class Document(
    val id: String = UUID.randomUUID().toString(),
    val uri: String? = null,
    val name: String = "Sem título.txt",
    val content: String = "",
    val savedContent: String = "",
    val diskHash: String? = null,
    val hasBom: Boolean = false,
    val canWrite: Boolean = true,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
) {
    val isModified: Boolean get() = content != savedContent
    val isNew: Boolean get() = uri == null
    val extension: String get() = if (name.endsWith(".md", true)) "md" else "txt"
}

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }

fun documentName(input: String, extension: String): String {
    val clean = input.trim().replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(120)
        .let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
        .ifBlank { "Sem título" }
    val stem = clean.replace(Regex("\\.(txt|md)$", RegexOption.IGNORE_CASE), "").ifBlank { "Sem título" }
    return "$stem.${if (extension == "md") "md" else "txt"}"
}

data class TextStats(val characters: Int, val words: Int, val lines: Int)
fun textStats(text: String): TextStats {
    var words = 0
    var insideWord = false
    var lines = 1
    var previous = -1
    val iterator = text.codePoints().iterator()
    while (iterator.hasNext()) {
        val c = iterator.nextInt()
        val space = Character.isWhitespace(c) || Character.isSpaceChar(c)
        if (!space && !insideWord) words++
        insideWord = !space
        if (c == 13 || (c == 10 && previous != 13)) lines++
        previous = c
    }
    return TextStats(text.codePointCount(0, text.length), words, lines)
}

/** Literal search; no regex interpretation and no allocation of all matches. */
fun findNext(text: String, query: String, start: Int): Int {
    if (query.isEmpty()) return -1
    val found = text.indexOf(query, start.coerceIn(0, text.length), ignoreCase = true)
    return if (found >= 0) found else text.indexOf(query, ignoreCase = true)
}
