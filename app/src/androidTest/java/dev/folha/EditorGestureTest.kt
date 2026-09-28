package dev.folha

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import dev.folha.editor.Document
import dev.folha.editor.EditorViewModel
import dev.folha.files.DraftStore
import dev.folha.ui.FolhaEditText
import dev.folha.ui.EditorViewport
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real window input and IME; run on an isolated emulator with a software keyboard enabled. */
@RunWith(AndroidJUnit4::class)
class EditorGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrumentation)
    private val sample = (1..600).joinToString("\n") { "Linha $it: ação, café e palavras para selecionar." }

    private fun findEditor(view: View): FolhaEditText? {
        if (view is FolhaEditText) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) {
            findEditor(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 20_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (predicate()) return
            SystemClock.sleep(80)
        }
        assertTrue(message, predicate())
    }

    private fun <T> main(block: () -> T): T {
        var result: T? = null
        instrumentation.runOnMainSync { result = block() }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun withEditor(text: String = sample, block: (FolhaEditText, EditorViewport, EditorViewModel) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // A clean baseline avoids a recovery snackbar covering the gesture area.
        DraftStore(File(context.filesDir, "drafts")).write(Document(content = text, savedContent = text), 1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var editor: FolhaEditText? = null
            var model: EditorViewModel? = null
            await("Editor must be laid out") {
                scenario.onActivity {
                    editor = findEditor(it.window.decorView)
                    model = ViewModelProvider(it)[EditorViewModel::class.java]
                }
                main { editor?.isLaidOut == true && editor!!.height > 0 }
            }
            block(editor!!, main { editor!!.parent as EditorViewport }, model!!)
        }
    }

    private fun ime(editor: View) = main {
        ViewCompat.getRootWindowInsets(editor)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    private fun bounds(view: View) = main { Rect().also { assertTrue(view.getGlobalVisibleRect(it)) } }

    private fun touch(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        try { assertTrue(instrumentation.uiAutomation.injectInputEvent(event, false)) }
        finally { event.recycle() }
    }

    private fun tap(scroll: View) {
        val rect = bounds(scroll)
        val down = SystemClock.uptimeMillis()
        val x = rect.left + rect.width() * .35f
        val y = rect.top + rect.height() * .3f
        touch(MotionEvent.ACTION_DOWN, down, x, y)
        touch(MotionEvent.ACTION_UP, down, x, y)
    }

    private fun swipe(scroll: View, slow: Boolean = false, upward: Boolean = true) {
        val rect = bounds(scroll)
        val down = SystemClock.uptimeMillis()
        val x = rect.exactCenterX()
        val start = rect.top + rect.height() * (if (upward) .8f else .2f)
        val end = rect.top + rect.height() * (if (upward) .2f else .8f)
        touch(MotionEvent.ACTION_DOWN, down, x, start)
        for (step in 1..8) {
            SystemClock.sleep(if (slow) 100 else 12)
            touch(MotionEvent.ACTION_MOVE, down, x, start + (end - start) * step / 8)
        }
        touch(MotionEvent.ACTION_UP, down, x, end)
    }

    private fun assertClosedFor(editor: View) {
        // Observe several frames, not only the instant before an asynchronous IME request finishes.
        val until = SystemClock.uptimeMillis() + 1_200
        do { assertFalse("Navigation must not show the IME", ime(editor)); SystemClock.sleep(80) }
        while (SystemClock.uptimeMillis() < until)
    }

    @Test fun swipesDoNotOpenImeAndTapsReopenItAfterBack() = withEditor { editor, scroll, model ->
        if (ime(editor)) device.pressBack()
        await("IME initially closed") { !ime(editor) }
        val original = main { model.state.value.document!! }
        val before = main { editor.scrollY }
        swipe(scroll, slow = true)
        await("Slow swipe must scroll") { main { editor.scrollY > before } }
        assertClosedFor(editor)
        main {
            assertSame("Navigation must not emit document edits or selection changes", original, model.state.value.document)
            assertEquals(sample, editor.text.toString())
        }
        tap(scroll)
        await("A genuine tap must show the real software IME") { ime(editor) }
        main { assertTrue(editor.hasFocus()); assertTrue(editor.selectionStart > 0) }
        val openPosition = main { editor.scrollY }
        swipe(scroll, slow = true)
        await("Scrolling with IME open") { main { editor.scrollY > openPosition } }
        assertTrue("Scrolling must keep IME open", ime(editor))
        device.pressBack()
        await("Back must hide IME") { !ime(editor) }
        val closedPosition = main { editor.scrollY }
        swipe(scroll, slow = true)
        await("Scrolling after Back") { main { editor.scrollY > closedPosition } }
        assertClosedFor(editor)
        tap(scroll)
        await("Tap after Back must reopen IME") { ime(editor) }
        main { assertEquals(sample, editor.text.toString()); assertEquals(sample, model.state.value.document!!.content) }
    }

    @Test fun flingContinuesAfterReleaseAndNextTouchStopsIt() = withEditor { editor, scroll, model ->
        if (ime(editor)) device.pressBack()
        await("IME initially closed") { !ime(editor) }
        val releasedAt = AtomicInteger(-1)
        val furthestAfterRelease = AtomicInteger(-1)
        val interrupted = AtomicInteger(-1)
        main {
            scroll.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP) releasedAt.set(editor.scrollY)
                if (event.actionMasked == MotionEvent.ACTION_DOWN && releasedAt.get() >= 0) interrupted.set(editor.scrollY)
                false
            }
            editor.setOnScrollChangeListener { _, _, y, _, _ ->
                if (releasedAt.get() >= 0) furthestAfterRelease.updateAndGet { maxOf(it, y) }
            }
        }
        val original = main { model.state.value.document }
        swipe(scroll)
        await("Fling must advance after ACTION_UP (release=${releasedAt.get()}, later=${furthestAfterRelease.get()})") {
            releasedAt.get() >= 0 && furthestAfterRelease.get() > releasedAt.get() + 10
        }
        val rect = bounds(scroll)
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, rect.exactCenterX(), rect.exactCenterY())
        await("Interrupting DOWN delivered") { interrupted.get() >= 0 }
        val stopped = main { editor.scrollY }
        SystemClock.sleep(400)
        main { assertEquals("Touch must interrupt momentum", stopped, editor.scrollY) }
        touch(MotionEvent.ACTION_CANCEL, down, rect.exactCenterX(), rect.exactCenterY())
        assertClosedFor(editor)
        main {
            assertEquals(sample, editor.text.toString())
            assertSame(original, model.state.value.document)
            scroll.setOnTouchListener(null)
            editor.setOnScrollChangeListener(null)
        }
        swipe(scroll, slow = true, upward = false)
        await("Reverse drag moves toward the beginning") { main { editor.scrollY < stopped } }
        assertClosedFor(editor)
        main { assertTrue(editor.scrollY >= 0); assertSame(original, model.state.value.document) }
    }

    @Test fun shortDocumentSwipeIsNotAnEditingTap() = withEditor("Texto curto com palavras.") { editor, scroll, model ->
        if (ime(editor)) device.pressBack()
        await("IME initially closed") { !ime(editor) }
        val original = main { model.state.value.document }
        swipe(scroll, slow = true)
        assertClosedFor(editor)
        main { assertSame(original, model.state.value.document) }
        tap(scroll)
        await("Tap on short document opens IME") { ime(editor) }
        // Press a real word, using its layout coordinates rather than invoking performLongClick.
        val point = main {
            val location = IntArray(2).also { editor.getLocationOnScreen(it) }
            val line = editor.layout.getLineForOffset(7)
            (location[0] + editor.totalPaddingLeft + editor.layout.getPrimaryHorizontal(7) - editor.scrollX) to
                (location[1] + editor.totalPaddingTop + (editor.layout.getLineTop(line) +
                    editor.layout.getLineBottom(line)) / 2f - editor.scrollY)
        }
        val down = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, down, point.first, point.second)
        await("Native long press must select a word") { main { editor.selectionEnd > editor.selectionStart } }
        touch(MotionEvent.ACTION_UP, down, point.first, point.second)
        instrumentation.waitForIdleSync()
        main {
            assertTrue("Release must preserve selection", editor.selectionEnd > editor.selectionStart)
            val selected = editor.text.substring(editor.selectionStart, editor.selectionEnd)
            assertTrue(editor.onTextContextMenuItem(android.R.id.copy))
            editor.setSelection(editor.length())
            assertTrue(editor.onTextContextMenuItem(android.R.id.paste))
            assertEquals("Texto curto com palavras.$selected", editor.text.toString())
            assertEquals(editor.text.toString(), model.state.value.document!!.content)
        }
    }

    @Test fun nearLimitDocumentScrollDoesNotEditBufferAndCaretCanBeRevealed() {
        if (InstrumentationRegistry.getArguments().getString("layoutProbe") == "true") {
            EditorLayoutProbe.run()
            return
        }
        val text = "Uma linha com acentos: ação.\n".repeat(67_500) // 2,092,500 bytes, just below 2 MiB.
        assertTrue(text.toByteArray(Charsets.UTF_8).size < 2 * 1024 * 1024)
        withEditor(text) { editor, scroll, model ->
            val original = main { model.state.value.document }
            swipe(scroll, slow = true)
            await("Large document scrolls") { main { editor.scrollY > 0 } }
            main {
                assertEquals("The editor must stay bounded regardless of document length", scroll.height, editor.height)
                assertSame(original, model.state.value.document)
                assertEquals(text, editor.text.toString())
                val position = text.lastIndexOf("acentos")
                editor.requestFocus()
                editor.setSelection(position, position + 7)
                editor.bringPointIntoView(position)
            }
            await("Search/caret near EOF must be visible in the viewport") {
                main {
                    val line = editor.layout.getLineForOffset(editor.selectionStart)
                    val y = editor.top + editor.totalPaddingTop + editor.layout.getLineTop(line)
                    y >= editor.scrollY && y < editor.scrollY + editor.height
                }
            }
            main { assertEquals(text, model.state.value.document!!.content) }
        }
    }
}
