package dev.app8.engine.events

import dev.app8.engine.core.EngineVersion
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.CopyOnWriteArrayList

/** Handle that stops a subscription. */
public fun interface App8Subscription {
    public fun cancel()
}

/**
 * Host-facing `emit` events (iOS `App8EventBus`). Subscribers are called synchronously on the
 * dispatching (main) thread; [events] is a hot flow of the same events.
 */
public class App8EventBus internal constructor() {
    /** Set by a cloud SDK once; stamped on every event. */
    public var cloudVersion: String? = null

    private val listeners = CopyOnWriteArrayList<(App8Event) -> Unit>()
    private val flow = MutableSharedFlow<App8Event>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    public val events: SharedFlow<App8Event> = flow.asSharedFlow()

    /** All events, or only those of [screenId] / [name] when given. */
    public fun subscribe(screenId: String? = null, name: String? = null, handler: (App8Event) -> Unit): App8Subscription {
        val l: (App8Event) -> Unit = { e -> if ((screenId == null || e.screenId == screenId) && (name == null || e.name == name)) handler(e) }
        listeners += l
        return App8Subscription { listeners.remove(l) }
    }

    public fun dispatch(event: App8Event) {
        val stamped = event.copy(engineVersion = EngineVersion.CURRENT, cloudVersion = cloudVersion)
        listeners.forEach { it(stamped) }
        flow.tryEmit(stamped)
    }
}

/**
 * Analytics events (iOS `App8AnalyticsBus`). Per dispatch: stamp versions, merge canonical
 * context into `properties` (SDK wins), run [redact] (null drops), fan out.
 */
public class App8AnalyticsBus internal constructor() {
    public var cloudVersion: String? = null

    /** Return null to drop an event, or a substitute. */
    public var redact: ((App8AnalyticsEvent) -> App8AnalyticsEvent?)? = null

    private val listeners = CopyOnWriteArrayList<(App8AnalyticsEvent) -> Unit>()
    private val flow = MutableSharedFlow<App8AnalyticsEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    public val events: SharedFlow<App8AnalyticsEvent> = flow.asSharedFlow()

    public fun observe(handler: (App8AnalyticsEvent) -> Unit): App8Subscription {
        listeners += handler
        return App8Subscription { listeners.remove(handler) }
    }

    public fun dispatch(event: App8AnalyticsEvent) {
        val stamped = event.copy(engineVersion = EngineVersion.CURRENT, cloudVersion = cloudVersion)
        val merged = stamped.copy(properties = mergeCanonical(stamped))
        val out = redact?.invoke(merged) ?: if (redact != null) return else merged
        listeners.forEach { it(out) }
        flow.tryEmit(out)
    }

    internal companion object {
        fun mergeCanonical(e: App8AnalyticsEvent): Map<String, Any?> {
            val out = LinkedHashMap(e.properties)
            e.screenId?.let { out["screen_id"] = it }
            e.componentId?.let { out["component_id"] = it }
            e.componentType?.let { out["component_type"] = it }
            e.locale?.let { out["locale"] = it }
            out["engine_version"] = e.engineVersion
            e.cloudVersion?.let { out["cloud_version"] = it }
            return out
        }
    }
}
