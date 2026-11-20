package dev.app8.engine.navigation

import org.junit.Test
import kotlin.test.assertNull

/** The predictive back flag never outlives a push: a new screen is not mistaken for a gesture's target. */
class PredictiveFlagTest {
    @Test
    fun aPushClearsTheGestureFlag() {
        val stack = StackState(Route("home"))
        stack.push(Route("detail"))
        stack.predictiveRoute = stack.entries.last().id
        stack.push(Route("more"))
        assertNull(stack.predictiveRoute)
    }
}
