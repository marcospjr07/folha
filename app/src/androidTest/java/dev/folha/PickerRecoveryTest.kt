package dev.folha

import android.app.Application
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.folha.editor.Document
import dev.folha.editor.EditorViewModel
import dev.folha.files.DraftStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class PickerRecoveryTest {
    @Test fun pickerResultBeforeRecoveryCompletesSavesTheMatchingBufferAndSendImportsCopy() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val restored = Document(content = "recuperado: ação 🌱\n")
        DraftStore(File(app.filesDir, "drafts")).write(restored, 1)
        val file = File(app.cacheDir, "shared/tests/retorno.md").apply { parentFile!!.mkdirs(); writeText("") }
        val uri = FileProvider.getUriForFile(app, "dev.folha.share", file)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val store = ViewModelStore()
        lateinit var model: EditorViewModel
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[EditorViewModel::class.java]
                // Reproduce delivery of a pending SAF result while the recovery is still loading.
                model.savePicked(restored.id, uri, 0) {}
            }
            await { model.state.value.document?.uri == uri.toString() && !model.state.value.busy }
            assertEquals(restored.content, file.readText())
            assertFalse(model.state.value.document!!.isModified)
            instrumentation.runOnMainSync { model.open(uri, importCopy = true) }
            await { model.state.value.document?.isNew == true && !model.state.value.busy }
            assertEquals(restored.content, model.state.value.document!!.content)
            assertTrue(model.state.value.document!!.isModified)
            assertNull(model.state.value.document!!.uri)
        } finally {
            instrumentation.runOnMainSync { model.flushRecovery(); store.clear() }
        }
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 30_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(100)
        assertTrue("Operation did not reach the expected state", condition())
    }
}
