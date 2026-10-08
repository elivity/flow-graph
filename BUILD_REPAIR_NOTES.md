# Compiler repair for 2026-10-08 log

This patch addresses source-extraction breakage identified by the local `./gradlew buildPlugin` log:

- Makes nested analyzer and canvas type references explicit at call sites.
- Imports companion constants into top-level Kotlin extension files.
- Moves graph causality extension helpers to a shared `FlowEdgeCausality.kt` file.
- Moves rectangle coordinate helpers into `RectangleGeometry.kt`.
- Moves formatting helpers into `GraphDetailFormatting.kt`.
- Imports the extracted `analyzeFrom` extension into the action entrypoint.
- Retains responsibility-named modules and removes the legacy numbered source files.

Compilation remains **unverified**. Run `./gradlew clean test buildPlugin` locally. The original error report includes hundreds of cascading errors, and any additional compiler diagnostics need to be addressed from the first remaining error forward.
