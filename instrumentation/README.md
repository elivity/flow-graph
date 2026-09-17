# Flow Graph automatic runtime instrumentation v0.11

This included Gradle build provides:

- `dev.flowgraph.instrumentation` — AGP ASM instrumentation plugin
- `dev.flowgraph:flowgraph-runtime:0.11.3` — debug runtime transport/helper

v0.11 instruments debug builds globally by default and traces:

- Flow/StateFlow/SharedFlow field registration
- StateFlow `getValue`, `setValue`, `compareAndSet`
- SharedFlow `tryEmit`
- suspended SharedFlow `emit` requests
- `Flow.collect(FlowCollector, Continuation)` collector binding
- `FlowCollector.emit(value, Continuation)` value delivery
- Flow-returning call sites for best-effort intermediate operator runtime IDs

Suggested app configuration:

```kotlin
flowGraphInstrumentation {
    instrumentAllEligibleFlows.set(true)
    traceReads.set(true)
    traceColdFlows.set(true)
    traceSuspendingEmits.set(true)
}
```

Deep cold-flow tracing is intentionally debug-only and has higher runtime/build overhead than StateFlow-only tracing.
