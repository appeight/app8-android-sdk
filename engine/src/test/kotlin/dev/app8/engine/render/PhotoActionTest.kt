package dev.app8.engine.render

import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.actions.AlertRequest
import dev.app8.engine.actions.HostEffects
import dev.app8.engine.actions.PhotoCaptureRequest
import dev.app8.engine.actions.PhotoPick
import dev.app8.engine.core.model.Action
import dev.app8.engine.core.model.ActionTrigger
import org.junit.Test
import kotlin.test.assertEquals

/** `capturePhoto` callbacks and request (app8-dsl docs/photos.md); storage runs in PhotoStoreTest and on the emulator. */
class PhotoActionTest {
    private class PhotoHost(var answer: PhotoPick) : HostEffects {
        val requests = mutableListOf<PhotoCaptureRequest>()
        override fun openUrl(url: String, presentation: Action.URLPresentation) = Unit
        override fun haptic(style: Action.HapticStyle) = Unit
        override fun alert(request: AlertRequest) = Unit
        override fun requestNotificationPermission(onResult: (Boolean) -> Unit) = onResult(false)
        override fun capturePhoto(request: PhotoCaptureRequest, onResult: (PhotoPick) -> Unit) { requests += request; onResult(answer) }
    }

    private val app = """{"title":"T","navigation":{"startFlow":"f","flows":[{"id":"f","startScreen":"s"}]}}"""
    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{"outcome":{"type":"string","initialValue":""},"code":{"type":"string","initialValue":""},"size":{"type":"number","initialValue":512}},
      "children":[
        {"type":"view","id":"cam","content":{"actions":{"tap":{"type":"capturePhoto","source":"camera","camera":"front",
          "labels":{"camera":"Shoot {{size}}"},"maxDimension":"{{size}}",
          "onSuccess":{"type":"updateVariable","variableName":"outcome","value":"ok"},
          "onCancel":{"type":"updateVariable","variableName":"outcome","value":"cancel"},
          "onError":[{"type":"updateVariable","variableName":"outcome","value":"error"},{"type":"updateVariable","variableName":"code","value":"{{error.code}}"}]}}}},
        {"type":"view","id":"any","content":{"actions":{"tap":{"type":"capturePhoto"}}}},
        {"type":"view","id":"del","content":{"actions":{"tap":{"type":"deletePhoto","ref":"https://example.com/a.jpg"}}}}
      ]}}"""

    private val rt = runtime(MemoryDataSource(app, mapOf("s" to screen)))
    private val session = session(rt)
    private val host = PhotoHost(PhotoPick.Cancel)
    private val dispatcher = ActionDispatcher(rt, RecordingNavigator(), host)
    private fun tap(id: String) = dispatcher.dispatchTrigger(session.root.find(id)!!, ActionTrigger.tap)
    private fun value(name: String) = session.scope.store.getValue(name)

    @Test
    fun `request carries source, camera and resolved labels and cancel runs onCancel`() {
        tap("cam")
        val r = host.requests.single()
        assertEquals(PhotoCaptureRequest.Source.camera, r.source)
        assertEquals(true, r.front)
        assertEquals("Shoot 512", r.cameraLabel)
        assertEquals("Choose from Library", r.libraryLabel)
        assertEquals("cancel", value("outcome"))
    }

    @Test
    fun `errors run onError with error in scope`() {
        host.answer = PhotoPick.Error("permissionDenied", "no")
        tap("cam")
        assertEquals("error", value("outcome"))
        assertEquals("permissionDenied", value("code"))
    }

    @Test
    fun `defaults to cameraOrLibrary and back and deletePhoto ignores non-references`() {
        tap("any")
        assertEquals(PhotoCaptureRequest.Source.cameraOrLibrary, host.requests.single().source)
        assertEquals(false, host.requests.single().front)
        tap("del")
    }
}
