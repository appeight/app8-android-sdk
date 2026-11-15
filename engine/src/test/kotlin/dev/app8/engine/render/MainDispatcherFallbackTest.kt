package dev.app8.engine.render

import android.content.ContextWrapper
import dev.app8.engine.App8Config
import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.runtime.App8Runtime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * A host (a cloud SDK, an app) running the engine in plain JVM tests has no main looper and
 * may not call `Dispatchers.setMain`: an instance built with the default dispatchers still
 * builds, and its action lists still run, on the calling thread.
 */
class MainDispatcherFallbackTest {
    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"count":{"type":"number","initialValue":0}},
      "children":[{"type":"button","id":"b","content":{"properties":{"text":"+"},
        "actions":{"tap":[{"type":"incrementVariable","variableName":"count","by":3}]}}}]}}"""

    @Test
    fun `without a main dispatcher, action lists run inline`() {
        assertSame(Dispatchers.Unconfined, dev.app8.engine.runtime.mainDispatcherOrInline())

        val rt = App8Runtime(ContextWrapper(null), MemoryDataSource(APP_JSON, mapOf("s" to screen)), App8Config())
        val session = session(rt)
        ActionDispatcher(rt, RecordingNavigator(), RecordingHost()).dispatchTrigger(session.root.find("b")!!, ActionTrigger.tap)

        assertEquals(3L, session.scope.store.getValue("count"))
    }

    @Test
    fun `app datasources load without a main dispatcher`() = runBlocking {
        val app = """{"title":"T","navigation":{"startFlow":"f","flows":[{"id":"f","startScreen":"s"}]},
          "variables":{"items":{"type":"array","source":"items","initialValue":[]}}}"""
        val rt = App8Runtime(
            ContextWrapper(null),
            MemoryDataSource(app, mapOf("s" to screen), datasources = mapOf("items" to "[1,2]")),
            App8Config(),
            ioDispatcher = Dispatchers.Unconfined,
        )
        rt.ensureReady()
        assertEquals(listOf(1L, 2L), rt.appStore.getValue("items"))
    }
}
