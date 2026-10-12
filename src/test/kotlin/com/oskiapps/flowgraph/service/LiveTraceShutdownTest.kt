package com.oskiapps.flowgraph.service

import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTraceShutdownTest {
    @Test fun `quit closes transport and can restart after a client connects`() {
        val trace = LiveTraceService()
        try {
            val firstPort = trace.start(0).getOrThrow()
            Socket("127.0.0.1", firstPort).close()
            trace.releaseResources()
            assertFalse(trace.isRunning())
            assertNull(trace.status().port)
            assertTrue(trace.recentEvents().isEmpty())

            val secondPort = trace.start(0).getOrThrow()
            assertTrue(trace.isRunning())
            Socket("127.0.0.1", secondPort).close()
            trace.releaseResources()
            assertFalse(trace.isRunning())
            assertNull(trace.status().port)
        } finally {
            trace.dispose()
        }
    }

    @Test fun `quit frees the listener port when there are no pending connections`() {
        val trace = LiveTraceService()
        try {
            val port = trace.start(0).getOrThrow()
            trace.releaseResources()
            assertFalse(trace.isRunning())
            assertNull(trace.status().port)
            // No accepted or pending connections: rebinding is a valid check that the
            // listening socket itself has been closed (without TCP TIME_WAIT races).
            ServerSocket(port).use { replacement ->
                assertTrue(replacement.isBound)
            }
        } finally {
            trace.dispose()
        }
    }
}
