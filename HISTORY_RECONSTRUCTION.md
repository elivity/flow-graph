# Reconstructed feature history

This repository was initialized from an existing source snapshot. Its Git history
is **reconstructed** into feature-oriented commits to make the source easier to
review and maintain. These are **not** the original dates or original development
commits; early intermediate commits may not independently compile. The final
version bump records the assembled state as 0.20.0, not a verified release.

Validation in the packaging environment is limited to static source-boundary
checks and archive integrity: Gradle's wrapper distribution is not cached.
Run `./gradlew clean test buildPlugin` on a machine with a working Gradle
installation before publishing.
