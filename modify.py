from pathlib import Path
p=Path('src/main/kotlin/com/oskiapps/flowgraph/ui/FlowGraphPanel.kt')
s=p.read_text()
s=s.replace('    private val zoomOut = JButton("−").apply {','    private val backToGraph = JButton("← Back").apply {\n        isEnabled = false\n        toolTipText = "Return to the complete graph from focused detail"\n        margin = Insets(2, 7, 2, 7)\n    }\n    private val zoomOut = JButton("−").apply {',1)
s=s.replace('        zoomOut.addActionListener { cancelPendingAutoFit(); canvas.zoomOut() }','        backToGraph.addActionListener { restoreFullGraph() }\n        zoomOut.addActionListener { cancelPendingAutoFit(); canvas.zoomOut() }',1)
s=s.replace('            add(showAll)\n            add(zoomOut)','            add(backToGraph)\n            add(showAll)\n            add(zoomOut)',1)
s=s.replace('        selectionFocusActive = true\n        resetDetailExpansionDepths()','        selectionFocusActive = true\n        backToGraph.isEnabled = true\n        resetDetailExpansionDepths()',1)
s=s.replace('        selectionFocusActive = false\n        selectedNodeId = null\n        resetDetailExpansionDepths()','        selectionFocusActive = false\n        selectedNodeId = null\n        backToGraph.isEnabled = false\n        resetDetailExpansionDepths()',1)
s=s.replace('        selectedNodeId = null\n        selectionFocusActive = false\n        resetDetailExpansionDepths()','        selectedNodeId = null\n        selectionFocusActive = false\n        backToGraph.isEnabled = false\n        resetDetailExpansionDepths()',1)
# Zoom should cap at a meaningful graph reading scale; reject stale wheel gestures at boundaries.
s=s.replace('        pendingWheelZoomExponent += -wheelRotation * WHEEL_ZOOM_SENSITIVITY','        pendingWheelZoomExponent = (pendingWheelZoomExponent -\n            wheelRotation * WHEEL_ZOOM_SENSITIVITY).coerceIn(-0.8, 0.8)',1)
s=s.replace('        val targetZoom = zoom * Math.exp(exponent)\n        applyZoomAroundModelPoint','        val targetZoom = (zoom * Math.exp(exponent))\n            .coerceIn(currentMinZoom(), currentMaxZoom())\n        applyZoomAroundModelPoint',1)
s=s.replace('        const val MAX_ZOOM = 3.50','        const val MAX_ZOOM = 2.50',1)
s=s.replace('        const val MIN_ZOOM = 0.20','        const val MIN_ZOOM = 0.30',1)
# Routing: for each edge find a vertical trunk that intersects fewest unrelated nodes and existing trunks.
old='''                routes[seed.edge] = buildSingleCircuitRoute(seed.edge, a, b, laneOffset = laneOffset, forcedTrunkX = trunkBase)'''
new='''                val candidates = (0..14).flatMap { step ->
                    if (step == 0) listOf(trunkBase + laneOffset)
                    else listOf(
                        trunkBase + laneOffset + step * CIRCUIT_GRID_SPACING,
                        trunkBase + laneOffset - step * CIRCUIT_GRID_SPACING,
                    )
                }
                val route = candidates.map { trunk ->
                    buildSingleCircuitRoute(seed.edge, a, b, laneOffset = 0,
                        forcedTrunkX = trunk)
                }.minByOrNull { candidate ->
                    circuitRoutePenalty(candidate, seed.edge, bounds, routes.values)
                } ?: seed.points
                routes[seed.edge] = route'''
assert old in s
s=s.replace(old,new,1)
marker='''    private fun centeredLaneOffsets(count: Int): List<Int> {'''
helper='''    /** Penalize crossing unrelated nodes and reusing an occupied wire segment. */
    private fun circuitRoutePenalty(
        route: List<Point>,
        edge: FlowEdge,
        bounds: Map<String, Rectangle>,
        existing: Collection<List<Point>>,
    ): Long {
        var score = 0L
        val obstacles = bounds.filterKeys { it != edge.from && it != edge.to }
            .values.map { Rectangle(it).apply { grow(12, 12) } }
        for ((a, b) in route.zipWithNext()) {
            val segment = Rectangle(
                minOf(a.x, b.x), minOf(a.y, b.y),
                kotlin.math.abs(b.x - a.x).coerceAtLeast(1),
                kotlin.math.abs(b.y - a.y).coerceAtLeast(1),
            )
            score += obstacles.count { it.intersects(segment) } * 100_000L
            for (wire in existing) {
                for ((c, d) in wire.zipWithNext()) {
                    if (a.x == b.x && c.x == d.x && a.x == c.x &&
                        maxOf(minOf(a.y, b.y), minOf(c.y, d.y)) <
                        minOf(maxOf(a.y, b.y), maxOf(c.y, d.y))) score += 5000
                    if (a.y == b.y && c.y == d.y && a.y == c.y &&
                        maxOf(minOf(a.x, b.x), minOf(c.x, d.x)) <
                        minOf(maxOf(a.x, b.x), maxOf(c.x, d.x))) score += 5000
                }
            }
            score += (kotlin.math.abs(a.x - b.x) + kotlin.math.abs(a.y - b.y)).toLong()
        }
        return score
    }

'''
assert marker in s
s=s.replace(marker,helper+marker,1)
# Toggle triggers rerouting dynamically (repaint already); layout node spacing current is generous.
p.write_text(s)
