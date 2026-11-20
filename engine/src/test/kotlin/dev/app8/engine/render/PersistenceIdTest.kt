package dev.app8.engine.render

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where persisted state lives: the host's id first, then `app.id`, `bundleId`, title (iOS parity). */
class PersistenceIdTest {
    private fun source(app: String, hostId: String?) =
        object : MemoryDataSource(app, emptyMap()) {
            override val persistenceId: String? = hostId
        }

    private fun resolved(app: String, hostId: String?): String = runBlocking {
        val rt = runtime(source(app, hostId))
        rt.ensureReady()
        rt.persistenceId
    }

    private val nav = """"navigation":{"startFlow":"f","flows":[{"id":"f","startScreen":"s"}]}"""

    @Test fun hostIdWinsOverTheAppsOwnFields() {
        assertEquals("host-42", resolved("""{"id":"app-1","bundleId":"b","title":"T",$nav}""", "host-42"))
    }

    @Test fun withoutHostIdTheAppsFieldsDecide() {
        assertEquals("app-1", resolved("""{"id":"app-1","bundleId":"b","title":"T",$nav}""", null))
        assertEquals("b", resolved("""{"bundleId":"b","title":"T",$nav}""", null))
        assertEquals("T", resolved("""{"title":"T",$nav}""", null))
    }

    @Test fun blankValuesAreSkipped() {
        assertEquals("app-1", resolved("""{"id":"app-1","title":"T",$nav}""", "  "))
        assertEquals("T", resolved("""{"id":" ","title":"T",$nav}""", ""))
    }
}
