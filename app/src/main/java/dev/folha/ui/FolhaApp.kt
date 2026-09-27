package dev.folha.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folha.editor.*
import dev.folha.preferences.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun FolhaApp(model: EditorViewModel, incoming: StateFlow<Intent?>, consumed: () -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val intent by incoming.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    var menu by remember { mutableStateOf(false) }
    var themeDialog by rememberSaveable { mutableStateOf(false) }
    var saveAs by rememberSaveable { mutableStateOf(false) }
    var info by rememberSaveable { mutableStateOf(false) }
    var focus by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchResult by remember { mutableStateOf<String?>(null) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var afterSave by remember { mutableStateOf<(() -> Unit)?>(null) }
    var editor by remember { mutableStateOf<FolhaEditText?>(null) }
    var pickerId by rememberSaveable { mutableStateOf<String?>(null) }
    val snack = remember { SnackbarHostState() }
    val document = state.document

    fun guard(action: () -> Unit) {
        if (state.busy || !state.ready) return
        if (model.state.value.document?.isModified == true) pendingLeave = action else action()
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) result.data?.let { data ->
            data.data?.let { uri -> model.open(uri, data.flags) }
        }
    }
    val create = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val uri = data?.data
            if (uri != null) model.savePicked(pickerId, uri, data.flags) { afterSave?.invoke(); afterSave = null }
            else model.message("O seletor não retornou um destino. Seu texto foi mantido.")
        } else afterSave = null
        pickerId = null
    }
    fun openPicker() {
        open.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // Some local providers identify .md as application/octet-stream; extension is validated after picking.
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        })
    }
    fun save() {
        if (document == null || state.busy) return
        if (document.isNew || !document.canWrite) saveAs = true
        else model.save(after = { afterSave?.invoke(); afterSave = null })
    }
    fun locate() {
        val doc = model.state.value.document ?: return
        val index = findNext(doc.content, query, doc.selectionEnd)
        if (index < 0) { searchResult = "Nenhuma ocorrência"; return }
        searchResult = null
        model.selection(index, index + query.length)
        editor?.apply {
            requestFocus()
            setSelection(index, index + query.length)
            post { bringPointIntoView(index) }
        }
    }
    LaunchedEffect(intent, state.ready, state.busy) {
        val item = intent ?: return@LaunchedEffect
        if (!state.ready || state.busy) return@LaunchedEffect
        if (item.action in listOf(Intent.ACTION_VIEW, Intent.ACTION_EDIT, Intent.ACTION_SEND)) {
            val uri = if (item.action == Intent.ACTION_SEND) IntentCompat.getParcelableExtra(item, Intent.EXTRA_STREAM, Uri::class.java) else item.data
            val shared = item.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            guard {
                if (uri != null) model.open(uri, item.flags, importCopy = item.action == Intent.ACTION_SEND)
                else if (shared != null && shared.length <= dev.folha.files.TextCodec.MAX_EDITOR_CHARS) model.newDocument(shared)
                else model.message("Não foi possível abrir este conteúdo como texto.")
            }
        }
        consumed()
    }
    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snack.showSnackbar(message, withDismissAction = true)
        model.message(null)
    }
    LaunchedEffect(document?.id) { search = false; focus = false }
    BackHandler(enabled = document != null || state.busy) {
        when {
            state.busy -> Unit
            focus -> focus = false
            search -> search = false
            else -> guard { model.closeDocument() }
        }
    }
    FolhaTheme(state.theme) { dark ->
        SideEffect {
            (context as? Activity)?.let { activity ->
                activity.setTheme(if (dark) dev.folha.R.style.Theme_Folha_Dark else dev.folha.R.style.Theme_Folha)
                val window = activity.window
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
        Scaffold(
            modifier = Modifier.fillMaxSize().imePadding(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                if (!focus) CenterAlignedTopAppBar(
                    title = {
                        Text(if (document == null) "Folha" else document.name + if (document.isModified) " •" else "",
                            maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 16.sp,
                            fontWeight = FontWeight.Medium)
                    },
                    navigationIcon = {
                        if (document != null) IconButton(enabled = !state.busy, onClick = { guard { model.closeDocument() } }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar")
                        }
                    },
                    actions = {
                        IconButton(onClick = { menu = true }, enabled = state.ready && !state.busy) { Icon(Icons.Default.MoreVert, "Menu") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            fun action(label: String, block: () -> Unit): @Composable () -> Unit = {
                                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; block() })
                            }
                            if (document != null) {
                                action("Salvar") { save() }()
                                action("Salvar como…") { afterSave = null; saveAs = true }()
                                action("Localizar") { search = !search }()
                                action("Modo Foco") { search = false; focus = true; model.message("Modo Foco: use Voltar para sair. Com o teclado aberto, volte duas vezes.") }()
                                action("Informações") { info = true }()
                                action("Compartilhar") { model.share { context.startActivity(Intent.createChooser(it, "Compartilhar texto")) } }()
                                HorizontalDivider()
                                action("Novo texto") { guard { model.newDocument() } }()
                                action("Abrir arquivo") { guard { openPicker() } }()
                            }
                            action("Tema") { themeDialog = true }()
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy || !state.ready) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (document == null) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 28.dp, vertical = 32.dp)) {
                        item {
                            Text("Uma folha.\nSuas palavras.", fontSize = 32.sp, lineHeight = 40.sp, fontWeight = FontWeight.Normal)
                            Spacer(Modifier.height(12.dp))
                            Text("Escreva alguma coisa.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                            Spacer(Modifier.height(32.dp))
                            TextButton(onClick = { model.newDocument() }, enabled = state.ready && !state.busy, contentPadding = PaddingValues(vertical = 14.dp)) { Text("Novo texto", fontSize = 18.sp) }
                            TextButton(onClick = { openPicker() }, enabled = state.ready && !state.busy, contentPadding = PaddingValues(vertical = 14.dp)) { Text("Abrir arquivo", fontSize = 18.sp) }
                            Spacer(Modifier.height(36.dp))
                            if (state.recents.isNotEmpty()) Text("RECENTES", style = MaterialTheme.typography.labelSmall, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(12.dp))
                        }
                        items(state.recents, key = { it.uri }) { recent ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(modifier = Modifier.weight(1f), onClick = { model.open(recent.uri.toUri()) }, enabled = !state.busy,
                                    contentPadding = PaddingValues(vertical = 12.dp)) {
                                    Text(recent.name, Modifier.fillMaxWidth(), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(enabled = !state.busy, onClick = { model.removeRecent(recent.uri) }) { Icon(Icons.Default.Close, "Remover ${recent.name} dos recentes", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                } else {
                    if (search) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(value = query, onValueChange = { query = it; searchResult = null }, label = { Text("Localizar texto") },
                                singleLine = true, modifier = Modifier.weight(1f), supportingText = searchResult?.let { { Text(it) } })
                            TextButton(onClick = { locate() }, enabled = query.isNotEmpty()) { Text("Próxima") }
                            IconButton(onClick = { search = false }) { Icon(Icons.Default.Close, "Fechar busca") }
                        }
                    }
                    key(document.id) {
                        NativeEditor(document, !state.busy, model::edit, model::selection,
                            onReady = { editor = it }, onLimit = { model.message("O texto ultrapassa o limite do editor. A inserção foi cancelada por inteiro.") }, onShortcut = { code, shift ->
                                when (code) {
                                    KeyEvent.KEYCODE_S -> { if (shift) saveAs = true else save(); true }
                                    KeyEvent.KEYCODE_F -> { search = true; true }
                                    else -> false
                                }
                            })
                    }
                }
            }
        }
        if (themeDialog) AlertDialog(onDismissRequest = { themeDialog = false }, title = { Text("Tema") }, text = {
            Column {
                listOf(ThemeMode.SYSTEM to "Seguir o sistema", ThemeMode.LIGHT to "Claro", ThemeMode.DARK to "Escuro").forEach { (mode, label) ->
                    TextButton(onClick = { model.setTheme(mode); themeDialog = false }, modifier = Modifier.fillMaxWidth()) {
                        RadioButton(selected = state.theme == mode, onClick = null)
                        Spacer(Modifier.width(12.dp)); Text(label, Modifier.weight(1f))
                    }
                }
            }
        }, confirmButton = {})
        if (saveAs && document != null) SaveAsDialog(document, onDismiss = { saveAs = false; afterSave = null }) { name, extension ->
            saveAs = false
            pickerId = document.id
            create.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = if (extension == "md") "text/markdown" else "text/plain"
                putExtra(Intent.EXTRA_TITLE, name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            })
        }
        if (pendingLeave != null) AlertDialog(onDismissRequest = { pendingLeave = null },
            title = { Text("Salvar alterações?") }, text = { Text("Há texto não salvo. Salve antes de continuar ou descarte estas alterações.") },
            confirmButton = { TextButton(onClick = { afterSave = pendingLeave; pendingLeave = null; save() }) { Text("Salvar") } },
            dismissButton = { Row {
                TextButton(onClick = { val action = pendingLeave; pendingLeave = null; action?.invoke() }) { Text("Descartar") }
                TextButton(onClick = { pendingLeave = null }) { Text("Cancelar") }
            } })
        if (state.conflict) AlertDialog(onDismissRequest = { model.cancelConflict(); afterSave = null },
            title = { Text("O arquivo mudou") }, text = { Text("Outro aplicativo alterou o arquivo desde a abertura. Salvar uma cópia preserva as duas versões.") },
            confirmButton = { TextButton(onClick = { model.cancelConflict(); saveAs = true }) { Text("Salvar cópia") } },
            dismissButton = { TextButton(onClick = { model.cancelConflict(); afterSave = null }) { Text("Cancelar") } })
        if (info && document != null) {
            val stats by produceState<TextStats?>(null, document.content) { value = withContext(Dispatchers.Default) { textStats(document.content) } }
            AlertDialog(onDismissRequest = { info = false }, title = { Text("Informações") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(document.name)
                    stats?.let { Text("${it.characters} caracteres\n${it.words} palavras\n${it.lines} linhas") }
                    Text("UTF-8${if (document.hasBom) " com BOM" else ""} · Texto puro")
                    Text(if (document.isNew) "Rascunho local · ainda sem arquivo" else if (document.canWrite) "Arquivo editável" else "Origem somente leitura · use Salvar como")
                }
            }, confirmButton = { TextButton(onClick = { info = false }) { Text("Fechar") } })
        }
    }
}

@Composable private fun SaveAsDialog(document: Document, onDismiss: () -> Unit, onSave: (String, String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(document.name.substringBeforeLast('.')) }
    var extension by rememberSaveable { mutableStateOf(document.extension) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Salvar como") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome do arquivo") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("txt", "md").forEach { ext -> FilterChip(selected = extension == ext, onClick = { extension = ext }, label = { Text(".$ext") }) }
            }
            Text("${documentName(name, extension)}\nEscolha o destino na próxima tela.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { onSave(documentName(name, extension), extension) }) { Text("Escolher destino") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } })
}
