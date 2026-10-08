# Recovery from duplicate-declaration cleanup

This source tree was recovered from the *pre-cleanup*, responsibility-named
`flow-graph-state-ownership-refactor` snapshot rather than attempting to delete
arbitrary declarations from the corrupted cleanup. That snapshot includes the
navigation-history and live-event-buffer ownership changes.

The broken cleanup had accidentally retained legacy numbered implementations
alongside responsibility-named replacements (`AnalysisOperations01/02`,
`FlowGraphOperations01/02`), causing conflicting overloads and cascading
unresolved-reference/type-inference errors.

`RefactorIntegrityTest` checks that the legacy fragments stay absent and key
extracted analyzer and graph API entrypoints are implemented once (except the
two intentional `buildGraph` overloads). Existing architectural, model, and
state-ownership tests have been preserved.

**Build status:** `./gradlew --offline compileKotlin test` was attempted but the
wrapper tried to download Gradle 9.0.0 and failed due to unavailable network.
The project is not certified to compile until it passes on a workstation with
Gradle available. Do not overwrite a known-good working checkout without a
backup. Run `./gradlew clean test buildPlugin` locally.
