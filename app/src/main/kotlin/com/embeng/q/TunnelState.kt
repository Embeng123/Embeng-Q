package com.embeng.q

import java.util.concurrent.CopyOnWriteArraySet

enum class TunnelPhase { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, STOPPING }

data class TunnelSnapshot(
    val phase: TunnelPhase = TunnelPhase.DISCONNECTED,
    val detail: String = "",
    val interfaceName: String? = null
) {
    val isActive: Boolean get() = phase != TunnelPhase.DISCONNECTED
}

/** Process-wide service state survives Activity recreation without claiming a connection. */
object TunnelState {
    @Volatile var snapshot = TunnelSnapshot()
        private set
    private val listeners = CopyOnWriteArraySet<(TunnelSnapshot) -> Unit>()

    fun publish(value: TunnelSnapshot) {
        snapshot = value
        listeners.forEach { it(value) }
    }

    fun observe(listener: (TunnelSnapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun removeObserver(listener: (TunnelSnapshot) -> Unit) {
        listeners.remove(listener)
    }
}

data class TrafficSample(val received: Long, val transmitted: Long, val elapsedMillis: Long)
data class TrafficRate(val download: Long, val upload: Long)

class TunnelSpeedMeter {
    private var previous: TrafficSample? = null

    fun reset() { previous = null }

    fun update(sample: TrafficSample?): TrafficRate? {
        val last = previous
        previous = sample
        if (sample == null) return null
        if (last == null || sample.elapsedMillis <= last.elapsedMillis ||
            sample.received < last.received || sample.transmitted < last.transmitted) {
            return TrafficRate(0, 0)
        }
        val seconds = (sample.elapsedMillis - last.elapsedMillis) / 1000.0
        return TrafficRate(((sample.received - last.received) / seconds).toLong(),
            ((sample.transmitted - last.transmitted) / seconds).toLong())
    }
}
