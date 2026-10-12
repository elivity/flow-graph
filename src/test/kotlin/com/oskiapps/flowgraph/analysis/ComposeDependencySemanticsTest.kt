package com.oskiapps.flowgraph.analysis

import com.oskiapps.flowgraph.model.CausalConfidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ComposeDependencySemanticsTest {
    @Test
    fun `collecting without a verified value read is only a possible UI dependency`() {
        val evidence = ComposeValueReadEvidence.UNVERIFIED
        assertEquals(CausalConfidence.POSSIBLE, composeDependencyConfidence(evidence))
        assertTrue(composeDependencyLabel(evidence).contains("not verified"))
    }

    @Test
    fun `a verified value read is a static invalidation dependency, not an observed recomposition`() {
        val evidence = ComposeValueReadEvidence.VALUE_READ
        assertNull(composeDependencyConfidence(evidence))
        assertTrue(composeDependencyLabel(evidence).contains("can invalidate"))
    }
}
