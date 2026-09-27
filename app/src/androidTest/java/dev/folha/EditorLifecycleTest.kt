package dev.folha

import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditorLifecycleTest {
    private fun findEditor(view: android.view.View): EditText? {
        if (view is EditText) return view
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) findEditor(view.getChildAt(i))?.let { return it }
        return null
    }
    @Test fun nativeEditorUnicodeSelectionAndRecreation() {
        // Instrumented tests are intended for an isolated emulator, never user data.
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        dev.folha.files.DraftStore(java.io.File(context.filesDir, "drafts")).write(null, 1)
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val newText = device.wait(Until.findObject(By.text("Novo texto")), 60_000)
            assertNotNull("Initial screen must be available", newText)
            newText.click()
            assertNotNull(device.wait(Until.findObject(By.desc("Editor de texto")), 30_000))
            val rapidText = "Minha primeira folha. Texto para salvar e localizar. 0123456789"
            scenario.onActivity { activity -> findEditor(activity.window.decorView)!!.requestFocus() }
            InstrumentationRegistry.getInstrumentation().sendStringSync(rapidText)
            scenario.onActivity { activity ->
                assertEquals("Recomposition must never replace live keystrokes with stale text", rapidText,
                    findEditor(activity.window.decorView)!!.text.toString())
            }
            val text = "ação 🌱\r\n# título\n".repeat(5000)
            scenario.onActivity { activity ->
                val editor = findEditor(activity.window.decorView)!!
                editor.setText(text)
                editor.setSelection(10, 15)
            }
            scenario.recreate()
            assertNotNull(device.wait(Until.findObject(By.desc("Editor de texto")), 30_000))
            scenario.onActivity { activity ->
                val editor = findEditor(activity.window.decorView)!!
                assertEquals(text, editor.text.toString())
                assertEquals(10, editor.selectionStart)
                assertEquals(15, editor.selectionEnd)
            }
        }
    }
}
