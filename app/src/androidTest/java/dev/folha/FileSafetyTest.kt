package dev.folha

import android.content.Context
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.folha.files.DocumentFiles
import dev.folha.files.ExternalChangeException
import dev.folha.editor.Document
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class FileSafetyTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun fixture(name: String, text: String): Pair<File, android.net.Uri> {
        val file = File(context.cacheDir, "shared/tests/$name").apply { parentFile!!.mkdirs(); writeText(text, Charsets.UTF_8) }
        return file to FileProvider.getUriForFile(context, "dev.folha.share", file)
    }
    @Test fun openEditSaveAndTruncateWithRealContentResolver() {
        val (file, uri) = fixture("notas.txt", "ação 🌱\r\ntexto que será removido")
        val files = DocumentFiles(context)
        val original = files.read(uri)
        assertFalse(original.isModified)
        val saved = files.save(original.copy(content = "olá 🌱\r\n"), uri)
        assertEquals("olá 🌱\r\n", file.readText())
        assertFalse(saved.isModified)
        assertEquals(saved.content, files.read(uri).content)
    }
    @Test fun externalChangesAreNeverOverwritten() {
        val (file, uri) = fixture("conflito.md", "# Original")
        val files = DocumentFiles(context)
        val document = files.read(uri).copy(content = "# Minha edição")
        file.writeText("# Alteração externa")
        try { files.save(document, uri); fail("Expected conflict") } catch (_: ExternalChangeException) { }
        assertEquals("# Alteração externa", file.readText())
        assertEquals("# Minha edição", document.content)
    }
    @Test fun saveAsBothExtensionsKeepsExactMarkdownAndUnicode() {
        val files = DocumentFiles(context)
        for (extension in listOf("txt", "md")) {
            val (file, uri) = fixture("copia.$extension", "")
            val doc = Document(content = "# Ação\n\n**texto** 日本語 🌱\n")
            val saved = files.save(doc, uri)
            assertEquals(doc.content, file.readText())
            assertEquals(extension, saved.extension)
            assertFalse(saved.isModified)
        }
    }
    @Test fun invalidUtf8AndOversizedEditsCannotTruncateFile() {
        val (file, uri) = fixture("seguro.txt", "intacto")
        val files = DocumentFiles(context)
        val document = files.read(uri)
        try { files.save(document.copy(content = "á".repeat(2 * 1024 * 1024)), uri); fail("Expected size limit") }
        catch (_: IllegalArgumentException) { }
        assertEquals("intacto", file.readText())
        file.writeBytes(byteArrayOf(0xFF.toByte()))
        try { files.read(uri); fail("Expected UTF-8 error") } catch (_: java.nio.charset.CharacterCodingException) { }
        assertArrayEquals(byteArrayOf(0xFF.toByte()), file.readBytes())
    }
}
