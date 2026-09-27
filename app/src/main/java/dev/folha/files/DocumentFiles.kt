package dev.folha.files

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import dev.folha.editor.Document
import dev.folha.editor.sha256
import java.io.File
import java.io.IOException

class ExternalChangeException : IOException("O arquivo mudou fora do Folha. Sua edição continua protegida.")

class DocumentFiles(private val context: Context) {
    private val resolver = context.contentResolver
    fun persist(uri: Uri, flags: Int) {
        val modes = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 && modes != 0) {
            try { resolver.takePersistableUriPermission(uri, modes) } catch (_: SecurityException) { /* Temporary grants are still useful. */ }
        }
    }
    fun releaseUnusedGrants(keep: Set<String>) {
        resolver.persistedUriPermissions.filterNot { it.uri.toString() in keep }.forEach { permission ->
            val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            runCatching { resolver.releasePersistableUriPermission(permission.uri, flags) }
        }
    }
    fun name(uri: Uri): String = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Sem título.txt"

    fun read(uri: Uri): Document {
        require(uri.scheme == "content") { "Abra o arquivo pelo seletor de documentos do Android." }
        val name = name(uri)
        require(name.endsWith(".txt", true) || name.endsWith(".md", true)) { "Escolha um arquivo .txt ou .md." }
        val bytes = bytes(uri)
        val decoded = TextCodec.decode(bytes)
        val canWrite = context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_WRITE_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED
        return Document(uri = uri.toString(), name = name, content = decoded.text, savedContent = decoded.text,
            diskHash = sha256(bytes), hasBom = decoded.hasBom, canWrite = canWrite)
    }

    fun save(document: Document, uri: Uri, overwrite: Boolean = false): Document {
        val bytes = TextCodec.encode(document.content, document.hasBom) // Validate before opening/truncating.
        if (document.uri == uri.toString() && !overwrite) {
            if (document.diskHash != sha256(bytes(uri))) throw ExternalChangeException()
        }
        // SAF has no cross-provider atomic rename. Recovery must be durable before reaching this call.
        resolver.openOutputStream(uri, "wt")?.use { output -> output.write(bytes); output.flush() }
            ?: throw IOException("O provedor não permitiu gravar este arquivo.")
        val actual = bytes(uri)
        if (!actual.contentEquals(bytes)) throw IOException("Não foi possível verificar a gravação. O recovery foi mantido; use Salvar como.")
        return document.copy(uri = uri.toString(), name = name(uri), savedContent = document.content,
            diskHash = sha256(bytes), canWrite = true)
    }

    private fun bytes(uri: Uri): ByteArray = resolver.openInputStream(uri)?.use(TextCodec::readBounded)
        ?: throw IOException("O arquivo não está disponível. Abra-o novamente pelo seletor.")

    fun share(document: Document): Intent {
        val directory = File(context.cacheDir, "shared").apply { mkdirs() }
        // Keep separate snapshots so an already-open share cannot unexpectedly change underneath its reader.
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1000 }
            ?.forEach { it.deleteRecursively() }
        val target = File(directory, java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        val safeName = dev.folha.editor.documentName(document.name, document.extension)
        val file = File(target, safeName).apply { writeBytes(TextCodec.encode(document.content, document.hasBom)) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = if (document.extension == "md") "text/markdown" else "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(safeName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
