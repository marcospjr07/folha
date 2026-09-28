package dev.folha.ui

import android.graphics.Typeface
import android.text.InputType
import android.text.InputFilter
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.widget.doAfterTextChanged
import dev.folha.editor.Document
import dev.folha.files.TextCodec

class FolhaEditText(context: android.content.Context) : EditText(context) {
    var selectionChanged: ((Int, Int) -> Unit)? = null
    var shortcut: ((Int, Boolean) -> Boolean)? = null
    var updating = false
    override fun onSelectionChanged(start: Int, end: Int) {
        super.onSelectionChanged(start, end)
        // Search, hardware keys and selection handles must take precedence over an active fling.
        (parent as? EditorViewport)?.stopFling()
        if (!updating) selectionChanged?.invoke(start, end)
    }
    override fun onKeyShortcut(keyCode: Int, event: KeyEvent): Boolean {
        if (event.isCtrlPressed && shortcut?.invoke(keyCode, event.isShiftPressed) == true) return true
        return super.onKeyShortcut(keyCode, event)
    }
}

@Composable fun NativeEditor(
    document: Document, enabled: Boolean,
    onEdit: (String, Int, Int) -> Unit, onSelection: (Int, Int) -> Unit,
    onShortcut: (Int, Boolean) -> Boolean, onLimit: () -> Unit, onReady: (FolhaEditText) -> Unit,
) {
    val edit by rememberUpdatedState(onEdit)
    val select by rememberUpdatedState(onSelection)
    val shortcut by rememberUpdatedState(onShortcut)
    val limit by rememberUpdatedState(onLimit)
    val colors = MaterialTheme.colorScheme
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
        val editor = FolhaEditText(context).apply {
            id = android.view.View.generateViewId()
            // The ViewModel + durable recovery own the buffer, not the Activity's size-limited Bundle.
            isSaveEnabled = false
            background = null
            gravity = Gravity.TOP or Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 17f
            setLineSpacing(0f, 1.3f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
            val padding = (22 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding / 2, padding, padding)
            filters = arrayOf(InputFilter { source, start, end, destination, from, to ->
                if (destination.length - (to - from) + (end - start) > TextCodec.MAX_EDITOR_CHARS) {
                    limit()
                    destination.subSequence(from, to) // Reject the whole edit; never silently truncate pasted text.
                } else null
            })
            hint = "Escreva alguma coisa…"
            contentDescription = "Editor de texto"
            // Hydrate once per document/View instance. Never feed asynchronous Compose snapshots
            // back into the live Editable: stale snapshots can erase fast IME/keyboard input.
            updating = true
            setText(document.content)
            setSelection(document.selectionStart.coerceIn(0, length()), document.selectionEnd.coerceIn(0, length()))
            updating = false
            selectionChanged = { start, end -> select(start, end) }
            this.shortcut = { key, shift -> shortcut(key, shift) }
            doAfterTextChanged {
                if (!updating) {
                    val content = it.toString()
                    edit(content, selectionStart, selectionEnd)
                }
            }
            onReady(this)
        }
        EditorViewport(context, editor)
    }, update = { viewport ->
        val view = viewport.editor
        view.updating = true
        view.isEnabled = enabled
        view.setTextColor(colors.onSurface.toArgb())
        view.setHintTextColor(colors.onSurfaceVariant.toArgb())
        view.highlightColor = colors.primary.copy(alpha = 0.30f).toArgb()
        if (android.os.Build.VERSION.SDK_INT >= 29) view.textCursorDrawable?.setTint(colors.primary.toArgb())
        view.updating = false
    })
}
