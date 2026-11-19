package dev.app8.engine.navigation

import dev.app8.engine.render.ScreenSession
import dev.app8.engine.render.runtime
import dev.app8.engine.render.session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Batch B motion: screens load before their transition and survive a tab switch or a cover. */
class SessionCacheTest {
    private val screen = """{"id":"s","type":"screen","content":{"subviews":[{"id":"l","type":"label","content":{"properties":{"text":"Hi"}}}]}}"""
    private val rt = runtime(mapOf("s" to screen))
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    private fun cache(): Pair<SessionCache, MutableList<ScreenSession>> {
        val created = mutableListOf<ScreenSession>()
        return SessionCache() to created
    }

    @Test
    fun aPreparedSessionIsReadyOnTheFirstFrameAndReused() = runBlocking {
        val (cache, created) = cache()
        val route = Route("s")
        assertNull(cache.peek(route.id))
        cache.prepare(route, scope) { session(rt).also { created += it } }.await()
        assertNotNull(cache.peek(route.id)?.getOrNull(), "a screen composed now has its session at once")
        // Joining an existing load does not build the screen again.
        cache.prepare(route, scope) { session(rt).also { created += it } }.await()
        assertEquals(1, created.size)
    }

    @Test
    fun anUnselectedTabOrCoveredScreenKeepsItsSession() = runBlocking {
        val (cache, _) = cache()
        val route = Route("s")
        val s = cache.prepare(route, scope) { session(rt) }.await().getOrThrow()
        cache.acquire(route.id)
        cache.release(route.id, stillOnStack = true)
        assertTrue(s.isAlive)
        assertSame(s, cache.peek(route.id)?.getOrNull(), "the revisit draws the same session: nothing rebuilt")
    }

    @Test
    fun aPoppedScreenIsDisposedOnlyOnceItLeavesTheComposition() = runBlocking {
        val (cache, _) = cache()
        val route = Route("s")
        val s = cache.prepare(route, scope) { session(rt) }.await().getOrThrow()
        cache.acquire(route.id)
        cache.retire(listOf(route))
        assertTrue(s.isAlive, "still drawn by the pop animation")
        cache.release(route.id, stillOnStack = false)
        assertFalse(s.isAlive)
        assertEquals(0, cache.size)
    }

    @Test
    fun aRouteRemovedWhileNotComposedIsDisposedAtOnce() = runBlocking {
        val (cache, _) = cache()
        val route = Route("s")
        val s = cache.prepare(route, scope) { session(rt) }.await().getOrThrow()
        cache.retire(listOf(route))
        assertFalse(s.isAlive)
    }

    @Test
    fun pushingWaitsForTheIncomingScreen() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val flow = FlowController(null, "home", scope = scope, createSession = { gate.await(); session(rt) })
        val stack = flow.layers.first().stack
        flow.push(stack, "s", emptyMap(), transition = null)
        assertEquals(1, stack.entries.size, "the push starts once the screen has loaded")
        gate.complete(Unit)
        assertEquals(2, stack.entries.size)
        assertNotNull(flow.sessions.peek(stack.entries.last().id)?.getOrNull())
        Unit
    }

    @Test
    fun aSlowScreenIsPushedAfterTheTimeout() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val flow = FlowController(null, "home", scope = CoroutineScope(Dispatchers.Default), createSession = { never.await(); session(rt) })
        val stack = flow.layers.first().stack
        flow.push(stack, "s", emptyMap(), transition = null)
        kotlinx.coroutines.delay(PREPARE_TIMEOUT_MS + 200)
        assertEquals(2, stack.entries.size)
        never.cancel()
        Unit
    }
}
