package com.oskiapps.flowgraph.ui

import com.oskiapps.flowgraph.model.*
import com.oskiapps.flowgraph.ui.FlowGraphPanel.Companion.MAX_DETAIL_ITEMS
import java.awt.Rectangle

internal fun Rectangle.centerX(): Int = x + width / 2

internal fun Rectangle.centerY(): Int = y + height / 2
