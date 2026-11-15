package dev.app8.engine.render

import dev.app8.engine.App8HttpRequest
import dev.app8.engine.actions.ActionDispatcher
import dev.app8.engine.actions.Navigator
import dev.app8.engine.core.model.ActionTrigger
import dev.app8.engine.runtime.HttpResponse
import dev.app8.engine.runtime.HttpTransport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DSL 0.6.0 actions: forEach, with, request, reloadDatasource, dismiss then navigate. */
class Dsl060ActionsTest {
    private val app = """{"title":"T","navigation":{"startFlow":"f","flows":[{"id":"f","startScreen":"s"}]},
      "variables":{"lastPing":{"type":"object"},
        "catalog":{"type":"array","initialValue":[],"source":"datasources/items"}}}"""

    private val screen = """{"type":"screen","id":"s","content":{
      "variables":{
        "lines":{"type":"array","initialValue":[{"key":"a","qty":2},{"key":"b","qty":1}]},
        "cart":{"type":"array","initialValue":[]},
        "log":{"type":"array","initialValue":[]},
        "item":{"type":"string","initialValue":"outer"},
        "items":{"type":"array","source":"datasources/items"},
        "result":{"type":"object"},
        "message":{"type":"string","initialValue":""},
        "reloaded":{"type":"boolean","initialValue":false}
      },
      "children":[
        {"type":"view","id":"loop","content":{"actions":{"tap":[
          {"type":"forEach","items":"{{lines}}","as":"line","index":"i","actions":[
            {"type":"appendToArray","variableName":"cart","value":{"key":"{{line.key}}","at":"{{i}}"}},
            {"type":"appendToArray","variableName":"lines","value":{"key":"never visited"}},
            {"type":"forEach","items":"{{[1, 2]}}","actions":{"type":"appendToArray","variableName":"log","value":"{{line.key}}{{item}}"}}
          ]},
          {"type":"appendToArray","variableName":"log","value":"{{item}}"},
          {"type":"forEach","items":"{{missing}}","actions":{"type":"appendToArray","variableName":"log","value":"no"}}
        ]}}},
        {"type":"view","id":"with","content":{"actions":{"tap":
          {"type":"with","values":{"product":{"key":"{{lines[0].key}}","n":"{{lines[0].qty}}"},"qty":1},"actions":[
            {"type":"updateVariable","variableName":"lines","value":[]},
            {"type":"upsertArrayItem","variableName":"cart","matchBy":"key","value":{"key":"{{product.key}}","n":"{{product.n}}","qty":"{{qty}}"}}
          ]}}}},
        {"type":"view","id":"post","content":{"actions":{"tap":[
          {"type":"request","method":"post","url":"https://api.example.com/v1/items/{{lines[0].key}}","headers":{"Accept":"application/json"},
            "body":{"key":"{{lines[0].key}}","qty":"{{lines[0].qty}}"},"resultVariable":"result",
            "onSuccess":[{"type":"updateVariable","variableName":"message","value":"ok {{response.status}} {{response.body.id}} {{response.headers.etag}}"}],
            "onError":[{"type":"updateVariable","variableName":"message","value":"error {{response.status}}"}]},
          {"type":"appendToArray","variableName":"log","value":"after"}
        ]}}},
        {"type":"view","id":"get","content":{"actions":{"tap":
          {"type":"request","url":"{{message}}","body":{"ignored":true},"resultVariable":"result",
            "onError":{"type":"updateVariable","variableName":"message","value":"error {{response.status}}"}}}}},
        {"type":"view","id":"appResult","content":{"actions":{"tap":
          {"type":"request","url":"https://api.example.com/v1/ping","resultVariable":"lastPing",
            "onSuccess":{"type":"updateVariable","variableName":"message","value":"should not run"}}}}},
        {"type":"view","id":"reload","content":{"actions":{"tap":
          {"type":"reloadDatasource","source":"datasources/items",
            "onSuccess":{"type":"updateVariable","variableName":"reloaded","value":true},
            "onError":{"type":"updateVariable","variableName":"message","value":"reload failed"}}}}},
        {"type":"view","id":"leave","content":{"actions":{"tap":[
          {"type":"dismiss"},
          {"type":"appendToArray","variableName":"log","value":"between"},
          {"type":"navigation","nextScreen":"next"}
        ]}}}
      ]}}"""

    private val data = mutableMapOf("datasources/items" to """[{"id":1}]""")
    private val requests = mutableListOf<App8HttpRequest>()
    private val fetched = mutableListOf<String>()
    private var reject = false

    private val source = object : MemoryDataSource(app, mapOf("s" to screen)) {
        override suspend fun getDatasource(screenId: String, datasourceId: String): ByteArray {
            fetched += "$screenId:$datasourceId"
            return (data[datasourceId] ?: error("offline")).encodeToByteArray()
        }

        override suspend fun prepareRequest(request: App8HttpRequest): App8HttpRequest {
            if (reject) error("not allowed")
            return request.copy(headers = request.headers + ("Authorization" to "Bearer t"))
        }
    }

    private val rt = runtime(source).apply {
        http = HttpTransport { r ->
            requests += r
            if (r.url.endsWith("/missing")) HttpResponse(404, emptyMap(), "") else HttpResponse(201, mapOf("etag" to "v1"), """{"id":7}""")
        }
    }
    private val session = session(rt)
    private val nav = RecordingNavigator()
    private val dispatcher = ActionDispatcher(rt, nav, RecordingHost())

    private fun tap(id: String) = dispatcher.dispatchTrigger(session.root.find(id)!!, ActionTrigger.tap)
    private fun value(name: String) = session.scope.store.getValue(name)

