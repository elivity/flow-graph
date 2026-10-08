# Connection correctness audit

This audit reviews the static connection model used by Flow Graph. It is intentionally split into
**definite static relationships**, **possible/inferred relationships**, and **visual projections**.
The project is a static analysis/debugging tool rather than a whole-program verifier, so the goal is
to avoid presenting uncertain relationships as definite and to avoid adding visual shortcuts that
change the meaning of the analyzed graph.

## Correctness fixes in this audit

1. **Framework Compose nodes**
   - AndroidX calls are admitted as framework composition nodes only when the resolved callable is
     actually annotated `@Composable`.
   - PascalCase value factories/constructors such as `Offset(...)`, `Color(...)`, and `DpSize(...)`
     are therefore no longer composition edges.

2. **Compose State classification**
   - An ordinary value whose initializer contains `mutableStateOf(...)` is not itself classified as
     State merely because the factory appears somewhere in the initializer.
   - The untyped factory fallback is limited to delegated state properties.

3. **Derived Compose State**
   - A source read inside `derivedStateOf` creates `source -> derivedState`.
   - It does not also create a direct `source -> composable` shortcut.
   - The composable is connected when it actually reads the derived state.

4. **Flow -> Compose confidence**
   - Collapsed Flow-to-Compose edges retain POSSIBLE confidence if any required path segment is only
     possible.
   - The **Possible connections** filter can therefore remove possible-only UI influences.
   - If an equivalent relationship has at least one definite path, definite evidence wins.

5. **Custom Flow stages**
   - Unknown/custom stages that are admitted conservatively now propagate POSSIBLE confidence onto
     their structural edges instead of becoming definite after projection.

6. **State writes**
   - Delegated Compose State and `.value` writes recognize `=`, `+=`, `-=`, `*=`, `/=`, `%=` and
     `++`/`--` where applicable.

7. **Composition call-site identity**
   - Two calls from one composable to the same child declaration remain two distinct composition
     call sites when their source offsets differ.
   - Direct recursive composable calls are retained; visual expansion is bounded by the cycle guard.

8. **Visual edge focus**
   - Edge focus uses visual-instance IDs, not only canonical Flow IDs.
   - Downstream highlighting stops at a fan-out so selecting one consumer branch does not light the
     sibling branches of the same Flow variable.

## Checks run in this environment

- `FlowGraph.kt` was compiled with a minimal `VirtualFile` stub and executed against connection
  invariants for possible confidence, definite-vs-possible merging, and derived-state projection.
  Result: `MODEL_CONNECTION_CHECK_OK`.
- All Kotlin source files were passed through `kotlinc` as a parser/syntax check. IntelliJ/Android
  Studio dependencies are unavailable in this environment, so symbol-resolution compilation fails
  as expected, but no Kotlin parser errors or unclosed comments were found.
- Block-comment delimiters were checked across all Kotlin sources.
- The final ZIP is integrity-tested after packaging.

## Known static-analysis boundaries

These are cases where the current analyzer cannot honestly guarantee an exact connection:

- A Compose State read that occurs only inside a later **non-composable callback/effect lambda**
  (for example `onClick = { state.value ... }`) can still be conservatively attributed to the
  enclosing composable as a recomposition dependency. Correctly distinguishing every callback from
  composable content requires deeper function-type/call-site analysis.
- A Flow selected/created only inside a complex higher-order operator lambda (for example an inner
  Flow returned from `flatMapLatest`) may be incomplete when it is not a direct receiver or direct
  Flow-valued argument.
- Reflection, dynamic dispatch, unusual custom Flow/State subtypes, and arbitrary custom operators
  can remain unresolved. The analyzer prefers omission or POSSIBLE confidence rather than inventing
  a definite connection.
- Project-wide discovery has explicit responsiveness caps. If a cap is hit, diagnostics report that
  the project graph may be incomplete; focused analysis should be used for the local area.

Because of these boundaries, this audit does **not** claim mathematical proof that every runtime
connection in an arbitrary Android application is captured. It does verify and strengthen the
connection semantics that the current analyzer represents, and documents the remaining cases where
its static model can be conservative or incomplete.
