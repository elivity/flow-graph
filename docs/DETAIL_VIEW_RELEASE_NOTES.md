# Detail-view UI changes (0.20.3–0.20.4)

The following changes are recorded individually in Git and preserved in their original commits:

| Commit | Feature |
| --- | --- |
| `6e2d932` | Stable, one-shot fit on entering a focused graph; default causal depth 7 |
| `16ffd2d` | Remove the verbose node-debug inspector and affected-flow lists |
| `4aa49c1` | Restore a compact Compose-render sidebar for inspectable Compose nodes; stabilize the detail viewport |
| `7527302` | Bump release to 0.20.3 and update publishing documentation |

Expected interaction contract:

- Entering a focused graph may trigger one automatic fit after the viewport has settled.
- Clicking another visible node in the existing detail graph updates selection without moving the camera.
- The right sidebar is reserved for an eligible Compose render, not the removed debug listing.
- Manual zoom/pan takes precedence over pending automatic fitting.
- The default causal depth is seven; All allows unbounded traversal.

This document summarizes existing commits and does not imply each historical commit compiled independently.