    @Test
    fun `forEach walks a snapshot of items with element and index in scope`() {
        tap("loop")
        assertEquals(listOf(mapOf("key" to "a", "at" to 0L), mapOf("key" to "b", "at" to 1L)), value("cart"))
        assertEquals(4, (value("lines") as List<*>).size, "appends during the loop add no iterations")
        assertEquals(listOf("a1", "a2", "b1", "b2", "outer"), value("log"), "nested loops shadow `item` only inside")
    }

    @Test
    fun `with evaluates its values once, before the nested actions`() {
        tap("with")
        assertEquals(emptyList<Any?>(), value("lines"))
        assertEquals(listOf(mapOf("key" to "a", "n" to 2L, "qty" to 1L)), value("cart"))
    }

    @Test
    fun `request goes through prepareRequest and runs onSuccess with response in scope`() {
        tap("post")
        val sent = requests.single()
        assertEquals("POST", sent.method)
        assertEquals("https://api.example.com/v1/items/a", sent.url)
        assertEquals("""{"key":"a","qty":2}""", sent.body)
        assertEquals(mapOf("Accept" to "application/json", "Content-Type" to "application/json", "Authorization" to "Bearer t"), sent.headers)
        assertEquals("ok 201 7 v1", value("message"))
        assertEquals(mapOf("status" to 201L, "body" to mapOf("id" to 7L), "headers" to mapOf("etag" to "v1")), value("result"))
        assertEquals(listOf("after"), value("log"))
    }

    @Test
    fun `errors, rejections and bad urls run onError with status 0 or the HTTP status`() {
        session.scope.store.setValue("message", "https://api.example.com/v1/missing")
        tap("get")
        assertNull(requests.single().body, "a GET sends no body")
        assertEquals("error 404", value("message"))

        session.scope.store.setValue("message", "/relative")
        tap("get")
        assertEquals("error 0", value("message"))
        assertEquals(1, requests.size, "a relative url is never sent")

        reject = true
        session.scope.store.setValue("message", "https://api.example.com/v1/x")
        tap("get")
        assertEquals("error 0", value("message"))
        assertEquals(mapOf("status" to 0L, "body" to null, "headers" to emptyMap<String, Any?>()), value("result"))
    }

    @Test
    fun `after the screen is gone only an app resultVariable is written`() {
        val gate = CompletableDeferred<Unit>()
        rt.http = HttpTransport { r ->
            gate.await()
            HttpResponse(200, emptyMap(), "true")
        }
        tap("appResult")
        session.dispose()
        gate.complete(Unit)
        @Suppress("UNCHECKED_CAST")
        assertEquals(200L, (rt.appStore.getValue("lastPing") as Map<String, Any?>)["status"])
        assertEquals("", value("message"), "callbacks of a gone screen are dropped")
    }

    @Test
    fun `reloadDatasource refetches once and updates app and screen variables`() {
        assertEquals(listOf(mapOf("id" to 1L)), rt.appStore.getValue("catalog"), "app variables load their source at start")
        assertTrue(":datasources/items" in fetched, "app scope asks with an empty screen id")
        data["datasources/items"] = """[{"id":1},{"id":2}]"""
        fetched.clear()
        tap("reload")
        assertEquals(listOf("s:datasources/items"), fetched)
        assertEquals(2, (value("items") as List<*>).size)
        assertEquals(2, (rt.appStore.getValue("catalog") as List<*>).size)
        assertEquals(true, value("reloaded"))

        data.remove("datasources/items")
        tap("reload")
        assertEquals("reload failed", value("message"))
        assertEquals(2, (value("items") as List<*>).size, "a failed reload keeps the values")
        assertTrue(rt.diagnostics.snapshot().any { it.startsWith("datasource.reload.failed") })
    }

    @Test
    fun `after dismiss the next navigation waits and goes to the presenting navigator`() {
        val presenter = RecordingNavigator()
        val dismissed = CompletableDeferred<Unit>()
        val modal = object : Navigator by nav {
            override suspend fun dismissAndAwait(): Navigator {
                nav.dismiss()
                dismissed.await()
                return presenter
            }
        }
        val d = ActionDispatcher(rt, modal, RecordingHost())
        d.dispatchTrigger(session.root.find("leave")!!, ActionTrigger.tap)
        assertEquals(listOf("dismiss"), nav.calls)
        assertEquals(listOf("between"), value("log"), "actions between dismiss and navigation run right away")
        assertEquals(emptyList(), presenter.calls, "the navigation waits for the dismissal")
        dismissed.complete(Unit)
        assertEquals(listOf("push:next"), presenter.calls)
        assertEquals(listOf("dismiss"), nav.calls)
    }

    @Test
    fun `the http transport sends every method with its body`() = runBlocking {
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        val seen = mutableListOf<String>()
        server.createContext("/") { ex ->
            seen += "${ex.requestMethod} ${ex.requestHeaders.getFirst("Content-Type")} ${ex.requestBody.readBytes().decodeToString()}"
            val out = """{"ok":true}""".encodeToByteArray()
            ex.responseHeaders.add("X-Trace", "1")
            ex.sendResponseHeaders(if (ex.requestMethod == "DELETE") 500 else 200, out.size.toLong())
            ex.responseBody.use { it.write(out) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/v1/items"
            val transport = dev.app8.engine.runtime.OkHttpTransport()
            val patch = transport.send(App8HttpRequest("PATCH", url, mapOf("Content-Type" to "application/json"), """{"a":1}"""))
            assertEquals(200, patch.status)
            assertEquals("1", patch.headers["x-trace"])
            assertEquals(mapOf("ok" to true), patch.toValue()["body"])
            assertEquals(500, transport.send(App8HttpRequest("DELETE", url)).status)
            assertEquals(listOf("PATCH application/json {\"a\":1}", "DELETE null "), seen)
        } finally {
            server.stop(0)
        }
    }
}
