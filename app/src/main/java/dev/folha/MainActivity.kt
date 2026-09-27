package dev.folha

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.folha.editor.EditorViewModel
import dev.folha.ui.FolhaApp
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {
    private val model: EditorViewModel by viewModels()
    private val incoming = MutableStateFlow<Intent?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        val theme = dev.folha.preferences.Preferences(applicationContext).theme
        val systemDark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        setTheme(if (theme == dev.folha.preferences.ThemeMode.DARK || (theme == dev.folha.preferences.ThemeMode.SYSTEM && systemDark)) R.style.Theme_Folha_Dark else R.style.Theme_Folha)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null && !model.incomingIntentConsumed) incoming.value = intent
        setContent { FolhaApp(model, incoming) { incoming.value = null; model.incomingIntentConsumed = true } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incoming.value = intent
    }
    override fun onPause() {
        model.flushRecovery()
        super.onPause()
    }
}
