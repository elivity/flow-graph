package com.oskiapps.flowgraph.model

internal fun FlowEdge.isPossibleCoupling(): Boolean =
        kind == EdgeKind.POSSIBLY_TRIGGERS_WRITE || confidence == CausalConfidence.POSSIBLE

internal fun FlowEdge.effectiveConfidence(): CausalConfidence =
        if (kind == EdgeKind.POSSIBLY_TRIGGERS_WRITE || confidence == CausalConfidence.POSSIBLE) {
            CausalConfidence.POSSIBLE
        } else {
            CausalConfidence.DEFINITE
        }
