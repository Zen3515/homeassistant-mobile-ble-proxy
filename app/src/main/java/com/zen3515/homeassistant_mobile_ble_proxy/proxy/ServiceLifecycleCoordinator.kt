package com.zen3515.homeassistant_mobile_ble_proxy.proxy

internal class ServiceLifecycleCoordinator {
    enum class State {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING,
    }

    data class StartToken(
        val generation: Long,
        val startId: Int,
    )

    var state: State = State.STOPPED
        private set

    private var generation: Long = 0
    private var latestStartId: Int = 0

    fun requestStart(startId: Int): StartToken? {
        latestStartId = maxOf(latestStartId, startId)
        if (state == State.STARTING || state == State.RUNNING) return null
        generation += 1
        state = State.STARTING
        return StartToken(generation = generation, startId = startId)
    }

    fun requestStop(): Long {
        generation += 1
        state = State.STOPPING
        return generation
    }

    fun markRunning(token: StartToken): Boolean {
        if (!isCurrent(token) || state != State.STARTING) return false
        state = State.RUNNING
        return true
    }

    fun markStartFailed(token: StartToken): Boolean {
        if (!isCurrent(token) || state != State.STARTING) return false
        state = State.STOPPED
        return true
    }

    fun markStopped(stopGeneration: Long) {
        if (generation == stopGeneration && state == State.STOPPING) {
            state = State.STOPPED
        }
    }

    fun isCurrent(token: StartToken): Boolean = token.generation == generation

    fun latestStartId(token: StartToken): Int? {
        return latestStartId.takeIf { isCurrent(token) }
    }
}
