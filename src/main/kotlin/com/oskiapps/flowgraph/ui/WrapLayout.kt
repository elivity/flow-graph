package com.oskiapps.flowgraph.ui

import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JScrollPane
import javax.swing.SwingUtilities

/**
 * FlowLayout that reports a preferred height based on the width actually available to it.
 *
 * Plain FlowLayout does wrap during layout, but its preferred size is normally one long row.
 * In an IDE tool window that means BorderLayout may allocate only one row of height and clip
 * the wrapped controls. This implementation makes the preferred/minimum height width-aware.
 */
class WrapLayout : FlowLayout {
    constructor() : super()
    constructor(align: Int) : super(align)
    constructor(align: Int, hgap: Int, vgap: Int) : super(align, hgap, vgap)

    override fun preferredLayoutSize(target: Container): Dimension = layoutSize(target, true)

    override fun minimumLayoutSize(target: Container): Dimension {
        val minimum = layoutSize(target, false)
        // Allows the parent to shrink enough to trigger another wrapped row.
        minimum.width = (minimum.width - (hgap + 1)).coerceAtLeast(0)
        return minimum
    }

    private fun layoutSize(target: Container, preferred: Boolean): Dimension {
        synchronized(target.treeLock) {
            val parentWidth = target.parent?.width ?: 0
            val availableWidth = when {
                target.width > 0 -> target.width
                parentWidth > 0 -> parentWidth
                else -> Int.MAX_VALUE
            }

            val insets = target.insets
            val horizontalInsetsAndGap = insets.left + insets.right + hgap * 2
            val maxWidth = if (availableWidth == Int.MAX_VALUE) {
                Int.MAX_VALUE
            } else {
                (availableWidth - horizontalInsetsAndGap).coerceAtLeast(1)
            }

            val result = Dimension(0, 0)
            var rowWidth = 0
            var rowHeight = 0

            for (component in target.components) {
                if (!component.isVisible) continue
                val size = if (preferred) component.preferredSize else component.minimumSize

                if (rowWidth > 0 && rowWidth + hgap + size.width > maxWidth) {
                    addRow(result, rowWidth, rowHeight)
                    rowWidth = 0
                    rowHeight = 0
                }

                if (rowWidth > 0) rowWidth += hgap
                rowWidth += size.width
                rowHeight = maxOf(rowHeight, size.height)
            }

            addRow(result, rowWidth, rowHeight)
            result.width += horizontalInsetsAndGap
            result.height += insets.top + insets.bottom + vgap * 2

            // Swing's viewport can otherwise repeatedly ask for a width that includes the
            // vertical scrollbar and oscillate between two wrapping configurations.
            if (SwingUtilities.getAncestorOfClass(JScrollPane::class.java, target) != null) {
                result.width = (result.width - (hgap + 1)).coerceAtLeast(0)
            }

            return result
        }
    }

    private fun addRow(result: Dimension, rowWidth: Int, rowHeight: Int) {
        if (rowWidth <= 0 || rowHeight <= 0) return
        result.width = maxOf(result.width, rowWidth)
        if (result.height > 0) result.height += vgap
        result.height += rowHeight
    }
}
