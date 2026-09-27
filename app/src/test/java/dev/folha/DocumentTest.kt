package dev.folha

import dev.folha.editor.*
import dev.folha.files.*
import dev.folha.preferences.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class DocumentTest {
    @Test fun dirtyTracksActualContentIncludingUndo() {
        val original = Document(content = "ação\n", savedContent = "ação\n")
        assertFalse(original.isModified)
        val changed = original.copy(content = "ação!\n")
        assertTrue(changed.isModified)
        assertFalse(changed.copy(content = original.content).isModified)
        assertFalse(Document().isModified)
        assertTrue(Document(content = "rascunho").isModified)
    }
    @Test fun utf8RoundTripPreservesAccentsEmojiBomAndLineEndings() {
        for (bom in listOf(true, false)) {
            val original = "ação, coração, 日本語, 🌱\r\nlinha dois\núltima\r"
            val bytes = TextCodec.encode(original, bom)
            val result = TextCodec.decode(bytes)
            assertEquals(original, result.text)
            assertEquals(bom, result.hasBom)
            assertArrayEquals(bytes, TextCodec.encode(result.text, result.hasBom))
        }
    }
    @Test(expected = java.nio.charset.CharacterCodingException::class)
    fun malformedUtf8IsNeverSilentlyReplaced() { TextCodec.decode(byteArrayOf(0xC3.toByte(), 0x28)) }
    @Test(expected = IllegalArgumentException::class)
    fun binaryIsRejected() { TextCodec.decode(byteArrayOf(0, 65)) }
    @Test(expected = IllegalArgumentException::class)
    fun cannotWriteBinaryContentThatTheEditorWouldRejectOnReopen() { TextCodec.encode("a\u0000b", false) }
    @Test(expected = IllegalArgumentException::class)
    fun oversizedInputRejectedEvenWhenProviderDoesNotSupplySize() {
        TextCodec.readBounded(ByteArrayInputStream(ByteArray(TextCodec.MAX_FILE_BYTES + 1)))
    }
    @Test fun boundarySizeAccepted() {
        assertEquals(TextCodec.MAX_FILE_BYTES, TextCodec.readBounded(ByteArrayInputStream(ByteArray(TextCodec.MAX_FILE_BYTES) { 65 })).size)
    }
    @Test fun extensionsAreExplicitAndNamesSafe() {
        assertEquals("Sem título.md", documentName(".md", "md"))
        assertEquals("notas.md", documentName(" notas.TXT ", "md"))
        assertEquals("ideias.txt", documentName("ideias.md", "txt"))
        assertEquals("Sem título.md", documentName("  ", "md"))
        assertEquals("a_b_c.txt", documentName("a/b\\c", "txt"))
    }
    @Test fun recoveryRoundTripsLargeUnsavedTextAndOriginalState() {
        val directory = Files.createTempDirectory("folha-test").toFile()
        try {
            val original = Document(uri = "content://test/document", content = "á🌱\r\n".repeat(20_000), savedContent = "original",
                diskHash = "old-hash", hasBom = true, selectionStart = 80, selectionEnd = 95, canWrite = false)
            DraftStore(directory).write(original, 1)
            assertEquals(original, DraftStore(directory).read())
        } finally { directory.deleteRecursively() }
    }
    @Test fun staleCheckpointCannotOverwriteNewerLifecycleSnapshot() {
        val directory = Files.createTempDirectory("folha-test").toFile()
        try {
            val store = DraftStore(directory)
            store.write(Document(content = "mais recente"), 3)
            store.write(Document(content = "antigo"), 2)
            assertEquals("mais recente", store.read()?.content)
            store.write(null, 4)
            store.write(Document(content = "antigo"), 3)
            assertNull(DraftStore(directory).read())
        } finally { directory.deleteRecursively() }
    }
    @Test fun corruptLatestSnapshotFallsBackToPreviousCompleteSnapshot() {
        val directory = Files.createTempDirectory("folha-test").toFile()
        try {
            val store = DraftStore(directory)
            store.write(Document(content = "primeiro"), 1)
            store.write(Document(content = "segundo"), 2)
            File(directory, "recovery.bin").writeBytes(byteArrayOf(1))
            assertEquals("primeiro", DraftStore(directory).read()?.content)
        } finally { directory.deleteRecursively() }
    }
    @Test fun recentsDeduplicateByUriMoveToFrontAndRemainBounded() {
        val items = (1..12).map { RecentFile("uri:$it", "$it.txt") }
        val updated = updateRecents(items, RecentFile("uri:7", "renamed.md"))
        assertEquals(12, updated.size)
        assertEquals(RecentFile("uri:7", "renamed.md"), updated.first())
        assertEquals(1, updated.count { it.uri == "uri:7" })
        assertEquals(12, updateRecents(updated, RecentFile("new", "new.txt")).size)
    }
    @Test fun literalSearchWrapsAndDoesNotInterpretRegex() {
        assertEquals(0, findNext("ação abc AÇÃO", "AÇÃO", 10))
        assertEquals(9, findNext("ação abc AÇÃO", "ação", 4))
        assertEquals(2, findNext("a .* b", ".*", 0))
        assertEquals(-1, findNext("abc", "", 0))
    }
    @Test fun countsUnicodeCodePointsAndCrLfAsOneLineBreak() {
        assertEquals(TextStats(8, 3, 2), textStats("olá 🌱\r\nx"))
        assertEquals(TextStats(0, 0, 1), textStats(""))
        assertEquals(3, textStats("um\u00a0dois\ttrês").words)
    }
}
