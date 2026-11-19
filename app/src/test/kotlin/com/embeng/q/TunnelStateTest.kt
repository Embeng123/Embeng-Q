package com.embeng.q

import org.junit.Assert.*
import org.junit.After
import org.junit.Test

class TunnelStateTest {
    @After fun resetState() { TunnelState.publish(TunnelSnapshot()) }

    @Test fun recreatedScreenImmediatelyReceivesCurrentServiceState() {
        val connected = TunnelSnapshot(TunnelPhase.CONNECTED, interfaceName = "tun0")
        TunnelState.publish(connected)
        val received = mutableListOf<TunnelSnapshot>()
        val listener: (TunnelSnapshot) -> Unit = { received.add(it) }
        try {
            TunnelState.observe(listener)
            assertEquals(listOf(connected), received)
            TunnelState.publish(TunnelSnapshot(TunnelPhase.RECONNECTING))
            assertEquals(TunnelPhase.RECONNECTING, received.last().phase)
            TunnelState.removeObserver(listener)
            TunnelState.publish(TunnelSnapshot())
            assertEquals(2, received.size)
        } finally { TunnelState.removeObserver(listener) }
    }

    @Test fun speedUsesCounterDeltasAndMonotonicElapsedTime() {
        val meter = TunnelSpeedMeter()
        assertEquals(TrafficRate(0, 0), meter.update(TrafficSample(1000, 500, 100)))
        assertEquals(TrafficRate(2000, 1000), meter.update(TrafficSample(4000, 2000, 1600)))
    }

    @Test fun missingCountersAndRecreatedInterfaceResetSpeedBaseline() {
        val meter = TunnelSpeedMeter()
        meter.update(TrafficSample(9000, 6000, 1000))
        assertNull(meter.update(null))
        assertEquals(TrafficRate(0, 0), meter.update(TrafficSample(10, 20, 2000)))
        assertEquals(TrafficRate(0, 0), meter.update(TrafficSample(0, 0, 3000)))
        meter.reset()
        assertEquals(TrafficRate(0, 0), meter.update(TrafficSample(4000, 5000, 4000)))
    }

    @Test fun unchangedClockDoesNotDivideByZero() {
        val meter = TunnelSpeedMeter()
        meter.update(TrafficSample(10, 20, 1000))
        assertEquals(TrafficRate(0, 0), meter.update(TrafficSample(20, 40, 1000)))
    }
}
