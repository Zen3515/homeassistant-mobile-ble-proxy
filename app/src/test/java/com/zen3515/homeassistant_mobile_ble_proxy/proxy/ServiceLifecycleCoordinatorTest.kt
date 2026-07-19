package com.zen3515.homeassistant_mobile_ble_proxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceLifecycleCoordinatorTest {
    @Test
    fun `start while starting or running does not create another generation`() {
        val coordinator = ServiceLifecycleCoordinator()
        val first = coordinator.requestStart(startId = 1)

        assertNotNull(first)
        assertNull(coordinator.requestStart(startId = 2))
        assertTrue(coordinator.markRunning(checkNotNull(first)))
        assertNull(coordinator.requestStart(startId = 3))
        assertEquals(ServiceLifecycleCoordinator.State.RUNNING, coordinator.state)
    }

    @Test
    fun `stop invalidates a suspended startup and a newer start wins`() {
        val coordinator = ServiceLifecycleCoordinator()
        val stale = checkNotNull(coordinator.requestStart(startId = 10))

        val stopGeneration = coordinator.requestStop()
        coordinator.markStopped(stopGeneration)
        val current = checkNotNull(coordinator.requestStart(startId = 12))

        assertFalse(coordinator.isCurrent(stale))
        assertFalse(coordinator.markRunning(stale))
        assertFalse(coordinator.markStartFailed(stale))
        assertTrue(coordinator.markRunning(current))
        assertEquals(ServiceLifecycleCoordinator.State.RUNNING, coordinator.state)
    }

    @Test
    fun `duplicate start is coalesced and updates the start id used by failure cleanup`() {
        val coordinator = ServiceLifecycleCoordinator()
        val startup = checkNotNull(coordinator.requestStart(startId = 41))

        assertNull(coordinator.requestStart(startId = 42))

        assertEquals(42, coordinator.latestStartId(startup))
        assertTrue(coordinator.markStartFailed(startup))
        assertEquals(ServiceLifecycleCoordinator.State.STOPPED, coordinator.state)
    }

    @Test
    fun `stale stop completion cannot stop a newer startup`() {
        val coordinator = ServiceLifecycleCoordinator()
        val first = checkNotNull(coordinator.requestStart(startId = 1))
        assertTrue(coordinator.markRunning(first))
        val staleStop = coordinator.requestStop()
        coordinator.markStopped(staleStop)
        val latest = checkNotNull(coordinator.requestStart(startId = 3))

        coordinator.markStopped(staleStop)

        assertTrue(coordinator.isCurrent(latest))
        assertEquals(ServiceLifecycleCoordinator.State.STARTING, coordinator.state)
    }

    @Test
    fun `one thousand alternating requests finish in the requested state`() {
        val coordinator = ServiceLifecycleCoordinator()
        repeat(1_000) { index ->
            val token = checkNotNull(coordinator.requestStart(startId = index * 2 + 1))
            assertTrue(coordinator.markRunning(token))
            val stopGeneration = coordinator.requestStop()
            coordinator.markStopped(stopGeneration)
        }

        assertEquals(ServiceLifecycleCoordinator.State.STOPPED, coordinator.state)
    }
}
