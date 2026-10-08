# Optional live runtime bridge — v0.8.1

This helper is debug-only and opt-in. It sends real StateFlow emissions to the Flow Graph IDE plugin and now logs its connectivity explicitly under the Logcat tag **`FlowGraphTrace`**.

1. Copy `FlowGraphRuntime.kt` into your app's `src/debug/kotlin/...` source set.
2. Ensure the debug app has `<uses-permission android:name="android.permission.INTERNET"/>`.
3. Turn **Live trace** on in the IDE Flow Graph window.
4. Run:

```bash
adb reverse tcp:50737 tcp:50737
adb reverse --list
```

5. Register flows using the exact runtime key shown in the IDE node details:

```kotlin
state.traceFlowGraph(viewModelScope, "com.example.player.PlayerViewModel.state")
playbackState.traceFlowGraph(viewModelScope, "com.example.player.PlayerViewModel.playbackState")
```

In Logcat, filter by:

```text
FlowGraphTrace
```

You should see messages such as:

```text
Registered StateFlow: com.example.player.PlayerViewModel.state
Connected to Flow Graph live trace at 127.0.0.1:50737
SEND initial com.example.player.PlayerViewModel.state = ...
SEND emit com.example.player.PlayerViewModel.state = ...
```

If the reverse tunnel/server is missing, the helper logs a warning that includes:

```text
adb reverse tcp:50737 tcp:50737
```

The IDE v0.8.1 panel separately shows server/client status, received/matched/unmatched event counts, unmatched runtime keys, malformed protocol lines, and the last transport error.
