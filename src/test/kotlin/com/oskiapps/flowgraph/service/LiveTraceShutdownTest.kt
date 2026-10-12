package com.oskiapps.flowgraph.service

import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LiveTraceShutdownTest {
    @Test fun `quit closes port and can restart live transport`() {
        val trace = LiveTraceService()
        try {
            val firstPort = trace.start(0).getOrThrow()
            Socket("127.0.0.1", firstPort).close()
            trace.releaseResources()
            assertFalse(trace.isRunning())
            assertTrue(trace.recentEvents().isEmpty())
            ServerSocket(firstPort).close()

            val secondPort = trace.start(0).getOrThrow()
            assertTrue(trace.isRunning())
            Socket("127.0.0.1", secondPort).close()
            trace.releaseResources()
            assertFalse(trace.isRunning())
            ServerSocket(secondPort).close()
        } finally {
            trace.dispose()
        }
    }
}
