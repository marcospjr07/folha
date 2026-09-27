package dev.folha.editor

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.folha.files.*
import dev.folha.preferences.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.nio.charset.CharacterCodingException
import java.util.concurrent.atomic.AtomicLong

data class EditorState(
    val document: Document? = null,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val recents: List<RecentFile> = emptyList(),
    val message: String? = null,
    val conflict: Boolean = false,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = Preferences(application)
    private val files = DocumentFiles(application)
    private val drafts = DraftStore(File(application.filesDir, "drafts"))
    private val sequence = AtomicLong()
    private val checkpoints = Channel<Pair<Long, Document?>>(Channel.CONFLATED)
    private val _state = MutableStateFlow(EditorState(theme = preferences.theme, recents = preferences.recents()))
    val state = _state.asStateFlow()
    var incomingIntentConsumed = false
    // Picker results after process recreation may save the restored document, but never a different session.
    init {
        viewModelScope.launch {
            var refreshFailed = false
            val restored = runCatching { withContext(Dispatchers.IO) {
                val recovered = drafts.read()
                if (recovered != null && !recovered.isModified && recovered.uri != null) {
                    try { files.read(Uri.parse(recovered.uri)).copy(id = recovered.id,
                        selectionStart = recovered.selectionStart, selectionEnd = recovered.selectionEnd) }
                    catch (_: Exception) { refreshFailed = true; recovered.copy(canWrite = false) }
                } else recovered
            } }
            _state.value = _state.value.copy(ready = true, document = restored.getOrNull(),
                message = if (restored.isFailure) "O recovery não pôde ser lido. Os arquivos locais foram preservados."
                else if (refreshFailed) "Cópia local aberta. A origem está indisponível; use Salvar como ou abra o arquivo novamente."
                else if (restored.getOrNull()?.isModified == true) "Rascunho recuperado. O arquivo original não foi alterado." else null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            for ((version, document) in checkpoints) {
                try { drafts.write(document, version) }
                catch (_: Exception) { withContext(Dispatchers.Main) { message("Não foi possível proteger o rascunho local. Salve uma cópia agora.") } }
            }
        }
    }
    fun message(text: String?) { _state.value = _state.value.copy(message = text) }
    private fun checkpoint() { checkpoints.trySend(sequence.incrementAndGet() to _state.value.document) }
    /** Called on pause: finishes a durable snapshot before Android can kill the background process. */
    fun flushRecovery() {
        if (!_state.value.ready) return
        try { drafts.write(_state.value.document, sequence.incrementAndGet()) }
        catch (_: Exception) { message("Falha no recovery local. Salve uma cópia do texto.") }
    }
    fun edit(content: String, start: Int, end: Int) {
        val document = _state.value.document ?: return
        _state.value = _state.value.copy(document = document.copy(content = content, selectionStart = start, selectionEnd = end))
        checkpoint()
    }
    fun selection(start: Int, end: Int) {
        val document = _state.value.document ?: return
        if (document.selectionStart == start && document.selectionEnd == end) return
        _state.value = _state.value.copy(document = document.copy(selectionStart = start, selectionEnd = end))
    }
    fun newDocument(content: String = "") {
        _state.value = _state.value.copy(document = Document(content = content), conflict = false)
        checkpoint()
    }
    fun closeDocument() {
        _state.value = _state.value.copy(document = null, conflict = false)
        checkpoint()
    }
    fun open(uri: Uri, flags: Int = 0) = operation {
        val document = withContext(Dispatchers.IO) { files.persist(uri, flags); files.read(uri) }
        _state.value = _state.value.copy(document = document, conflict = false)
        remember(document)
        checkpoint()
    }
    fun save(uri: Uri? = null, flags: Int = 0, overwrite: Boolean = false, after: (() -> Unit)? = null) = operation {
        val document = _state.value.document ?: return@operation
        val target = uri ?: document.uri?.let(Uri::parse) ?: return@operation
        val version = sequence.incrementAndGet()
        val saved = withContext(Dispatchers.IO) {
            drafts.write(document, version) // Never truncate the destination before recovery is durable.
            files.persist(target, flags)
            files.save(document, target, overwrite)
        }
        _state.value = _state.value.copy(document = saved, conflict = false)
        remember(saved)
        checkpoint()
        message("Arquivo salvo")
        after?.invoke()
    }
    fun cancelConflict() { _state.value = _state.value.copy(conflict = false) }
    fun setTheme(theme: ThemeMode) {
        preferences.theme = theme
        _state.value = _state.value.copy(theme = theme)
    }
    private suspend fun remember(document: Document) {
        val uri = document.uri ?: return
        val recents = updateRecents(_state.value.recents, RecentFile(uri, document.name))
        preferences.saveRecents(recents)
        releaseUnusedGrants(recents)
        _state.value = _state.value.copy(recents = recents)
    }
    fun removeRecent(uri: String) = operation {
        val recents = _state.value.recents.filterNot { it.uri == uri }
        preferences.saveRecents(recents)
        releaseUnusedGrants(recents)
        _state.value = _state.value.copy(recents = recents)
    }
    private suspend fun releaseUnusedGrants(recents: List<RecentFile>) {
        val keep = recents.map { it.uri }.toSet() + listOfNotNull(_state.value.document?.uri)
        withContext(Dispatchers.IO) { files.releaseUnusedGrants(keep) }
    }
    fun share(onReady: (Intent) -> Unit) = operation {
        val document = _state.value.document ?: return@operation
        onReady(withContext(Dispatchers.IO) { files.share(document) })
    }
    private fun operation(block: suspend () -> Unit) {
        if (_state.value.busy) return
        _state.value = _state.value.copy(busy = true)
        viewModelScope.launch {
            try { block() }
            catch (cancel: CancellationException) { throw cancel }
            catch (_: ExternalChangeException) { _state.value = _state.value.copy(conflict = true) }
            catch (_: CharacterCodingException) { message("Este arquivo não é UTF-8 válido. Ele não foi alterado.") }
            catch (_: SecurityException) { message("O acesso ao arquivo expirou ou é somente leitura. Abra-o novamente ou use Salvar como.") }
            catch (error: Exception) { message(error.message ?: "Não foi possível concluir. Seu texto foi mantido.") }
            finally { _state.value = _state.value.copy(busy = false) }
        }
    }
}
