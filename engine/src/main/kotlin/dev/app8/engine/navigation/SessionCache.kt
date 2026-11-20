package dev.app8.engine.navigation

import dev.app8.engine.render.ScreenSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async

/**
 * The loaded [ScreenSession] of each route on the app's stacks, kept while the route is on a
 * stack (iOS keeps a pushed or unselected tab's view controller alive).
 *
 * - A push, a presentation or a tab switch [prepare]s the incoming route's session first, so
 *   the screen draws its immediate content on the first frame of its transition instead of
 *   fading in from a blank background.
 * - A tab revisit or a pop back to a covered screen reuses the session: nothing is rebuilt and
 *   deferred sections are not built again.
 * - A session is disposed once its route has left its stack ([retire]) and its screen has
 *   left the composition ([release]).
 *
 * Main thread only, like the rest of navigation state.
 */
internal class SessionCache {
    private class Entry(var load: Deferred<Result<ScreenSession>>)

    private val entries = HashMap<Long, Entry>()

    /** Routes whose screen is in the composition, with a count (a screen can be composed twice mid-transition). */
    private val composed = HashMap<Long, Int>()

    /** Routes that left their stack while their screen was still composed. */
    private val retired = HashSet<Long>()

    /** The session of [routeId] if it has finished loading (successfully or not). */
    fun peek(routeId: Long): Result<ScreenSession>? {
        val load = entries[routeId]?.load ?: return null
        if (!load.isCompleted || load.isCancelled) return null
        @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
        return load.getCompleted()
    }

    /**
     * Starts (or joins) loading [route]'s session in [scope]. A load cancelled with the scope
     * that started it (a screen that left the composition mid-load) starts again.
     */
    fun prepare(route: Route, scope: CoroutineScope, create: suspend (Route) -> ScreenSession): Deferred<Result<ScreenSession>> {
        val existing = entries[route.id]
        if (existing != null && !existing.load.isCancelled) return existing.load
        val load = scope.async(start = CoroutineStart.UNDISPATCHED) { runCatching { create(route) } }
        if (existing != null) existing.load = load else entries[route.id] = Entry(load)
        return load
    }

    /** A streamed update replaced [routeId]'s session: the cache hands out [session] from now on. */
    fun replace(routeId: Long, session: ScreenSession, scope: CoroutineScope) {
        val entry = entries[routeId] ?: return
        entry.load = scope.async(start = CoroutineStart.UNDISPATCHED) { Result.success(session) }
    }

    /** [routeId]'s screen entered the composition. */
    fun acquire(routeId: Long) {
        composed[routeId] = (composed[routeId] ?: 0) + 1
    }

    /**
     * [routeId]'s screen left the composition. [stillOnStack] false (popped, dismissed) disposes
     * the session; true (a covered screen, an unselected tab) keeps it for the return.
     */
    fun release(routeId: Long, stillOnStack: Boolean) {
        val count = (composed[routeId] ?: 0) - 1
        if (count > 0) composed[routeId] = count else composed.remove(routeId)
        if (!stillOnStack) retired += routeId
        if (count <= 0 && routeId in retired) {
            retired -= routeId
            drop(routeId)
        }
    }

    /** [routes] left their stack: dispose each session now, or when its screen leaves the composition. */
    fun retire(routes: Iterable<Route>) {
        for (route in routes) {
            if ((composed[route.id] ?: 0) > 0) retired += route.id else drop(route.id)
        }
    }

    /** Number of sessions held (tests). */
    val size: Int get() = entries.size

    private fun drop(routeId: Long) {
        val entry = entries.remove(routeId) ?: return
        val load = entry.load
        if (load.isCompleted) {
            if (!load.isCancelled) peekResult(load)?.dispose()
        } else {
            load.invokeOnCompletion { if (it == null) peekResult(load)?.dispose() }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun peekResult(load: Deferred<Result<ScreenSession>>): ScreenSession? = load.getCompleted().getOrNull()
}
