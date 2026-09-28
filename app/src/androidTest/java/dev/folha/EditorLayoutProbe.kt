package dev.folha

import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Debug
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams
import android.widget.FrameLayout
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import dev.folha.files.DraftStore
import dev.folha.ui.EditorViewport
import dev.folha.ui.FolhaEditText
import org.junit.Assert.assertTrue
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Opt-in A/B diagnostic, not a timing assertion. See docs/qa/VALIDATION.md for invocation. */
internal object EditorLayoutProbe {
    fun run() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        DraftStore(File(context.filesDir, "drafts")).write(null, 1)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val text = "Uma linha com acentos: ação.\n".repeat(67_500)
        // Both orders reduce the risk of attributing first-run JIT cost to one layout.
        for ((iteration, wrapped) in listOf(false, true, true, false).withIndex()) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                val finished = CountDownLatch(1)
                var height = 0
                var viewport = 0
                var firstFrameMs = 0L
                val drawMs = mutableListOf<Long>()
                scenario.onActivity { activity ->
                    val started = SystemClock.elapsedRealtime()
                    val editor = FolhaEditText(activity).apply {
                        isSaveEnabled = false
                        background = null
                        gravity = Gravity.TOP or Gravity.START
                        typeface = Typeface.MONOSPACE
                        textSize = 17f
                        setLineSpacing(0f, 1.3f)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        setHorizontallyScrolling(false)
                        val padding = (22 * resources.displayMetrics.density).toInt()
                        setPadding(padding, padding / 2, padding, padding)
                        setText(text)
                    }
                    val host = if (wrapped) NestedScrollView(activity).apply {
                        isFillViewport = true
                        addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
                    } else EditorViewport(activity, editor)
                    val target: View = if (wrapped) host else editor
                    val root = object : FrameLayout(activity) {
                        override fun dispatchDraw(canvas: Canvas) {
                            val before = SystemClock.elapsedRealtime()
                            super.dispatchDraw(canvas)
                            if (drawMs.size >= 12) return
                            drawMs.add(SystemClock.elapsedRealtime() - before)
                            if (drawMs.size == 1) {
                                firstFrameMs = SystemClock.elapsedRealtime() - started
                                height = editor.height
                                viewport = host.height
                            }
                            if (drawMs.size == 12) finished.countDown()
                            else postOnAnimation { target.scrollTo(0, drawMs.size * 5_000); invalidate() }
                        }
                    }
                    root.addView(host, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                    activity.setContentView(root)
                }
                assertTrue("Layout probe must render (180s software-emulator allowance)", finished.await(180, TimeUnit.SECONDS))
                instrumentation.waitForIdleSync()
                val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
                Log.i("FolhaLayoutProbe", "run=$iteration mode=${if (wrapped) "wrap" else "bounded"} " +
                    "bytes=${text.toByteArray().size} editorHeight=$height viewportHeight=$viewport " +
                    "firstFrameMs=$firstFrameMs drawMs=$drawMs totalPssKb=${memory.totalPss} " +
                    "javaPssKb=${memory.dalvikPss} nativePssKb=${memory.nativePss}")
            }
        }
    }
}
