package dev.flowgraph.runtime;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

/**
 * Debug-only runtime used by Flow Graph v0.11.
 *
 * v0.11 traces three complementary layers without attaching a debugger:
 *
 *  1. StateFlow writes / reads (setValue, compareAndSet, update lowering)
 *  2. SharedFlow writes (tryEmit and suspended emit requests)
 *  3. Arbitrary cold Flow runtime delivery. Instrumented Flow.collect() binds collector identity to
 *     a Flow property, and every FlowCollector.emit() crossing that collector is reported as a
 *     delivery. This works through flow {}, map/filter/combine/flatMap*, callbackFlow/channelFlow,
 *     custom Flow implementations and most library operators because they eventually cross the
 *     Flow.collect / FlowCollector.emit protocol.
 *
 * The runtime deliberately keeps no compile-time Android/coroutines dependency. All detection is
 * reflective / identity-based so the helper cannot change the app's coroutine dependency graph.
 */
public final class FlowGraphAutoRuntime {
    private static final String TAG = "FlowGraphTrace";
    private static final Object NO_VALUE = new Object();
    private static final Object LOCK = new Object();

    /**
     * Compose Snapshot state reads performed by the tracer must never participate in the app's
     * current read observation. Otherwise merely inspecting a MutableState while a composable is
     * executing registers an artificial dependency and can make the tracer itself cause repeated
     * recompositions. Resolve Snapshot.withoutReadObservation reflectively so this debug runtime
     * keeps zero compile-time dependency on Compose/Kotlin.
     */
    private static final Object SNAPSHOT_OBSERVER_LOCK = new Object();
    private static volatile boolean snapshotWithoutReadObservationResolved = false;
    private static volatile Object snapshotCompanion = null;
    private static volatile Method snapshotWithoutReadObservationMethod = null;
    private static volatile Class<?> kotlinFunction0Class = null;

    /** Runtime object -> source property keys. Identity semantics are required for Flow objects. */
    private static final IdentityHashMap<Object, LinkedHashSet<String>> KEYS_BY_OBJECT = new IdentityHashMap<>();
    /**
     * Compose State is different from StateFlow: one source declaration can have many simultaneous
     * runtime objects (Lazy items, keyed remember blocks, repeated composables). Keep those objects
     * in a weak identity registry so values are compared per instance without retaining disposed
     * compositions forever. The source runtime key remains shared so the IDE can aggregate all
     * instances onto the same static graph node.
     */
    private static final ReferenceQueue<Object> COMPOSE_STATE_REFERENCE_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, ComposeStateRuntime> COMPOSE_STATE_RUNTIME = new HashMap<>();
    private static int nextComposeStateInstanceId = 1;

    /** Collector identity -> Flow keys whose collect() call supplied that collector. */
    private static final IdentityHashMap<Object, CollectorBinding> COLLECTOR_BINDINGS = new IdentityHashMap<>();

    private static final Map<String, Object> LAST_VALUE_BY_KEY = new ConcurrentHashMap<>();
    private static final Set<String> REGISTERED_KEYS = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Long> LAST_READ_BY_SITE = new ConcurrentHashMap<>();
    /** Last actual (non-skipped) source composable body execution, used only for UI ranking. */
    private static final Map<String, Long> LAST_COMPOSE_EXECUTION_BY_KEY = new ConcurrentHashMap<>();
    /**
     * Strong registry of CompositionData objects observed in the running app.
     *
     * v0.16.6 cached one weak slot-table reference per composable key. That made Render UI depend
     * on the lifetime of a particular cached reference and prevented searching sibling/root
     * compositions (dialogs, popups, subcompositions). v0.17.30 keeps a bounded strong registry and
     * searches every observed live composition on demand. Detached layout nodes are ignored during
     * rendering, so stale compositions cannot win a render match.
     */
    private static final Object COMPOSE_LOCK = new Object();
    private static final IdentityHashMap<Object, ActiveComposition> ACTIVE_COMPOSITIONS = new IdentityHashMap<>();
    /**
     * Fast de-duplication for slot-table registration. The Gradle transform reaches the outer
     * Compose group on every invocation, including skipped invocations. Reflecting CompositionData
     * and taking COMPOSE_LOCK every time was both noisy and expensive. Successful registrations are
     * remembered per Composer identity + runtime key + compiler group key. Weak identities avoid
     * retaining dead composers.
     */
    private static final ReferenceQueue<Object> COMPOSER_REFERENCE_QUEUE = new ReferenceQueue<>();
    private static final Map<IdentityWeakReference, ComposerInspectionRuntime> COMPOSE_INSPECTION_SEEN = new HashMap<>();
    private static final int MAX_ACTIVE_COMPOSITIONS = 64;
    private static long composeInspectionAttempts = 0L;
    private static long composeInspectionSuccesses = 0L;
    private static long composeInspectionMissingData = 0L;
    private static String lastComposeInspectionFailure = null;
    private static final Map<String, String> COMPOSE_SITE_BY_KEY = new ConcurrentHashMap<>();
    /** Exact Compose compiler group keys captured from startRestartGroup/startReplaceGroup. */
    private static final Map<String, Set<Integer>> COMPOSE_GROUP_KEYS_BY_KEY = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_COMPOSE_IMAGE_HASH_BY_KEY = new ConcurrentHashMap<>();
    private static final int MAX_COMPOSE_IMAGE_BYTES = 4 * 1024 * 1024;
    private static final long COMPOSE_CAPTURE_DELAY_MS = 32L;
    private static final int COMPOSE_GEOMETRY_RETRIES = 3;

    private static final ThreadLocal<java.util.ArrayDeque<PendingWrite>> PENDING_WRITES =
            new ThreadLocal<java.util.ArrayDeque<PendingWrite>>() {
                @Override protected java.util.ArrayDeque<PendingWrite> initialValue() {
                    return new java.util.ArrayDeque<>();
                }
            };

    private static final ScheduledExecutorService IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FlowGraphTrace");
        t.setDaemon(true);
        return t;
    });

    /**
     * Keep connection establishment off the event writer. A debug app may start before Live Trace
     * is enabled in the IDE (or before adb reverse is installed). In older builds the runtime only
     * attempted to connect while sending an event, so the IDE could remain on "waiting for client"
     * indefinitely when the app was otherwise idle. This small background watcher makes the
     * transport self-healing without delaying event delivery.
     */
    private static final ScheduledExecutorService CONNECTION_IO = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "FlowGraphTrace-Connect");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean CONNECTION_WATCH_STARTED = new AtomicBoolean(false);
    private static volatile int failedConnectionAttempts = 0;

    private static volatile Socket socket;
    private static volatile BufferedWriter writer;
    private static volatile boolean enabled = true;
    private static volatile boolean logEvents = false;
    private static volatile String host = "127.0.0.1";
    private static volatile int port = 50737;
    private static volatile int connectTimeoutMs = 2_000;

    /**
     * Hot StateFlow/Compose state can produce tens of thousands of callbacks per second. Queueing
     * one socket write for every callback can outrun both Android Studio and this single writer
     * thread. Coalesce identical source/kind/site events for a short window and preserve their
     * multiplicity in the wire protocol. The IDE still sees the correct accumulated activity
     * count, but transport/UI work is capped to roughly 20 packets/sec per hot event source.
     */
    private static final long TRACE_COALESCE_WINDOW_MS = 50L;
    private static final int MAX_PENDING_TRACE_BUCKETS = 2_048;
    private static final ConcurrentHashMap<String, PendingTrace> PENDING_TRACES = new ConcurrentHashMap<>();

    private static final class IdentityWeakReference extends WeakReference<Object> {
        private final int identityHash;

        IdentityWeakReference(Object referent, ReferenceQueue<Object> queue) {
            super(referent, queue);
            identityHash = System.identityHashCode(referent);
        }

        IdentityWeakReference(Object referent) {
            super(referent);
            identityHash = System.identityHashCode(referent);
        }

        @Override public int hashCode() {
            return identityHash;
        }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof IdentityWeakReference)) return false;
            Object left = get();
            Object right = ((IdentityWeakReference) other).get();
            return left != null && left == right;
        }
    }

    private static final class ComposeStateRuntime {
        final int instanceId;
        final LinkedHashSet<String> keys = new LinkedHashSet<>();
        final LinkedHashSet<String> exactPropertyKeys = new LinkedHashSet<>();
        final LinkedHashSet<String> delegatedAccessKeys = new LinkedHashSet<>();
        boolean delegatedAccessTracing = false;
        Object lastValue = NO_VALUE;

        ComposeStateRuntime(int instanceId) {
            this.instanceId = instanceId;
        }
    }

    private static final class ComposerInspectionRuntime {
        final Object compositionData;
        final LinkedHashSet<String> registrations = new LinkedHashSet<>();

        ComposerInspectionRuntime(Object compositionData) {
            this.compositionData = compositionData;
        }
    }

    private static final class PendingTrace {
        final String stateKey;
        final String kind;
        final Integer instanceId;
        String valueSummary;
        String site;
        final LinkedHashSet<String> fields = new LinkedHashSet<>();
        long timestampNanos;
        int occurrences;
        boolean scheduled;

        PendingTrace(String stateKey, String kind, Integer instanceId) {
            this.stateKey = stateKey;
            this.kind = kind;
            this.instanceId = instanceId;
        }
    }

    private FlowGraphAutoRuntime() {}

    public static void configure(boolean isEnabled, boolean shouldLogEvents, int tracePort) {
        enabled = isEnabled;
        logEvents = shouldLogEvents;
        port = tracePort;
    }

    /**
     * Install a transparent Window.Callback proxy on an Android Activity after onCreate(Bundle).
     *
     * The runtime intentionally has no Android compile-time dependency, so all Android types are
     * discovered reflectively. The proxy delegates every callback to the original Activity exactly
     * as before and only observes top-level user input on the way through. This gives the IDE a
     * reliable, framework-level interaction marker for both Compose and classic Views without
     * instrumenting individual click lambdas or replacing View listeners.
     */
    public static void installUiInteractionCapture(Object possibleActivity) {
        if (!enabled || possibleActivity == null) return;
        startConnectionWatch();
        try {
            Class<?> activityClass = Class.forName("android.app.Activity");
            if (!activityClass.isInstance(possibleActivity)) return;

            Object window = activityClass.getMethod("getWindow").invoke(possibleActivity);
            if (window == null) return;

            Class<?> callbackClass = Class.forName("android.view.Window$Callback");
            Method getCallback = window.getClass().getMethod("getCallback");
            Object original = getCallback.invoke(window);
            if (original == null || isUiInteractionProxy(original)) return;

            ClassLoader loader = possibleActivity.getClass().getClassLoader();
            if (loader == null) loader = FlowGraphAutoRuntime.class.getClassLoader();
            Object proxy = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] { callbackClass },
                    new UiInteractionWindowCallback(original, possibleActivity.getClass().getName())
            );
            Method setCallback = window.getClass().getMethod("setCallback", callbackClass);
            setCallback.invoke(window, proxy);
            logInfo("UI interaction timeline capture installed for " + possibleActivity.getClass().getName());
        } catch (Throwable error) {
            // Debug tooling must never change app behavior merely because interaction capture is
            // unavailable on a particular Android/API implementation.
            logWarn("Could not install UI interaction capture: " + error.getClass().getSimpleName()
                    + ": " + error.getMessage(), null);
        }
    }

    private static boolean isUiInteractionProxy(Object callback) {
        if (callback == null || !Proxy.isProxyClass(callback.getClass())) return false;
        try {
            return Proxy.getInvocationHandler(callback) instanceof UiInteractionWindowCallback;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Emit one non-coalesced profiler marker for a physical UI interaction. */
    public static void recordUiInteraction(String summary, String site) {
        if (!enabled) return;
        sendSummaryNow(
                "@ui-interaction",
                "ui-interaction",
                summary == null ? "interaction" : summary,
                site == null ? "" : site,
                Collections.<String>emptySet(),
                1,
                System.nanoTime(),
                null
        );
    }

    private static final class UiInteractionWindowCallback implements InvocationHandler {
        private final Object delegate;
        private final String ownerName;
        private long lastGenericScrollNanos = 0L;

        UiInteractionWindowCallback(Object delegate, String ownerName) {
            this.delegate = delegate;
            this.ownerName = ownerName == null ? "Activity" : ownerName;
        }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            String name = method.getName();
            try {
                if ("dispatchTouchEvent".equals(name) && args != null && args.length > 0 && args[0] != null) {
                    recordTouch(args[0]);
                } else if ("dispatchKeyEvent".equals(name) && args != null && args.length > 0 && args[0] != null) {
                    recordKey(args[0]);
                } else if ("dispatchGenericMotionEvent".equals(name) && args != null && args.length > 0 && args[0] != null) {
                    recordGenericMotion(args[0]);
                }
            } catch (Throwable ignored) {
                // Interaction diagnostics must never interfere with real event dispatch.
            }

            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException wrapped) {
                Throwable cause = wrapped.getCause();
                throw cause == null ? wrapped : cause;
            }
        }

        private void recordTouch(Object event) {
            int action = invokeIntNoArg(event, "getActionMasked", -1);
            // One bubble marks the beginning of one finger gesture. MOVE/UP can occur dozens of
            // times during a drag and would make the marker strip noisy without adding causality.
            if (action != 0 /* MotionEvent.ACTION_DOWN */) return;
            float x = invokeFloatNoArg(event, "getX", Float.NaN);
            float y = invokeFloatNoArg(event, "getY", Float.NaN);
            StringBuilder summary = new StringBuilder("touch");
            if (!Float.isNaN(x) && !Float.isNaN(y)) {
                summary.append(" @ ").append(Math.round(x)).append(',').append(Math.round(y));
            }
            recordUiInteraction(summary.toString(), ownerName);
        }

        private void recordKey(Object event) {
            int action = invokeIntNoArg(event, "getAction", -1);
            int repeat = invokeIntNoArg(event, "getRepeatCount", 0);
            if (action != 0 /* KeyEvent.ACTION_DOWN */ || repeat != 0) return;
            int keyCode = invokeIntNoArg(event, "getKeyCode", -1);
            String label = keyCode >= 0 ? keyCodeLabel(event, keyCode) : "key";
            recordUiInteraction(label, ownerName);
        }

        private void recordGenericMotion(Object event) {
            int action = invokeIntNoArg(event, "getActionMasked", -1);
            if (action != 8 /* MotionEvent.ACTION_SCROLL */) return;
            long now = System.nanoTime();
            // Mouse/trackpad scrolling can produce a burst of callbacks. Preserve the fact that the
            // user interacted while keeping the marker row readable.
            if (now - lastGenericScrollNanos < 75_000_000L) return;
            lastGenericScrollNanos = now;
            recordUiInteraction("scroll", ownerName);
        }

        private static int invokeIntNoArg(Object target, String name, int fallback) {
            try {
                Object value = target.getClass().getMethod(name).invoke(target);
                return value instanceof Number ? ((Number) value).intValue() : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static float invokeFloatNoArg(Object target, String name, float fallback) {
            try {
                Object value = target.getClass().getMethod(name).invoke(target);
                return value instanceof Number ? ((Number) value).floatValue() : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }

        private static String keyCodeLabel(Object event, int keyCode) {
            try {
                Method method = event.getClass().getMethod("keyCodeToString", int.class);
                Object text = method.invoke(null, keyCode);
                if (text != null) return String.valueOf(text);
            } catch (Throwable ignored) {
                // Fall through to the numeric representation.
            }
            return "key " + keyCode;
        }
    }

    /**
     * Called by instrumented Flow-typed field reads/writes. Generic cold Flow objects are associated
     * only with their exact property object. StateFlow/SharedFlow wrappers are additionally walked a
     * few levels so readonly wrappers still correlate with their mutable implementation object.
     */
    public static void registerFlow(Object flow, String runtimeKey) {
        if (!enabled || flow == null || runtimeKey == null || runtimeKey.length() == 0) return;
        startConnectionWatch();
        if (!looksLikeAnyFlow(flow)) return;

        final boolean composeState = looksLikeComposeState(flow);
        final Object current = composeState ? readValue(flow) : NO_VALUE;

        if (composeState) {
            final ComposeStateRuntime runtime;
            final boolean newKeyAssociation;
            synchronized (LOCK) {
                runtime = composeStateRuntimeLocked(flow, true);
                newKeyAssociation = runtime.keys.add(runtimeKey);
                if (current != NO_VALUE && runtime.lastValue == NO_VALUE) {
                    runtime.lastValue = normalize(current);
                }
            }

            // Initial Compose values are instance snapshots, not mutations. Emit one snapshot for
            // every newly associated runtime object/key so the IDE can present stable per-instance
            // values immediately without pretending the first observation was a state change.
            if (newKeyAssociation) {
                if (current != NO_VALUE) {
                    LAST_VALUE_BY_KEY.put(runtimeKey, normalize(current));
                    emit(runtimeKey, "initial", current, "auto-register", Collections.<String>emptySet(), runtime.instanceId);
                }
                logInfo(
                        "Auto-registered Compose state: " + runtimeKey + " instance#" + runtime.instanceId
                                + " (" + flow.getClass().getName() + ")"
                );
            }
            return;
        }

        boolean firstObjectAssociation;
        synchronized (LOCK) {
            if (looksLikeStateOrSharedFlow(flow)) {
                firstObjectAssociation = associateHotFlowRecursively(
                        flow,
                        runtimeKey,
                        0,
                        new IdentityHashMap<Object, Boolean>()
                );
            } else {
                firstObjectAssociation = associateExact(flow, runtimeKey);
            }
        }

        Object hotCurrent = readValue(flow);
        if (REGISTERED_KEYS.add(runtimeKey)) {
            if (hotCurrent != NO_VALUE) {
                LAST_VALUE_BY_KEY.put(runtimeKey, normalize(hotCurrent));
                emit(runtimeKey, "initial", hotCurrent, "auto-register", Collections.<String>emptySet());
            }
            logInfo(
                    "Auto-registered reactive state: " + runtimeKey + " (" + flow.getClass().getName() + ")"
                            + (firstObjectAssociation ? "" : " [alias]")
            );
        }
    }

    /**
     * Exact identity for Kotlin delegated Compose state (`var x by MutableState`). Factory line
     * mappings are useful for ordinary State-valued locals, but delegated access gives us something
     * stronger: the actual KProperty name. Once observed, prefer file + source function + property
     * name over synthetic factory keys for every subsequent read/write event from this State object.
     */
    public static void registerComposeDelegatedState(
            Object state, Object propertyReference, String sourceFile, String sourceFunction
    ) {
        if (!enabled || state == null || propertyReference == null || !looksLikeComposeState(state)) return;
        String propertyName = kotlinPropertyName(propertyReference);
        if (propertyName == null || propertyName.length() == 0) return;
        if (sourceFile == null) sourceFile = "";
        if (sourceFunction == null || sourceFunction.length() == 0) sourceFunction = "<unknown>";
        String key = "@composestate-property|" + sourceFile + "|" + sourceFunction + "|" + propertyName;

        Object current = readValue(state);
        final ComposeStateRuntime runtime;
        final boolean newAssociation;
        synchronized (LOCK) {
            runtime = composeStateRuntimeLocked(state, true);
            newAssociation = runtime.exactPropertyKeys.add(key);
            runtime.keys.add(key);
            if (current != NO_VALUE && runtime.lastValue == NO_VALUE) {
                runtime.lastValue = normalize(current);
            }
        }

        if (newAssociation && current != NO_VALUE) {
            LAST_VALUE_BY_KEY.put(key, normalize(current));
            emit(key, "initial", current, "delegated-property-register",
                    Collections.<String>emptySet(), runtime.instanceId);
        }
    }


    /**
     * Exact delegated-State read. Unlike the legacy broad property key, this identity includes the
     * concrete source access line and access kind. This prevents inlined dependency code with a
     * generic local name such as `state` from being attributed to the user's local Compose State.
     */
    public static void recordComposeDelegatedRead(
            Object state,
            Object propertyReference,
            String sourceFile,
            String sourceFunction,
            int accessLine,
            String site
    ) {
        if (!enabled || state == null || propertyReference == null || !looksLikeComposeState(state)) return;
        String key = composeDelegatedAccessKey(
                propertyReference, sourceFile, sourceFunction, accessLine, "read"
        );
        if (key == null) return;

        Object current = readValue(state);
        final ComposeStateRuntime runtime;
        final boolean newAssociation;
        synchronized (LOCK) {
            runtime = composeStateRuntimeLocked(state, true);
            runtime.delegatedAccessTracing = true;
            newAssociation = runtime.delegatedAccessKeys.add(key);
            if (current != NO_VALUE && runtime.lastValue == NO_VALUE) {
                runtime.lastValue = normalize(current);
            }
        }

        if (newAssociation && current != NO_VALUE) {
            LAST_VALUE_BY_KEY.put(key, normalize(current));
            emit(key, "initial", current, siteWithKind(site, "delegated-read-register"),
                    Collections.<String>emptySet(), runtime.instanceId);
        }

        String throttleKey = key + "@" + runtime.instanceId + "@" + (site == null ? "" : site);
        long now = System.nanoTime();
        Long previous = LAST_READ_BY_SITE.put(throttleKey, now);
        if (previous == null || now - previous >= 25_000_000L) {
            emit(key, "read", null, site, Collections.<String>emptySet(), runtime.instanceId);
        }
    }

    /** Captures the exact delegated-State value immediately before the source setValue helper. */
    public static void beforeComposeDelegatedWrite(
            Object state,
            Object propertyReference,
            String sourceFile,
            String sourceFunction,
            int accessLine,
            String site
    ) {
        if (!enabled || state == null || propertyReference == null || !looksLikeComposeState(state)) return;
        String key = composeDelegatedAccessKey(
                propertyReference, sourceFile, sourceFunction, accessLine, "write"
        );
        if (key == null) return;

        Object before = readValue(state);
        final ComposeStateRuntime runtime;
        final boolean newAssociation;
        synchronized (LOCK) {
            runtime = composeStateRuntimeLocked(state, true);
            // Mark before forwarding to the Compose helper. Generic nested getValue/setValue hooks
            // now deliberately ignore this object; the outer delegated hook has the precise source
            // identity and is the only authority allowed to emit a change for it.
            runtime.delegatedAccessTracing = true;
            newAssociation = runtime.delegatedAccessKeys.add(key);
            if (before != NO_VALUE && runtime.lastValue == NO_VALUE) {
                runtime.lastValue = normalize(before);
            }
        }

        if (newAssociation && before != NO_VALUE) {
            LAST_VALUE_BY_KEY.put(key, normalize(before));
            emit(key, "initial", before, siteWithKind(site, "delegated-write-register"),
                    Collections.<String>emptySet(), runtime.instanceId);
        }

        PENDING_WRITES.get().push(new PendingWrite(
                state,
                "delegated-setValue",
                site,
                before == NO_VALUE ? NO_VALUE : normalize(before),
                key
        ));
    }

    /** Emits one change for the exact delegated source access, and only when before != after. */
    public static void afterComposeDelegatedWrite(
            Object state,
            Object propertyReference,
            String sourceFile,
            String sourceFunction,
            int accessLine,
            String site
    ) {
        if (!enabled || state == null) return;
        PendingWrite pending = popPendingFor(state);
        String key = pending == null ? composeDelegatedAccessKey(
                propertyReference, sourceFile, sourceFunction, accessLine, "write"
        ) : pending.eventKey;
        if (key == null) return;

        Object current = readValue(state);
        if (current == NO_VALUE) return;
        Object normalizedCurrent = normalize(current);
        Object before = pending == null ? NO_VALUE : pending.beforeValue;

        final ComposeStateRuntime runtime;
        final Object previous;
        synchronized (LOCK) {
            runtime = composeStateRuntimeLocked(state, true);
            runtime.delegatedAccessTracing = true;
            runtime.delegatedAccessKeys.add(key);
            previous = runtime.lastValue;
            runtime.lastValue = normalizedCurrent;
        }

        LAST_VALUE_BY_KEY.put(key, normalizedCurrent);
        if (before != NO_VALUE) {
            if (composeValuesEqual(before, normalizedCurrent)) return;
        } else if (previous == NO_VALUE || composeValuesEqual(previous, normalizedCurrent)) {
            return;
        }

        emit(
                key,
                "state-change",
                current,
                siteWithKind(pending == null ? site : pending.site, "delegated-setValue"),
                Collections.<String>emptySet(),
                runtime.instanceId
        );
    }

    private static String composeDelegatedAccessKey(
            Object propertyReference,
            String sourceFile,
            String sourceFunction,
            int accessLine,
            String accessKind
    ) {
        if (propertyReference == null) return null;
        String propertyName = kotlinPropertyName(propertyReference);
        if (propertyName == null || propertyName.length() == 0) return null;
        if (sourceFile == null) sourceFile = "";
        if (sourceFunction == null || sourceFunction.length() == 0) sourceFunction = "<unknown>";
        return "@composestate-access|" + sourceFile + "|" + sourceFunction + "|"
                + propertyName + "|" + accessLine + "|" + accessKind;
    }

    /** Backward-compatible entry point for APKs/plugins built from v0.10. */
    public static void registerState(Object flow, String runtimeKey) {
        registerFlow(flow, runtimeKey);
    }

    /**
     * Deep cold-flow hook. Called before an instrumented Flow.collect(collector, continuation).
     * The collector is now a runtime boundary for the receiver Flow. When any code later invokes
     * collector.emit(value, continuation), recordCollectorEmission() can attribute the value to the
     * exact Flow property that was collected.
     */
    public static void bindCollector(Object flow, Object collector, String collectSite) {
        if (!enabled || flow == null || collector == null) return;
        List<String> keys = keysFor(flow);
        if (keys.isEmpty() && looksLikeAnyFlow(flow)) {
            // Local/inline cold-flow chains may never be stored in a Flow-typed field and inline
            // operators may not leave a call-return site we can register. Give the collected Flow a
            // terminal synthetic identity so its delivered values can still light the collector node.
            String synthetic = "@collect|" + (collectSite == null ? "unknown" : collectSite);
            synchronized (LOCK) { associateExact(flow, synthetic); }
            keys = keysFor(flow);
        }
        if (keys.isEmpty()) return;

        boolean changed = false;
        synchronized (LOCK) {
            CollectorBinding previous = COLLECTOR_BINDINGS.get(collector);
            CollectorBinding next = new CollectorBinding(keys, collectSite);
            if (!next.sameAs(previous)) {
                COLLECTOR_BINDINGS.put(collector, next);
                changed = true;
            }
        }

        if (changed) {
            for (String key : keys) {
                emit(key, "collect-start", null, collectSite, Collections.<String>emptySet());
            }
        }
    }

    /**
     * Called before FlowCollector.emit(). This is the generic cold-flow value hook. The emit call can
     * suspend; reporting before the call is intentional: the value has crossed the Flow collector
     * boundary at that point. Suspension/completion timing remains visible through subsequent values
     * and static operator semantics rather than being guessed.
     */
    public static void recordCollectorEmission(Object collector, Object value, String emitSite) {
        if (!enabled || collector == null) return;

        // MutableSharedFlow/MutableStateFlow implement FlowCollector themselves. Depending on Kotlin
        // bytecode lowering, `shared.emit(value)` can therefore appear as FlowCollector.emit rather
        // than MutableSharedFlow.emit. If this receiver is also a registered hot Flow, surface the
        // write request here; internal tryEmit/setValue instrumentation will confirm successful writes.
        List<String> directFlowKeys = keysFor(collector);
        if (!directFlowKeys.isEmpty() && looksLikeStateOrSharedFlow(collector)) {
            for (String key : directFlowKeys) {
                emit(key, "emit-request", value, siteWithKind(emitSite, "emit"), Collections.<String>emptySet());
            }
        }

        CollectorBinding binding;
        synchronized (LOCK) {
            binding = COLLECTOR_BINDINGS.get(collector);
        }
        if (binding == null || binding.keys.isEmpty()) return;

        for (String key : binding.keys) {
            LAST_VALUE_BY_KEY.put(key, normalize(value));
            emit(
                    key,
                    "deliver",
                    value,
                    joinSites(binding.collectSite, emitSite),
                    Collections.<String>emptySet()
            );
        }
    }

    /** Records the source call site before entering StateFlow implementation code. */
    public static void beforeWrite(Object flow, String kind, String site) {
        if (!enabled || flow == null || keysFor(flow).isEmpty()) return;
        // Capture the value immediately before the setter/CAS. This is the authoritative test for
        // whether a write attempt actually changed observable state. In particular,
        // rememberUpdatedState writes on every recomposition; same-value write attempts must not be
        // reported as state changes merely because the runtime baseline was stale or remapped.
        Object before = readValue(flow);
        PENDING_WRITES.get().push(new PendingWrite(
                flow,
                kind,
                site,
                before == NO_VALUE ? NO_VALUE : normalize(before)
        ));
    }

    /** Called after StateFlow mutation operations such as setValue/compareAndSet. */
    public static void afterWrite(Object flow, String kind, String site) {
        if (!enabled || flow == null) return;
        List<String> keys = keysFor(flow);
        if (keys.isEmpty()) return;

        PendingWrite pending = popPendingFor(flow);
        if (pending != null) {
            kind = pending.kind;
            site = pending.site;
        }

        Object current = readValue(flow);
        if (current == NO_VALUE) return;
        Object normalizedCurrent = normalize(current);
        final Object beforeWriteValue = pending == null ? NO_VALUE : pending.beforeValue;

        final ComposeStateRuntime composeRuntime;
        final Object previousComposeValue;
        synchronized (LOCK) {
            composeRuntime = composeStateRuntimeLocked(flow, false);
            if (composeRuntime != null) {
                previousComposeValue = composeRuntime.lastValue;
                composeRuntime.lastValue = normalizedCurrent;
            } else {
                previousComposeValue = NO_VALUE;
            }
        }
        if (composeRuntime != null) {
            // If we observed both sides of the concrete setter/CAS, only a real before -> after
            // value transition is a state change. This removes recomposition-frequency noise from
            // helpers such as rememberUpdatedState, which deliberately executes `state.value = x`
            // each recomposition even when x is unchanged.
            if (beforeWriteValue != NO_VALUE) {
                if (composeValuesEqual(beforeWriteValue, normalizedCurrent)) {
                    for (String key : keys) LAST_VALUE_BY_KEY.put(key, normalizedCurrent);
                    return;
                }
                // Nested instrumentation can see the same write at both the call site and inside a
                // Compose implementation. If an inner hook already committed this exact resulting
                // value, do not count the outer hook again.
                if (previousComposeValue != NO_VALUE && composeValuesEqual(previousComposeValue, normalizedCurrent)) {
                    for (String key : keys) LAST_VALUE_BY_KEY.put(key, normalizedCurrent);
                    return;
                }
            } else {
                // Fallback hooks that cannot capture the pre-write value use the per-instance
                // baseline. A missing baseline is an observation, not an invented mutation.
                if (previousComposeValue == NO_VALUE || composeValuesEqual(previousComposeValue, normalizedCurrent)) return;
            }

            for (String key : keys) {
                LAST_VALUE_BY_KEY.put(key, normalizedCurrent);
                emit(
                        key,
                        "state-change",
                        current,
                        siteWithKind(site, kind),
                        Collections.<String>emptySet(),
                        composeRuntime.instanceId
                );
            }
            return;
        }

        for (String key : keys) {
            Object previous = LAST_VALUE_BY_KEY.put(key, normalizedCurrent);
            if (beforeWriteValue != NO_VALUE && Objects.equals(beforeWriteValue, normalizedCurrent)) {
                continue;
            }
            if (!Objects.equals(previous, normalizedCurrent)) {
                emit(key, "emit", current, siteWithKind(site, kind), Collections.<String>emptySet());
            }
        }
    }

    /** For SharedFlow-style writes where there is no getValue() to inspect. */
    public static void recordEmission(Object flow, Object value, String kind, String site) {
        if (!enabled || flow == null) return;
        for (String key : keysFor(flow)) {
            LAST_VALUE_BY_KEY.put(key, normalize(value));
            emit(key, "emit", value, siteWithKind(site, kind), Collections.<String>emptySet());
        }
    }

    /**
     * Suspended MutableSharedFlow.emit(value) hook. A request is distinct from a confirmed synchronous
     * completion: emit can suspend until buffer/subscriber capacity becomes available. Collector
     * delivery events later show what actually propagated downstream.
     */
    public static void recordEmissionRequest(Object flow, Object value, String kind, String site) {
        if (!enabled || flow == null) return;
        for (String key : keysFor(flow)) {
            emit(key, "emit-request", value, siteWithKind(site, kind), Collections.<String>emptySet());
        }
    }

    public static void recordEmissionIf(boolean success, Object flow, Object value, String kind, String site) {
        if (success) recordEmission(flow, value, kind, site);
    }

    /**
     * Records one actual execution of a source @Composable body. The ASM transform suppresses
     * compiler-generated skipToGroupEnd() invocations before this method is called. Do not sample
     * here: the trace transport already coalesces events in 50 ms buckets while preserving the
     * occurrence count, so dropping fast executions would make the displayed count inaccurate.
     */
    public static void recordCompose(String runtimeKey, String site) {
        if (!enabled || runtimeKey == null || runtimeKey.length() == 0) return;
        LAST_COMPOSE_EXECUTION_BY_KEY.put(runtimeKey, System.nanoTime());
        emit(runtimeKey, "compose", null, site, Collections.<String>emptySet());
    }

    /**
     * Register the live Compose slot table observed at a source @Composable entry.
     *
     * The registry is intentionally composition-centric rather than composable-centric. One
     * CompositionData contains the complete source/tooling tree for many composable calls, so a
     * later Render UI request searches all registered trees instead of depending on the exact
     * function that happened to register a weak reference.
     */
    public static void recordComposeInspection(String runtimeKey, Object composer, String site) {
        recordComposeInspection(runtimeKey, composer, site, Integer.MIN_VALUE);
    }

    /**
     * v0.17.30 overload: the instrumentation calls this immediately after the compiler starts the
     * source composable's outer restart/replace group. The integer key is therefore the exact key
     * stored in CompositionGroup.key and does not depend on optional source-information strings.
     */
    public static void recordComposeInspection(
            String runtimeKey,
            Object composer,
            String site,
            int composeGroupKey
    ) {
        if (!enabled || runtimeKey == null || runtimeKey.length() == 0 || composer == null) return;

        final String registrationId = runtimeKey + "#" + composeGroupKey;
        final long now = System.currentTimeMillis();
        synchronized (COMPOSE_LOCK) {
            drainComposerInspectionQueueLocked();
            ComposerInspectionRuntime seen =
                    COMPOSE_INSPECTION_SEEN.get(new IdentityWeakReference(composer));
            if (seen != null && seen.registrations.contains(registrationId)) {
                // Keep composition recency accurate without repeating reflection / tree registration.
                ActiveComposition active = ACTIVE_COMPOSITIONS.get(seen.compositionData);
                if (active != null) active.lastSeenMillis = now;
                return;
            }
        }

        if (composeGroupKey != Integer.MIN_VALUE) {
            Set<Integer> keys = COMPOSE_GROUP_KEYS_BY_KEY.get(runtimeKey);
            if (keys == null) {
                Set<Integer> created =
                        Collections.newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());
                Set<Integer> raced = COMPOSE_GROUP_KEYS_BY_KEY.putIfAbsent(runtimeKey, created);
                keys = raced == null ? created : raced;
            }
            keys.add(Integer.valueOf(composeGroupKey));
        }

        Object compositionData = compositionDataFromComposer(composer);
        synchronized (COMPOSE_LOCK) {
            composeInspectionAttempts++;
            if (compositionData == null) {
                composeInspectionMissingData++;
                lastComposeInspectionFailure =
                        "Composer " + composer.getClass().getName() + " did not expose CompositionData";
                return;
            }

            composeInspectionSuccesses++;

            IdentityWeakReference composerLookup = new IdentityWeakReference(composer);
            ComposerInspectionRuntime seen = COMPOSE_INSPECTION_SEEN.get(composerLookup);
            if (seen == null) {
                seen = new ComposerInspectionRuntime(compositionData);
                COMPOSE_INSPECTION_SEEN.put(
                        new IdentityWeakReference(composer, COMPOSER_REFERENCE_QUEUE),
                        seen
                );
            }
            seen.registrations.add(registrationId);

            ActiveComposition active = ACTIVE_COMPOSITIONS.get(compositionData);
            if (active == null) {
                active = new ActiveComposition(compositionData, now);
                ACTIVE_COMPOSITIONS.put(compositionData, active);
            }
            active.lastSeenMillis = now;
            active.runtimeKeys.add(runtimeKey);
            if (site != null && site.length() > 0) active.sites.add(site);
            trimCompositionRegistryLocked();
        }
        if (site != null) COMPOSE_SITE_BY_KEY.put(runtimeKey, site);
    }

    /** Ask the running app for source composables that are visibly placed on the current UI. */
    private static void requestCurrentCompose() {
        if (!enabled) return;
        postToAndroidMainThread(new Runnable() {
            @Override public void run() {
                captureCurrentComposeCandidates();
            }
        }, COMPOSE_CAPTURE_DELAY_MS);
    }

    private static void captureCurrentComposeCandidates() {
        try {
            List<ActiveComposition> compositions = snapshotActiveCompositions();
            if (compositions.isEmpty()) {
                emitSummary(
                        "@compose-current",
                        "compose-current-error",
                        "No active Compose slot tables have been registered by the running app.",
                        "live-current-ui",
                        Collections.<String>emptySet()
                );
                return;
            }

            LinkedHashMap<String, CurrentComposeCandidate> bestByKey = new LinkedHashMap<>();
            for (ActiveComposition active : compositions) {
                Object rootsValue = invokeNoArgNamed(active.data, "getCompositionGroups");
                if (!(rootsValue instanceof Iterable)) continue;

                for (String runtimeKey : active.runtimeKeys) {
                    if (runtimeKey == null || !runtimeKey.startsWith("@compose|")) continue;
                    ComposeTarget target = ComposeTarget.fromRuntimeKey(runtimeKey);
                    if (target == null) continue;

                    Set<Integer> groupKeys = snapshotComposeGroupKeys(runtimeKey);
                    List<ComposeGroupMatch> matches = new ArrayList<>();
                    DiagnosticStats stats = new DiagnosticStats();
                    IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
                    for (Object root : (Iterable<?>) rootsValue) {
                        findComposeGroups(root, target, groupKeys, null, matches, visited, stats);
                    }
                    long visibleArea = 0L;
                    for (ComposeGroupMatch match : matches) {
                        if (match == null || match.bounds == null || match.ownerView == null) continue;
                        if (!match.bounds.isPositive()) continue;
                        visibleArea = Math.max(visibleArea, match.bounds.area());
                    }
                    if (visibleArea <= 0L) continue;

                    Long composeNanosBoxed = LAST_COMPOSE_EXECUTION_BY_KEY.get(runtimeKey);
                    long composeNanos = composeNanosBoxed == null ? 0L : composeNanosBoxed.longValue();
                    CurrentComposeCandidate candidate = new CurrentComposeCandidate(
                            runtimeKey,
                            visibleArea,
                            active.lastSeenMillis,
                            composeNanos
                    );
                    CurrentComposeCandidate previous = bestByKey.get(runtimeKey);
                    if (previous == null || candidate.betterThan(previous)) {
                        bestByKey.put(runtimeKey, candidate);
                    }
                }
            }

            List<CurrentComposeCandidate> candidates = new ArrayList<>(bestByKey.values());
            Collections.sort(candidates, new java.util.Comparator<CurrentComposeCandidate>() {
                @Override public int compare(CurrentComposeCandidate a, CurrentComposeCandidate b) {
                    int area = Long.compare(b.visibleArea, a.visibleArea);
                    if (area != 0) return area;
                    int compose = Long.compare(b.lastComposeNanos, a.lastComposeNanos);
                    if (compose != 0) return compose;
                    return Long.compare(b.compositionSeenMillis, a.compositionSeenMillis);
                }
            });

            if (candidates.isEmpty()) {
                emitSummary(
                        "@compose-current",
                        "compose-current-error",
                        "Compose is active, but no instrumented source composable currently exposes attached visible LayoutInfo.",
                        "live-current-ui",
                        Collections.<String>emptySet()
                );
                return;
            }

            StringBuilder payload = new StringBuilder();
            int limit = Math.min(candidates.size(), 32);
            for (int i = 0; i < limit; i++) {
                CurrentComposeCandidate candidate = candidates.get(i);
                if (payload.length() > 0) payload.append('\n');
                payload.append(candidate.runtimeKey)
                        .append('\t')
                        .append(candidate.visibleArea);
            }
            emitSummary(
                    "@compose-current",
                    "compose-current",
                    payload.toString(),
                    "live-current-ui • visible candidates=" + limit,
                    Collections.<String>emptySet()
            );
        } catch (Throwable error) {
            emitSummary(
                    "@compose-current",
                    "compose-current-error",
                    "Current UI inspection threw " + error.getClass().getSimpleName()
                            + (error.getMessage() == null ? "" : ": " + error.getMessage()),
                    "live-current-ui",
                    Collections.<String>emptySet()
            );
        }
    }

    /** Invoked by the duplex trace socket after the IDE asks for one live composable image. */
    private static void requestComposeImage(final String runtimeKey) {
        if (!enabled || runtimeKey == null || runtimeKey.length() == 0) return;

        // Resolve the registry at execution time on the Android main thread. This is important:
        // another composition may have appeared between the IDE request and the next frame.
        postToAndroidMainThread(new Runnable() {
            @Override public void run() {
                captureComposeImage(runtimeKey, 0);
            }
        }, COMPOSE_CAPTURE_DELAY_MS);
    }

    private static void captureComposeImage(final String runtimeKey, final int retryAttempt) {
        DiagnosticStats stats = new DiagnosticStats();
        stats.geometryRetryAttempt = retryAttempt;
        try {
            ComposeTarget target = ComposeTarget.fromRuntimeKey(runtimeKey);
            if (target == null) {
                emitComposeImageError(runtimeKey, "Invalid Compose runtime key.");
                return;
            }

            stats.target = target;
            Set<Integer> targetGroupKeys = snapshotComposeGroupKeys(runtimeKey);
            stats.targetGroupKeys.addAll(targetGroupKeys);
            List<ActiveComposition> compositions = snapshotActiveCompositions();
            stats.activeCompositions = compositions.size();
            synchronized (COMPOSE_LOCK) {
                stats.inspectionAttempts = composeInspectionAttempts;
                stats.inspectionSuccesses = composeInspectionSuccesses;
                stats.inspectionMissingData = composeInspectionMissingData;
                stats.lastInspectionFailure = lastComposeInspectionFailure;
            }

            if (compositions.isEmpty()) {
                emitComposeImageError(runtimeKey, stats.failure(
                        "No CompositionData has been registered by the running app. " +
                        "This usually means the v0.17.30 instrumentation is not active or Composer.compositionData could not be reflected."
                ));
                return;
            }

            List<ComposeGroupMatch> matches = new ArrayList<>();
            for (ActiveComposition active : compositions) {
                Object rootsValue = invokeNoArgNamed(active.data, "getCompositionGroups");
                if (!(rootsValue instanceof Iterable)) {
                    stats.compositionsWithoutRoots++;
                    continue;
                }
                stats.compositionsWithRoots++;
                IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
                for (Object root : (Iterable<?>) rootsValue) {
                    findComposeGroups(root, target, targetGroupKeys, null, matches, visited, stats);
                }
            }

            if (matches.isEmpty()) {
                if (stats.exactSourceMatches > 0 && retryAttempt < COMPOSE_GEOMETRY_RETRIES) {
                    postToAndroidMainThread(new Runnable() {
                        @Override public void run() {
                            captureComposeImage(runtimeKey, retryAttempt + 1);
                        }
                    }, COMPOSE_CAPTURE_DELAY_MS);
                    return;
                }
                String reason;
                if (stats.exactSourceMatches == 0 && stats.targetGroupKeys.isEmpty()) {
                    reason = "No compiler group key was captured for this composable. Rebuild the debug app with the v0.17.30 instrumentation.";
                } else if (stats.exactSourceMatches == 0) {
                    reason = "The compiler group key was captured, but no live CompositionGroup currently has that key.";
                } else if (stats.subCompositionRootsFound > 0 && stats.subCompositionOwnerViewsFound == 0) {
                    reason = "The composable owns a Dialog/Popup sub-composition, but its live owner view is not attached yet.";
                } else {
                    reason = "The composable group was found, but neither descendant LayoutInfo nor a Dialog/Popup sub-composition exposed usable live geometry.";
                }
                emitComposeImageError(runtimeKey, stats.failure(reason));
                return;
            }

            // A source @Composable can have many simultaneous live instances. This is common for
            // LazyColumn/LazyRow/LazyGrid/LazyStaggeredGrid item content: every visible item has
            // the same compiler group key because they all originate from the same call site.
            // Older builds selected only the largest individual match, which made repeated item
            // composables look as if only one item existed. Group matches by owner view and union
            // every currently placed instance in that view. Off-screen lazy items are intentionally
            // absent because Compose has not composed them yet.
            IdentityHashMap<Object, ComposeMatchAggregate> byOwner = new IdentityHashMap<>();
            for (ComposeGroupMatch match : matches) {
                if (match.bounds == null || match.ownerView == null || !match.bounds.isPositive()) continue;
                ComposeMatchAggregate aggregate = byOwner.get(match.ownerView);
                if (aggregate == null) {
                    aggregate = new ComposeMatchAggregate(match.ownerView);
                    byOwner.put(match.ownerView, aggregate);
                }
                aggregate.add(match);
            }

            ComposeMatchAggregate bestAggregate = null;
            for (ComposeMatchAggregate aggregate : byOwner.values()) {
                if (aggregate.bounds == null || !aggregate.bounds.isPositive()) continue;
                if (bestAggregate == null
                        || aggregate.instanceCount > bestAggregate.instanceCount
                        || (aggregate.instanceCount == bestAggregate.instanceCount
                            && aggregate.bounds.area() > bestAggregate.bounds.area())) {
                    bestAggregate = aggregate;
                }
            }
            if (bestAggregate == null) {
                emitComposeImageError(runtimeKey, stats.failure(
                        "The composable exists in the live composition tree but has no attached/placed LayoutInfo bounds yet."
                ));
                return;
            }

            Bounds selectedBounds = bestAggregate.bounds;
            int visibleInstances = bestAggregate.instanceCount;
            String selectedOrigin = bestAggregate.instanceCount > 1 ? "multi-instance" : bestAggregate.origin;

            // Some lazy/subcomposed containers expose only one exact compiler-key group even
            // while several sibling item LayoutInfos are visibly placed. In that case the exact
            // group identity is not enough to enumerate every live instance. As a conservative
            // fallback, inspect peer-sized LayoutInfo rectangles on the same AndroidComposeView
            // and grow the capture only across an adjacent repeated-item cluster.
            if (visibleInstances <= 1) {
                RepeatedRegionExpansion repeated = inferRepeatedLayoutRegion(
                        bestAggregate.ownerView,
                        selectedBounds,
                        compositions
                );
                if (repeated != null && repeated.instanceCount > 1 && repeated.bounds != null
                        && repeated.bounds.isPositive()) {
                    selectedBounds = repeated.bounds;
                    visibleInstances = repeated.instanceCount;
                    selectedOrigin = "inferred-repeated-layout";
                    stats.inferredPeerInstances = repeated.instanceCount;
                }
            }

            ComposeGroupMatch best = new ComposeGroupMatch(
                    bestAggregate.ownerView,
                    selectedBounds,
                    selectedOrigin
            );
            stats.visibleInstances = visibleInstances;
            stats.ownerGroupsWithMatches = byOwner.size();
            stats.selectedWidth = best.bounds.width();
            stats.selectedHeight = best.bounds.height();
            byte[] png = drawViewRegion(best.ownerView, best.bounds);
            stats.drawAttempts++;
            if (png != null && png.length > 0) {
                stats.captureMethod = "AndroidComposeView.draw";
            } else {
                // A number of Compose nodes (graphicsLayer, RenderNode-backed content, some
                // lazy/grid cells, dialogs, etc.) cannot replay their hardware-backed pixels into
                // a software Bitmap Canvas. The group/bounds are still correct in that case, so
                // copy the already-rendered pixels from the owning Window surface instead.
                stats.pixelCopyAttempts++;
                png = pixelCopyViewRegion(best.ownerView, best.bounds, stats);
                if (png != null && png.length > 0) {
                    stats.captureMethod = "PixelCopy";
                }
            }
            if (png == null || png.length == 0) {
                if (retryAttempt < COMPOSE_GEOMETRY_RETRIES) {
                    postToAndroidMainThread(new Runnable() {
                        @Override public void run() {
                            captureComposeImage(runtimeKey, retryAttempt + 1);
                        }
                    }, COMPOSE_CAPTURE_DELAY_MS);
                    return;
                }
                emitComposeImageError(runtimeKey, stats.failure(
                        "The source group and LayoutInfo bounds were found, but neither software View.draw() nor Window PixelCopy produced an image after retries."
                ));
                return;
            }
            if (png.length > MAX_COMPOSE_IMAGE_BYTES) {
                emitComposeImageError(
                        runtimeKey,
                        stats.failure("Rendered composable image is " + png.length +
                                " bytes; limit is " + MAX_COMPOSE_IMAGE_BYTES + ".")
                );
                return;
            }

            CRC32 crc = new CRC32();
            crc.update(png);
            long hash = crc.getValue();
            LAST_COMPOSE_IMAGE_HASH_BY_KEY.put(runtimeKey, hash);

            String payload = "FGIMG3|"
                    + best.bounds.width() + "|"
                    + best.bounds.height() + "|"
                    + encBytes(png) + "|"
                    + best.bounds.left + "," + best.bounds.top + ","
                    + best.bounds.right + "," + best.bounds.bottom + "|"
                    + stats.exactSourceMatches + "|"
                    + stats.activeCompositions + "|"
                    + stats.groupsScanned + "|"
                    + stats.visibleInstances;
            String site = COMPOSE_SITE_BY_KEY.get(runtimeKey);
            emitSummary(
                    runtimeKey,
                    "compose-image",
                    payload,
                    (site == null ? "live-composition-registry" : site)
                            + " • active=" + stats.activeCompositions
                            + " • groups=" + stats.groupsScanned
                            + " • matches=" + stats.exactSourceMatches
                            + " • geometry=" + best.origin
                            + " • capture=" + (stats.captureMethod == null ? "unknown" : stats.captureMethod),
                    Collections.<String>emptySet()
            );
        } catch (Throwable error) {
            emitComposeImageError(
                    runtimeKey,
                    stats.failure(
                            "Live Compose inspection threw " + error.getClass().getSimpleName()
                                    + (error.getMessage() == null ? "" : ": " + error.getMessage())
                    )
            );
        }
    }

    private static Object compositionDataFromComposer(Object composer) {
        Object data = invokeNoArgNamed(composer, "getCompositionData");
        if (data != null) return data;

        // Compose runtime internals occasionally gain JVM-name suffixes. Accept a zero-arg method
        // whose name begins with getCompositionData rather than tying the debug helper to one ABI.
        Class<?> type = composer.getClass();
        while (type != null) {
            try {
                for (Method method : type.getDeclaredMethods()) {
                    if (method.getParameterTypes().length == 0
                            && method.getName().startsWith("getCompositionData")) {
                        try {
                            method.setAccessible(true);
                            Object value = method.invoke(composer);
                            if (value != null) return value;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }

        // Last compatibility fallback for runtimes where the tooling value is stored as a field.
        type = composer.getClass();
        while (type != null) {
            try {
                for (Field field : type.getDeclaredFields()) {
                    if (field.getName().toLowerCase(java.util.Locale.ROOT).contains("compositiondata")) {
                        try {
                            field.setAccessible(true);
                            Object value = field.get(composer);
                            if (value != null) return value;
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }
        return null;
    }

    private static List<ActiveComposition> snapshotActiveCompositions() {
        synchronized (COMPOSE_LOCK) {
            return new ArrayList<>(ACTIVE_COMPOSITIONS.values());
        }
    }

    private static Set<Integer> snapshotComposeGroupKeys(String runtimeKey) {
        Set<Integer> keys = COMPOSE_GROUP_KEYS_BY_KEY.get(runtimeKey);
        if (keys == null || keys.isEmpty()) return Collections.emptySet();
        return new LinkedHashSet<>(keys);
    }

    private static void drainComposerInspectionQueueLocked() {
        IdentityWeakReference ref;
        while ((ref = (IdentityWeakReference) COMPOSER_REFERENCE_QUEUE.poll()) != null) {
            COMPOSE_INSPECTION_SEEN.remove(ref);
        }
    }

    private static void trimCompositionRegistryLocked() {
        while (ACTIVE_COMPOSITIONS.size() > MAX_ACTIVE_COMPOSITIONS) {
            Object oldestKey = null;
            long oldestSeen = Long.MAX_VALUE;
            for (Map.Entry<Object, ActiveComposition> entry : ACTIVE_COMPOSITIONS.entrySet()) {
                if (entry.getValue().lastSeenMillis < oldestSeen) {
                    oldestSeen = entry.getValue().lastSeenMillis;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) break;
            ACTIVE_COMPOSITIONS.remove(oldestKey);
        }
    }

    private static void emitComposeImageError(String runtimeKey, String message) {
        emitSummary(runtimeKey, "compose-image-error", message, "live-slot-table", Collections.<String>emptySet());
    }

    private static void findComposeGroups(
            Object group,
            ComposeTarget target,
            Set<Integer> targetGroupKeys,
            String inheritedFile,
            List<ComposeGroupMatch> matches,
            IdentityHashMap<Object, Boolean> visited,
            DiagnosticStats stats
    ) {
        if (group == null || visited.put(group, Boolean.TRUE) != null) return;
        stats.groupsScanned++;

        String raw = stringValue(invokeNoArgNamed(group, "getSourceInfo"));
        ParsedSourceInfo parsed = parseSourceInfo(raw, inheritedFile);
        String effectiveFile = parsed.sourceFile == null ? inheritedFile : parsed.sourceFile;
        if (raw != null && raw.length() > 0) stats.groupsWithSourceInfo++;

        if (target.functionName.equals(parsed.functionName)) {
            stats.functionNameMatches++;
            stats.noteFunctionFile(effectiveFile);
        }

        Object rawGroupKey = invokeNoArgNamed(group, "getKey");
        boolean keyMatch = false;
        if (rawGroupKey instanceof Number && targetGroupKeys != null && !targetGroupKeys.isEmpty()) {
            keyMatch = targetGroupKeys.contains(Integer.valueOf(((Number) rawGroupKey).intValue()));
            if (keyMatch) stats.groupKeyMatches++;
        }

        boolean sourceMatch = target.matches(parsed.functionName, effectiveFile);
        // Group-key matching is the primary v0.17.30 path. sourceInfo is optional tooling data and
        // is frequently absent in modern non-inspection builds. If both are available, either can
        // locate the source group; a key match remains valid even when sourceInfo is null.
        if (keyMatch || sourceMatch) {
            stats.exactSourceMatches++;
            ComposeGroupMatch match = boundsForGroup(group, stats);
            if (match != null) {
                stats.matchesWithBounds++;
                matches.add(match);
            }
        }

        Object children = invokeNoArgNamed(group, "getCompositionGroups");
        if (children instanceof Iterable) {
            for (Object child : (Iterable<?>) children) {
                findComposeGroups(child, target, targetGroupKeys, effectiveFile, matches, visited, stats);
            }
        }
    }

    /**
     * Mirrors Compose tooling in two stages:
     *
     *  1. Normal composables get the union of descendant LayoutInfo bounds.
     *  2. Dialog/Popup/other ViewRootForInspector composables intentionally have an empty parent
     *     slot-table box. Android Studio's Layout Inspector stitches those sub-compositions by
     *     finding ViewRootForInspector in CompositionGroup.data. We do the equivalent here and
     *     render the actual sub-composition owner view.
     */
    private static ComposeGroupMatch boundsForGroup(Object group, DiagnosticStats stats) {
        IdentityHashMap<Object, Bounds> byOwner = new IdentityHashMap<>();
        collectLayoutBounds(group, byOwner, new IdentityHashMap<Object, Boolean>(), stats);
        Object bestOwner = null;
        Bounds bestBounds = null;
        for (Map.Entry<Object, Bounds> entry : byOwner.entrySet()) {
            Bounds bounds = entry.getValue();
            if (bounds == null || !bounds.isPositive()) continue;
            if (bestBounds == null || bounds.area() > bestBounds.area()) {
                bestOwner = entry.getKey();
                bestBounds = bounds;
            }
        }
        if (bestOwner != null) {
            stats.directLayoutMatches++;
            return new ComposeGroupMatch(bestOwner, bestBounds, "layout-info");
        }

        ComposeGroupMatch sub = subCompositionMatch(group, stats);
        if (sub != null) {
            stats.subCompositionMatches++;
            return sub;
        }
        return null;
    }

    private static void collectLayoutBounds(
            Object group,
            IdentityHashMap<Object, Bounds> byOwner,
            IdentityHashMap<Object, Boolean> visited,
            DiagnosticStats stats
    ) {
        if (group == null || visited.put(group, Boolean.TRUE) != null) return;
        Object node = invokeNoArgNamed(group, "getNode");
        if (node != null) stats.groupNodesSeen++;
        LayoutBounds layout = layoutBounds(node, stats);
        if (layout != null && layout.owner != null && layout.bounds != null && layout.bounds.isPositive()) {
            Bounds current = byOwner.get(layout.owner);
            byOwner.put(layout.owner, current == null ? layout.bounds : current.union(layout.bounds));
        }
        Object children = invokeNoArgNamed(group, "getCompositionGroups");
        if (children instanceof Iterable) {
            for (Object child : (Iterable<?>) children) {
                collectLayoutBounds(child, byOwner, visited, stats);
            }
        }
    }

    private static LayoutBounds layoutBounds(Object node, DiagnosticStats stats) {
        if (node == null || !implementsNamedInterface(node.getClass(), "androidx.compose.ui.layout.LayoutInfo")) {
            return null;
        }
        stats.layoutInfoNodesSeen++;
        Object attached = invokeNoArgNamed(node, "isAttached");
        if (attached instanceof Boolean && !((Boolean) attached).booleanValue()) {
            stats.detachedLayoutInfoNodes++;
            return null;
        }
        stats.attachedLayoutInfoNodes++;

        Object coordinates = invokeNoArgNamed(node, "getCoordinates");
        Number width = numberValue(invokeNoArgNamed(node, "getWidth"));
        Number height = numberValue(invokeNoArgNamed(node, "getHeight"));
        if (coordinates == null || width == null || height == null) {
            stats.layoutInfoMissingCoordinatesOrSize++;
            return null;
        }
        stats.layoutCoordinatesReadable++;

        Long packedValue = positionInWindow(coordinates);
        if (packedValue == null) {
            stats.layoutPositionFailures++;
            return null;
        }
        stats.layoutPositionsReadable++;
        long packed = packedValue.longValue();
        float x = Float.intBitsToFloat((int) (packed >>> 32));
        float y = Float.intBitsToFloat((int) packed);
        if (Float.isNaN(x) || Float.isNaN(y) || Float.isInfinite(x) || Float.isInfinite(y)) {
            stats.layoutPositionFailures++;
            return null;
        }

        int left = Math.round(x);
        int top = Math.round(y);
        int right = left + width.intValue();
        int bottom = top + height.intValue();
        Object owner = layoutOwner(node);
        if (owner == null) {
            stats.layoutOwnerFailures++;
            return null;
        }
        stats.layoutOwnersReadable++;
        return new LayoutBounds(owner, new Bounds(left, top, right, bottom));
    }

    /**
     * positionInWindow has changed bytecode presentation across Compose/Kotlin releases. Prefer the
     * public static helper, but also accept mangled JVM names and finally call localToWindow(Offset.Zero)
     * directly on LayoutCoordinates.
     */
    private static Long positionInWindow(Object coordinates) {
        try {
            Class<?> kt = Class.forName("androidx.compose.ui.layout.LayoutCoordinatesKt");
            for (Method method : kt.getMethods()) {
                if (method.getName().startsWith("positionInWindow")
                        && method.getParameterTypes().length == 1
                        && method.getParameterTypes()[0].isAssignableFrom(coordinates.getClass())) {
                    try {
                        Object result = method.invoke(null, coordinates);
                        if (result instanceof Number) return Long.valueOf(((Number) result).longValue());
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}

        Class<?> type = coordinates.getClass();
        while (type != null) {
            try {
                for (Method method : type.getDeclaredMethods()) {
                    if (!method.getName().startsWith("localToWindow") || method.getParameterTypes().length != 1) {
                        continue;
                    }
                    Class<?> parameter = method.getParameterTypes()[0];
                    if (parameter == long.class || parameter == Long.class) {
                        try {
                            method.setAccessible(true);
                            Object result = method.invoke(coordinates, Long.valueOf(0L)); // Offset.Zero packed
                            if (result instanceof Number) return Long.valueOf(((Number) result).longValue());
                        } catch (Throwable ignored) {}
                    }
                }
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }
        return null;
    }

    private static RepeatedRegionExpansion inferRepeatedLayoutRegion(
            Object ownerView,
            Bounds target,
            List<ActiveComposition> compositions
    ) {
        if (ownerView == null || target == null || !target.isPositive() || compositions == null || compositions.isEmpty()) {
            return null;
        }

        Bounds ownerBounds = androidViewBoundsInWindow(ownerView);
        if (ownerBounds == null || !ownerBounds.isPositive()) return null;
        if (target.area() * 100L >= ownerBounds.area() * 62L) return null;

        final LinkedHashMap<String, Bounds> unique = new LinkedHashMap<>();
        final DiagnosticStats scratch = new DiagnosticStats();
        for (ActiveComposition active : compositions) {
            Object rootsValue = invokeNoArgNamed(active.data, "getCompositionGroups");
            if (!(rootsValue instanceof Iterable)) continue;
            IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
            for (Object root : (Iterable<?>) rootsValue) {
                collectIndividualLayoutBounds(root, ownerView, unique, visited, scratch);
            }
        }
        if (unique.isEmpty()) return null;

        final int targetW = target.width();
        final int targetH = target.height();
        final long targetArea = Math.max(1L, target.area());
        List<Bounds> compatible = new ArrayList<>();
        for (Bounds candidate : unique.values()) {
            if (candidate == null || !candidate.isPositive()) continue;
            if (sameBounds(candidate, target)) continue;
            if (!intersects(candidate, ownerBounds)) continue;

            long area = candidate.area();
            if (area < targetArea / 5L || area > targetArea * 5L) continue;

            double widthRatio = targetW == 0 ? 0.0 : (double) candidate.width() / (double) targetW;
            double heightRatio = targetH == 0 ? 0.0 : (double) candidate.height() / (double) targetH;
            boolean sameColumnCellWidth = widthRatio >= 0.58 && widthRatio <= 1.42 && heightRatio >= 0.28 && heightRatio <= 3.4;
            boolean sameRowCellHeight = heightRatio >= 0.58 && heightRatio <= 1.42 && widthRatio >= 0.28 && widthRatio <= 3.4;
            if (!sameColumnCellWidth && !sameRowCellHeight) continue;

            // Reject parent wrappers around the target and children nested inside it. The fallback
            // is looking for sibling cells, not another level in the target's own layout tree.
            if (contains(candidate, target) || contains(target, candidate)) continue;
            if (overlapArea(candidate, target) * 100L > Math.min(area, targetArea) * 45L) continue;
            compatible.add(candidate);
        }
        if (compatible.isEmpty()) return null;

        // Prefer the largest rectangle at each location. Compose often exposes several nested
        // LayoutInfo nodes for one visual cell; keeping all of them would falsely inflate the
        // instance count and could pull the inferred region into unrelated descendants.
        List<Bounds> maximal = new ArrayList<>();
        for (Bounds candidate : compatible) {
            boolean nested = false;
            for (Bounds other : compatible) {
                if (candidate == other) continue;
                if (contains(other, candidate) && other.area() > candidate.area() * 12L / 10L) {
                    nested = true;
                    break;
                }
            }
            if (!nested) maximal.add(candidate);
        }
        if (maximal.isEmpty()) maximal = compatible;

        List<Bounds> selected = new ArrayList<>();
        selected.add(target);
        Bounds union = target;
        final int maxGap = Math.max(30, Math.min(targetW, targetH) / 2 + 18);
        boolean changed;
        do {
            changed = false;
            for (Bounds candidate : maximal) {
                if (selected.contains(candidate)) continue;
                boolean near = false;
                for (Bounds already : selected) {
                    if (rectGap(already, candidate) <= maxGap) {
                        near = true;
                        break;
                    }
                }
                if (!near) continue;
                Bounds nextUnion = union.union(candidate);
                if (nextUnion.area() > ownerBounds.area()) continue;
                selected.add(candidate);
                union = nextUnion;
                changed = true;
            }
        } while (changed);

        if (selected.size() < 2 || union.area() * 100L < targetArea * 125L) return null;
        Bounds clipped = intersection(union, ownerBounds);
        if (clipped == null || !clipped.isPositive()) return null;
        return new RepeatedRegionExpansion(clipped, selected.size());
    }

    private static void collectIndividualLayoutBounds(
            Object group,
            Object wantedOwner,
            LinkedHashMap<String, Bounds> out,
            IdentityHashMap<Object, Boolean> visited,
            DiagnosticStats scratch
    ) {
        if (group == null || visited.put(group, Boolean.TRUE) != null) return;
        Object node = invokeNoArgNamed(group, "getNode");
        LayoutBounds layout = layoutBounds(node, scratch);
        if (layout != null && layout.owner == wantedOwner && layout.bounds != null && layout.bounds.isPositive()) {
            Bounds b = layout.bounds;
            out.put(b.left + ":" + b.top + ":" + b.right + ":" + b.bottom, b);
        }
        Object children = invokeNoArgNamed(group, "getCompositionGroups");
        if (children instanceof Iterable) {
            for (Object child : (Iterable<?>) children) {
                collectIndividualLayoutBounds(child, wantedOwner, out, visited, scratch);
            }
        }
    }

    private static boolean sameBounds(Bounds a, Bounds b) {
        return a != null && b != null && a.left == b.left && a.top == b.top && a.right == b.right && a.bottom == b.bottom;
    }

    private static boolean contains(Bounds outer, Bounds inner) {
        return outer != null && inner != null
                && outer.left <= inner.left && outer.top <= inner.top
                && outer.right >= inner.right && outer.bottom >= inner.bottom;
    }

    private static boolean intersects(Bounds a, Bounds b) {
        return a != null && b != null && a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom;
    }

    private static long overlapArea(Bounds a, Bounds b) {
        if (!intersects(a, b)) return 0L;
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        return (long) Math.max(0, right - left) * (long) Math.max(0, bottom - top);
    }

    private static int rectGap(Bounds a, Bounds b) {
        int dx = Math.max(0, Math.max(a.left, b.left) - Math.min(a.right, b.right));
        int dy = Math.max(0, Math.max(a.top, b.top) - Math.min(a.bottom, b.bottom));
        return Math.max(dx, dy);
    }

    private static Bounds intersection(Bounds a, Bounds b) {
        int left = Math.max(a.left, b.left);
        int top = Math.max(a.top, b.top);
        int right = Math.min(a.right, b.right);
        int bottom = Math.min(a.bottom, b.bottom);
        if (right <= left || bottom <= top) return null;
        return new Bounds(left, top, right, bottom);
    }

    /**
     * Compose Dialog, Popup and several other constructs create a second AndroidComposeView. Their
     * source call group in the parent composition intentionally has no LayoutInfo. Layout Inspector
     * discovers the bridge through ViewRootForInspector stored in CompositionGroup.data.
     */
    private static ComposeGroupMatch subCompositionMatch(Object group, DiagnosticStats stats) {
        IdentityHashMap<Object, Boolean> visitedGroups = new IdentityHashMap<>();
        java.util.ArrayDeque<Object> stack = new java.util.ArrayDeque<>();
        stack.push(group);
        while (!stack.isEmpty()) {
            Object current = stack.pop();
            if (current == null || visitedGroups.put(current, Boolean.TRUE) != null) continue;

            Object data = invokeNoArgNamed(current, "getData");
            if (data instanceof Iterable) {
                for (Object item : (Iterable<?>) data) {
                    Object root = unwrapViewRootForInspector(item);
                    if (root == null) continue;
                    stats.subCompositionRootsFound++;
                    Object subView = invokeNoArgNamed(root, "getSubCompositionView");
                    if (subView == null) continue;
                    Object ownerView = findAndroidComposeView(subView);
                    if (ownerView == null) ownerView = subView;
                    Bounds bounds = androidViewBoundsInWindow(ownerView);
                    if (bounds != null && bounds.isPositive()) {
                        stats.subCompositionOwnerViewsFound++;
                        return new ComposeGroupMatch(ownerView, bounds, "sub-composition");
                    }
                }
            }

            Object children = invokeNoArgNamed(current, "getCompositionGroups");
            if (children instanceof Iterable) {
                for (Object child : (Iterable<?>) children) {
                    if (child != null) stack.push(child);
                }
            }
        }
        return null;
    }

    private static Object unwrapViewRootForInspector(Object value) {
        if (value == null) return null;
        if (implementsNamedInterface(value.getClass(), "androidx.compose.ui.platform.ViewRootForInspector")) {
            return value;
        }

        // Older inspector code stores Ref<ViewRootForInspector> in the slot table. Avoid depending
        // on the concrete Ref package by unwrapping a value-like zero-arg getter/field reflectively.
        String name = value.getClass().getName();
        if (name.endsWith(".Ref") || name.contains("$Ref")) {
            Object unwrapped = invokeNoArgNamed(value, "getValue");
            if (unwrapped == null) {
                Class<?> type = value.getClass();
                while (type != null && unwrapped == null) {
                    try {
                        Field field = type.getDeclaredField("value");
                        field.setAccessible(true);
                        unwrapped = field.get(value);
                    } catch (Throwable ignored) {}
                    type = type.getSuperclass();
                }
            }
            if (unwrapped != null
                    && implementsNamedInterface(unwrapped.getClass(), "androidx.compose.ui.platform.ViewRootForInspector")) {
                return unwrapped;
            }
        }
        return null;
    }

    private static Object findAndroidComposeView(Object rootView) {
        if (rootView == null) return null;
        java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        queue.add(rootView);
        Object firstView = null;
        while (!queue.isEmpty()) {
            Object view = queue.removeFirst();
            if (view == null || visited.put(view, Boolean.TRUE) != null) continue;
            if (firstView == null) firstView = view;
            String className = view.getClass().getName();
            if (className.endsWith("AndroidComposeView") || className.contains(".AndroidComposeView")) {
                return view;
            }
            Number childCount = numberValue(invokeNoArgNamed(view, "getChildCount"));
            if (childCount == null) continue;
            Method childAt = findMethod(view.getClass(), "getChildAt", int.class);
            if (childAt == null) continue;
            try { childAt.setAccessible(true); } catch (Throwable ignored) {}
            for (int i = 0; i < childCount.intValue(); i++) {
                try {
                    Object child = childAt.invoke(view, Integer.valueOf(i));
                    if (child != null) queue.add(child);
                } catch (Throwable ignored) {}
            }
        }
        return firstView;
    }

    private static Bounds androidViewBoundsInWindow(Object view) {
        if (view == null) return null;
        Number width = numberValue(invokeNoArgNamed(view, "getWidth"));
        Number height = numberValue(invokeNoArgNamed(view, "getHeight"));
        if (width == null || height == null || width.intValue() <= 0 || height.intValue() <= 0) return null;
        int[] location = new int[2];
        Method locationMethod = findMethod(view.getClass(), "getLocationInWindow", int[].class);
        if (locationMethod == null) return null;
        try {
            locationMethod.setAccessible(true);
            locationMethod.invoke(view, (Object) location);
            return new Bounds(
                    location[0],
                    location[1],
                    location[0] + width.intValue(),
                    location[1] + height.intValue()
            );
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** LayoutNode.owner is internal Kotlin API; reflection keeps the runtime helper version-neutral. */
    private static Object layoutOwner(Object node) {
        Class<?> type = node.getClass();
        while (type != null) {
            for (Method method : type.getDeclaredMethods()) {
                if (method.getParameterTypes().length == 0 && method.getName().startsWith("getOwner")) {
                    try {
                        method.setAccessible(true);
                        Object owner = method.invoke(node);
                        if (owner != null) return owner;
                    } catch (Throwable ignored) {}
                }
            }
            try {
                Field field = type.getDeclaredField("owner");
                field.setAccessible(true);
                Object owner = field.get(node);
                if (owner != null) return owner;
            } catch (Throwable ignored) {}
            type = type.getSuperclass();
        }
        return null;
    }

    /**
     * Draw only the exact live region in the same window coordinate system used by
     * LayoutInfo.positionInWindow(). No full-view screenshot/crop and no adb/status-bar offset is
     * involved.
     */
    private static byte[] drawViewRegion(Object view, Bounds windowBounds) {
        try {
            Number viewWidth = numberValue(invokeNoArgNamed(view, "getWidth"));
            Number viewHeight = numberValue(invokeNoArgNamed(view, "getHeight"));
            if (viewWidth == null || viewHeight == null || viewWidth.intValue() <= 0 || viewHeight.intValue() <= 0) {
                return null;
            }

            int[] location = new int[2];
            Method locationMethod = findMethod(view.getClass(), "getLocationInWindow", int[].class);
            if (locationMethod == null) return null;
            locationMethod.setAccessible(true);
            locationMethod.invoke(view, (Object) location);

            int localLeft = Math.max(0, windowBounds.left - location[0]);
            int localTop = Math.max(0, windowBounds.top - location[1]);
            int localRight = Math.min(viewWidth.intValue(), windowBounds.right - location[0]);
            int localBottom = Math.min(viewHeight.intValue(), windowBounds.bottom - location[1]);
            int cropWidth = localRight - localLeft;
            int cropHeight = localBottom - localTop;
            if (cropWidth <= 0 || cropHeight <= 0) return null;

            Class<?> bitmapClass = Class.forName("android.graphics.Bitmap");
            Class<?> configClass = Class.forName("android.graphics.Bitmap$Config");
            Object argb8888 = configClass.getField("ARGB_8888").get(null);
            Method createBitmap = bitmapClass.getMethod("createBitmap", int.class, int.class, configClass);
            // Allocate only the composable's exact live region. We do not create/crop a full-screen
            // screenshot; translating the Canvas makes AndroidComposeView draw directly into this
            // region-sized bitmap.
            Object region = createBitmap.invoke(null, cropWidth, cropHeight, argb8888);

            Class<?> canvasClass = Class.forName("android.graphics.Canvas");
            Object canvas = canvasClass.getConstructor(bitmapClass).newInstance(region);
            Method translate = canvasClass.getMethod("translate", float.class, float.class);
            translate.invoke(canvas, (float) -localLeft, (float) -localTop);
            Method draw = findCompatibleMethod(view.getClass(), "draw", canvasClass);
            if (draw == null) {
                recycleBitmap(region);
                return null;
            }
            draw.setAccessible(true);
            draw.invoke(view, canvas);

            Class<?> formatClass = Class.forName("android.graphics.Bitmap$CompressFormat");
            Object png = formatClass.getField("PNG").get(null);
            Method compress = bitmapClass.getMethod("compress", formatClass, int.class, java.io.OutputStream.class);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Object success = compress.invoke(region, png, 100, bytes);
            recycleBitmap(region);
            if (success instanceof Boolean && !((Boolean) success).booleanValue()) return null;
            return bytes.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Hardware-surface fallback for Compose content that cannot be replayed through View.draw().
     * Uses reflection so flowgraph-runtime remains a plain java-library with no compile-time
     * Android dependency.
     */
    private static byte[] pixelCopyViewRegion(Object view, Bounds windowBounds, DiagnosticStats stats) {
        Object bitmap = null;
        Object handlerThread = null;
        try {
            if (windowBounds == null || !windowBounds.isPositive()) return null;
            Object window = findOwningWindow(view);
            if (window == null) {
                stats.pixelCopyLastResult = "no owning Window";
                return null;
            }

            Class<?> bitmapClass = Class.forName("android.graphics.Bitmap");
            Class<?> configClass = Class.forName("android.graphics.Bitmap$Config");
            Object argb8888 = configClass.getField("ARGB_8888").get(null);
            Method createBitmap = bitmapClass.getMethod("createBitmap", int.class, int.class, configClass);
            bitmap = createBitmap.invoke(null, windowBounds.width(), windowBounds.height(), argb8888);

            Class<?> rectClass = Class.forName("android.graphics.Rect");
            Object srcRect = rectClass.getConstructor(int.class, int.class, int.class, int.class).newInstance(
                    windowBounds.left,
                    windowBounds.top,
                    windowBounds.right,
                    windowBounds.bottom
            );

            Class<?> looperClass = Class.forName("android.os.Looper");
            Class<?> handlerClass = Class.forName("android.os.Handler");
            Class<?> handlerThreadClass = Class.forName("android.os.HandlerThread");
            handlerThread = handlerThreadClass.getConstructor(String.class).newInstance("FlowGraphPixelCopy");
            handlerThreadClass.getMethod("start").invoke(handlerThread);
            Object looper = handlerThreadClass.getMethod("getLooper").invoke(handlerThread);
            Object handler = handlerClass.getConstructor(looperClass).newInstance(looper);

            Class<?> pixelCopyClass = Class.forName("android.view.PixelCopy");
            Class<?> listenerClass = Class.forName("android.view.PixelCopy$OnPixelCopyFinishedListener");
            Class<?> windowClass = Class.forName("android.view.Window");
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final java.util.concurrent.atomic.AtomicInteger resultCode = new java.util.concurrent.atomic.AtomicInteger(Integer.MIN_VALUE);
            Object listener = java.lang.reflect.Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] { listenerClass },
                    new java.lang.reflect.InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method method, Object[] args) {
                            if ("onPixelCopyFinished".equals(method.getName()) && args != null && args.length > 0 && args[0] instanceof Number) {
                                resultCode.set(((Number) args[0]).intValue());
                                latch.countDown();
                            }
                            return null;
                        }
                    }
            );

            Method request = pixelCopyClass.getMethod(
                    "request",
                    windowClass,
                    rectClass,
                    bitmapClass,
                    listenerClass,
                    handlerClass
            );
            request.invoke(null, window, srcRect, bitmap, listener, handler);

            boolean completed = latch.await(1600L, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!completed) {
                stats.pixelCopyLastResult = "timeout";
                recycleBitmap(bitmap);
                bitmap = null;
                return null;
            }
            int successCode = pixelCopyClass.getField("SUCCESS").getInt(null);
            int result = resultCode.get();
            if (result != successCode) {
                stats.pixelCopyLastResult = "result=" + result;
                recycleBitmap(bitmap);
                bitmap = null;
                return null;
            }
            stats.pixelCopySuccesses++;
            stats.pixelCopyLastResult = "SUCCESS";

            Class<?> formatClass = Class.forName("android.graphics.Bitmap$CompressFormat");
            Object png = formatClass.getField("PNG").get(null);
            Method compress = bitmapClass.getMethod("compress", formatClass, int.class, java.io.OutputStream.class);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            Object success = compress.invoke(bitmap, png, 100, bytes);
            recycleBitmap(bitmap);
            bitmap = null;
            if (success instanceof Boolean && !((Boolean) success).booleanValue()) {
                stats.pixelCopyLastResult = "PNG compression failed";
                return null;
            }
            return bytes.toByteArray();
        } catch (Throwable error) {
            stats.pixelCopyLastResult = error.getClass().getSimpleName() +
                    (error.getMessage() == null ? "" : ": " + error.getMessage());
            recycleBitmap(bitmap);
            return null;
        } finally {
            if (handlerThread != null) {
                try {
                    Method quitSafely = findMethod(handlerThread.getClass(), "quitSafely");
                    if (quitSafely != null) {
                        quitSafely.setAccessible(true);
                        quitSafely.invoke(handlerThread);
                    } else {
                        Method quit = findMethod(handlerThread.getClass(), "quit");
                        if (quit != null) {
                            quit.setAccessible(true);
                            quit.invoke(handlerThread);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
    }

    /** Find the exact Window for normal activities as well as Compose Dialog/Popup roots. */
    private static Object findOwningWindow(Object view) {
        IdentityHashMap<Object, Boolean> visited = new IdentityHashMap<>();
        Object current = view;
        for (int depth = 0; current != null && depth < 24; depth++) {
            if (visited.put(current, Boolean.TRUE) != null) break;
            Object window = invokeNoArgNamed(current, "getWindow");
            if (window != null && isInstanceOf(window, "android.view.Window")) return window;
            Object parent = invokeNoArgNamed(current, "getParent");
            if (parent == current) break;
            current = parent;
        }

        Object context = invokeNoArgNamed(view, "getContext");
        visited.clear();
        for (int depth = 0; context != null && depth < 16; depth++) {
            if (visited.put(context, Boolean.TRUE) != null) break;
            Object window = invokeNoArgNamed(context, "getWindow");
            if (window != null && isInstanceOf(window, "android.view.Window")) return window;
            Object base = invokeNoArgNamed(context, "getBaseContext");
            if (base == null || base == context) break;
            context = base;
        }
        return null;
    }

    private static boolean isInstanceOf(Object value, String className) {
        if (value == null) return false;
        try {
            return Class.forName(className).isInstance(value);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void recycleBitmap(Object bitmap) {
        if (bitmap == null) return;
        try {
            Method recycle = findMethod(bitmap.getClass(), "recycle");
            if (recycle != null) {
                recycle.setAccessible(true);
                recycle.invoke(bitmap);
            }
        } catch (Throwable ignored) {}
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Class<?> parameterType) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                Class<?>[] params = method.getParameterTypes();
                if (name.equals(method.getName()) && params.length == 1 && params[0].isAssignableFrom(parameterType)) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        for (Method method : type.getMethods()) {
            Class<?>[] params = method.getParameterTypes();
            if (name.equals(method.getName()) && params.length == 1 && params[0].isAssignableFrom(parameterType)) {
                return method;
            }
        }
        return null;
    }

    private static void postToAndroidMainThread(Runnable runnable, long delayMs) {
        try {
            Class<?> looperClass = Class.forName("android.os.Looper");
            Object mainLooper = looperClass.getMethod("getMainLooper").invoke(null);
            Class<?> handlerClass = Class.forName("android.os.Handler");
            Object handler = handlerClass.getConstructor(looperClass).newInstance(mainLooper);
            Method postDelayed = handlerClass.getMethod("postDelayed", Runnable.class, long.class);
            postDelayed.invoke(handler, runnable, delayMs);
        } catch (Throwable error) {
            // Instrumentation is debug-only. If Android scheduling APIs are unavailable, report from
            // the caller's thread rather than crashing app code.
            try { runnable.run(); } catch (Throwable ignored) {}
        }
    }

    private static ParsedSourceInfo parseSourceInfo(String raw, String inheritedFile) {
        if (raw == null || raw.length() == 0) return new ParsedSourceInfo(null, inheritedFile);

        // Newer Compose runtime exposes the exact parser used by tooling. Prefer it when present.
        try {
            Class<?> parser = Class.forName("androidx.compose.runtime.tooling.SourceInformationKt");
            Method method = parser.getMethod("parseSourceInformation", String.class);
            Object parsed = method.invoke(null, raw);
            if (parsed != null) {
                String name = stringValue(invokeNoArgNamed(parsed, "getFunctionName"));
                String file = stringValue(invokeNoArgNamed(parsed, "getSourceFile"));
                return new ParsedSourceInfo(name, file == null ? inheritedFile : stripPackageHash(file));
            }
        } catch (Throwable ignored) {}

        // Compatible fallback for older Compose releases. Compiler source information starts with
        // C(name) / CC(name) and, when present, ends in :File.kt#packageHash.
        String name = null;
        int nameStart = raw.startsWith("CC(") ? 3 : (raw.startsWith("C(") ? 2 : -1);
        if (nameStart >= 0) {
            int end = raw.indexOf(')', nameStart);
            if (end > nameStart) name = raw.substring(nameStart, end);
        }

        String file = inheritedFile;
        int kt = raw.lastIndexOf(".kt");
        if (kt >= 0) {
            int colon = raw.lastIndexOf(':', kt);
            if (colon >= 0 && colon + 1 < kt + 3) {
                file = raw.substring(colon + 1, kt + 3);
            }
        }
        return new ParsedSourceInfo(name, stripPackageHash(file));
    }

    private static String stripPackageHash(String file) {
        if (file == null) return null;
        int hash = file.lastIndexOf('#');
        return hash > 0 ? file.substring(0, hash) : file;
    }

    private static Number numberValue(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static String stringValue(Object value) {
        return value instanceof String ? (String) value : null;
    }

    private static final class CurrentComposeCandidate {
        final String runtimeKey;
        final long visibleArea;
        final long compositionSeenMillis;
        final long lastComposeNanos;

        CurrentComposeCandidate(String runtimeKey, long visibleArea, long compositionSeenMillis, long lastComposeNanos) {
            this.runtimeKey = runtimeKey;
            this.visibleArea = visibleArea;
            this.compositionSeenMillis = compositionSeenMillis;
            this.lastComposeNanos = lastComposeNanos;
        }

        boolean betterThan(CurrentComposeCandidate other) {
            if (visibleArea != other.visibleArea) return visibleArea > other.visibleArea;
            if (lastComposeNanos != other.lastComposeNanos) return lastComposeNanos > other.lastComposeNanos;
            return compositionSeenMillis > other.compositionSeenMillis;
        }
    }

    private static final class ComposeMatchAggregate {
        final Object ownerView;
        Bounds bounds;
        int instanceCount;
        String origin;

        ComposeMatchAggregate(Object ownerView) {
            this.ownerView = ownerView;
        }

        void add(ComposeGroupMatch match) {
            if (match == null || match.bounds == null || !match.bounds.isPositive()) return;
            bounds = bounds == null ? match.bounds : bounds.union(match.bounds);
            instanceCount++;
            if (origin == null) origin = match.origin;
            else if (!origin.equals(match.origin)) origin = "mixed";
        }
    }

    private static final class RepeatedRegionExpansion {
        final Bounds bounds;
        final int instanceCount;
        RepeatedRegionExpansion(Bounds bounds, int instanceCount) {
            this.bounds = bounds;
            this.instanceCount = instanceCount;
        }
    }

    private static final class ActiveComposition {
        final Object data;
        final long firstSeenMillis;
        long lastSeenMillis;
        final LinkedHashSet<String> runtimeKeys = new LinkedHashSet<>();
        final LinkedHashSet<String> sites = new LinkedHashSet<>();

        ActiveComposition(Object data, long seenAtMillis) {
            this.data = data;
            this.firstSeenMillis = seenAtMillis;
            this.lastSeenMillis = seenAtMillis;
        }
    }

    private static final class DiagnosticStats {
        ComposeTarget target;
        int activeCompositions;
        int compositionsWithRoots;
        int compositionsWithoutRoots;
        int groupsScanned;
        int groupsWithSourceInfo;
        int functionNameMatches;
        int exactSourceMatches;
        int groupKeyMatches;
        int matchesWithBounds;
        int directLayoutMatches;
        int subCompositionMatches;
        int subCompositionRootsFound;
        int subCompositionOwnerViewsFound;
        int groupNodesSeen;
        int layoutInfoNodesSeen;
        int attachedLayoutInfoNodes;
        int detachedLayoutInfoNodes;
        int layoutInfoMissingCoordinatesOrSize;
        int layoutCoordinatesReadable;
        int layoutPositionsReadable;
        int layoutPositionFailures;
        int layoutOwnersReadable;
        int layoutOwnerFailures;
        int geometryRetryAttempt;
        int drawAttempts;
        int pixelCopyAttempts;
        int pixelCopySuccesses;
        String pixelCopyLastResult;
        String captureMethod;
        int selectedWidth;
        int selectedHeight;
        int visibleInstances;
        int inferredPeerInstances;
        int ownerGroupsWithMatches;
        long inspectionAttempts;
        long inspectionSuccesses;
        long inspectionMissingData;
        String lastInspectionFailure;
        final LinkedHashSet<String> functionFiles = new LinkedHashSet<>();
        final LinkedHashSet<Integer> targetGroupKeys = new LinkedHashSet<>();

        void noteFunctionFile(String file) {
            if (file == null || file.length() == 0 || functionFiles.size() >= 6) return;
            functionFiles.add(file);
        }

        String failure(String reason) {
            StringBuilder out = new StringBuilder();
            out.append("Render request reached app.\n");
            out.append("Active CompositionData objects: ").append(activeCompositions).append('\n');
            out.append("CompositionData registrations: ")
                    .append(inspectionSuccesses).append(" successful / ")
                    .append(inspectionAttempts).append(" attempts");
            if (inspectionMissingData > 0) out.append(" / ").append(inspectionMissingData).append(" missing");
            out.append('\n');
            out.append("Composition roots readable: ").append(compositionsWithRoots)
                    .append('/').append(activeCompositions).append('\n');
            out.append("Composition groups scanned: ").append(groupsScanned).append('\n');
            out.append("Groups with source info: ").append(groupsWithSourceInfo).append('\n');
            out.append("Captured compiler group keys: ").append(targetGroupKeys).append('\n');
            out.append("Group-key matches: ").append(groupKeyMatches).append('\n');
            if (target != null) {
                out.append("Requested source: ").append(target.functionName)
                        .append(" in ").append(target.fileName).append('\n');
            }
            out.append("Function-name matches: ").append(functionNameMatches);
            if (!functionFiles.isEmpty()) out.append(" (files: ").append(functionFiles).append(')');
            out.append('\n');
            out.append("Target group matches (key or source): ").append(exactSourceMatches).append('\n');
            out.append("Matches with usable geometry: ").append(matchesWithBounds)
                    .append(" (LayoutInfo=").append(directLayoutMatches)
                    .append(", sub-composition=").append(subCompositionMatches).append(")\n");
            out.append("Visible matching instances selected: ").append(visibleInstances)
                    .append(" across ").append(ownerGroupsWithMatches).append(" owner view(s)\n");
            if (inferredPeerInstances > 1) {
                out.append("Repeated-layout fallback: inferred ").append(inferredPeerInstances)
                        .append(" adjacent visible peer cells\n");
            }
            out.append("Matched-subtree nodes: ").append(groupNodesSeen)
                    .append("; LayoutInfo: ").append(layoutInfoNodesSeen)
                    .append(" (attached=").append(attachedLayoutInfoNodes)
                    .append(", detached=").append(detachedLayoutInfoNodes).append(")\n");
            out.append("Layout geometry: coordinates=").append(layoutCoordinatesReadable)
                    .append(", positions=").append(layoutPositionsReadable)
                    .append(", owners=").append(layoutOwnersReadable)
                    .append("; failures position=").append(layoutPositionFailures)
                    .append(", owner=").append(layoutOwnerFailures)
                    .append(", size/coords=").append(layoutInfoMissingCoordinatesOrSize).append('\n');
            out.append("Sub-composition roots: ").append(subCompositionRootsFound)
                    .append("; owner views: ").append(subCompositionOwnerViewsFound).append('\n');
            out.append("Geometry retry: ").append(geometryRetryAttempt)
                    .append('/').append(COMPOSE_GEOMETRY_RETRIES).append('\n');
            out.append("Capture attempts: View.draw=").append(drawAttempts)
                    .append(", PixelCopy=").append(pixelCopyAttempts)
                    .append(" (success=").append(pixelCopySuccesses).append(')');
            if (pixelCopyLastResult != null) out.append(" • last PixelCopy: ").append(pixelCopyLastResult);
            out.append('\n');
            if (lastInspectionFailure != null && lastInspectionFailure.length() > 0) {
                out.append("Last CompositionData reflection failure: ").append(lastInspectionFailure).append('\n');
            }
            out.append("Failure: ").append(reason);
            return out.toString();
        }
    }

    private static final class ParsedSourceInfo {
        final String functionName;
        final String sourceFile;
        ParsedSourceInfo(String functionName, String sourceFile) {
            this.functionName = functionName;
            this.sourceFile = sourceFile;
        }
    }

    private static final class ComposeTarget {
        final String fileName;
        final String functionName;

        ComposeTarget(String fileName, String functionName) {
            this.fileName = fileName;
            this.functionName = functionName;
        }

        static ComposeTarget fromRuntimeKey(String runtimeKey) {
            if (runtimeKey == null || !runtimeKey.startsWith("@compose|")) return null;
            String[] parts = runtimeKey.split("\\|", -1);
            if (parts.length < 4 || parts[2].length() == 0 || parts[3].length() == 0) return null;
            return new ComposeTarget(parts[2], parts[3]);
        }

        boolean matches(String name, String file) {
            if (!functionName.equals(name)) return false;
            if (file == null) return true;
            String normalized = stripPackageHash(file);
            return fileName.equals(normalized) || normalized.endsWith("/" + fileName);
        }
    }

    private static final class ComposeGroupMatch {
        final Object ownerView;
        final Bounds bounds;
        final String origin;
        ComposeGroupMatch(Object ownerView, Bounds bounds, String origin) {
            this.ownerView = ownerView;
            this.bounds = bounds;
            this.origin = origin;
        }
    }

    private static final class LayoutBounds {
        final Object owner;
        final Bounds bounds;
        LayoutBounds(Object owner, Bounds bounds) {
            this.owner = owner;
            this.bounds = bounds;
        }
    }

    private static final class Bounds {
        final int left;
        final int top;
        final int right;
        final int bottom;

        Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int width() { return Math.max(0, right - left); }
        int height() { return Math.max(0, bottom - top); }
        long area() { return (long) width() * (long) height(); }
        boolean isPositive() { return width() > 0 && height() > 0; }

        Bounds union(Bounds other) {
            if (other == null) return this;
            return new Bounds(
                    Math.min(left, other.left),
                    Math.min(top, other.top),
                    Math.max(right, other.right),
                    Math.max(bottom, other.bottom)
            );
        }
    }

    private static boolean implementsNamedInterface(Class<?> type, String wanted) {
        if (type == null) return false;
        if (wanted.equals(type.getName())) return true;
        for (Class<?> iface : type.getInterfaces()) {
            if (implementsNamedInterface(iface, wanted)) return true;
        }
        return implementsNamedInterface(type.getSuperclass(), wanted);
    }

    private static Method findMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try { return current.getDeclaredMethod(name, parameterTypes); }
            catch (Throwable ignored) { current = current.getSuperclass(); }
        }
        try { return type.getMethod(name, parameterTypes); }
        catch (Throwable ignored) { return null; }
    }

    private static Object invokeNoArgNamed(Object target, String name) {
        if (target == null) return null;
        Method method = findMethod(target.getClass(), name);
        if (method == null) return null;
        try { method.setAccessible(true); return method.invoke(target); }
        catch (Throwable ignored) { return null; }
    }

    /** Called before an instrumented StateFlow.getValue(). */
    public static void recordRead(Object flow, String site) {
        if (!enabled || flow == null) return;
        List<String> keys = keysFor(flow);
        if (keys.isEmpty()) return;

        final Integer instanceId = composeInstanceId(flow);
        long now = System.nanoTime();
        for (String key : keys) {
            String throttleKey = key + "@" + (instanceId == null ? "" : instanceId + "@") + site;
            Long previous = LAST_READ_BY_SITE.put(throttleKey, now);
            // Reads can be extremely hot (Compose in particular). Keep the graph useful instead of
            // turning the socket into a profiler firehose. Compose reads are throttled per runtime
            // instance so one Lazy item cannot suppress another item's diagnostics.
            if (previous != null && now - previous < 25_000_000L) continue;
            emit(key, "read", null, site, Collections.<String>emptySet(), instanceId);
        }
    }

    private static PendingWrite popPendingFor(Object flow) {
        java.util.ArrayDeque<PendingWrite> stack = PENDING_WRITES.get();
        if (stack.isEmpty()) return null;
        PendingWrite top = stack.peek();
        if (top != null && top.flow == flow) return stack.pop();
        java.util.ArrayList<PendingWrite> skipped = new java.util.ArrayList<>();
        PendingWrite found = null;
        while (!stack.isEmpty()) {
            PendingWrite item = stack.pop();
            if (item.flow == flow) { found = item; break; }
            skipped.add(item);
        }
        for (int i = skipped.size() - 1; i >= 0; i--) stack.push(skipped.get(i));
        return found;
    }

    private static void cleanupComposeStateRuntimeLocked() {
        IdentityWeakReference ref;
        while ((ref = (IdentityWeakReference) COMPOSE_STATE_REFERENCE_QUEUE.poll()) != null) {
            COMPOSE_STATE_RUNTIME.remove(ref);
        }
    }

    private static ComposeStateRuntime composeStateRuntimeLocked(Object value, boolean create) {
        cleanupComposeStateRuntimeLocked();
        ComposeStateRuntime runtime = COMPOSE_STATE_RUNTIME.get(new IdentityWeakReference(value));
        if (runtime == null && create) {
            runtime = new ComposeStateRuntime(nextComposeStateInstanceId++);
            COMPOSE_STATE_RUNTIME.put(new IdentityWeakReference(value, COMPOSE_STATE_REFERENCE_QUEUE), runtime);
        }
        return runtime;
    }

    private static Integer composeInstanceId(Object value) {
        synchronized (LOCK) {
            ComposeStateRuntime runtime = composeStateRuntimeLocked(value, false);
            return runtime == null ? null : Integer.valueOf(runtime.instanceId);
        }
    }

    private static String siteWithKind(String site, String kind) {
        if (site == null || site.length() == 0) return kind;
        return site + " • " + kind;
    }

    private static String joinSites(String collectSite, String emitSite) {
        if (collectSite == null || collectSite.length() == 0) return emitSite;
        if (emitSite == null || emitSite.length() == 0 || collectSite.equals(emitSite)) return collectSite;
        return collectSite + " → emit@" + emitSite;
    }

    private static Object normalize(Object value) {
        return value == null ? NullValue.INSTANCE : value;
    }

    private static List<String> keysFor(Object flow) {
        synchronized (LOCK) {
            ComposeStateRuntime composeRuntime = composeStateRuntimeLocked(flow, false);
            if (composeRuntime != null) {
                // Delegated access hooks carry a stronger source identity (property + exact access
                // line). Once one is observed, generic nested Compose-runtime hooks are suppressed
                // for this object; otherwise an inlined/library setter can re-introduce the same
                // false attribution that the exact hook is designed to remove.
                if (composeRuntime.delegatedAccessTracing) {
                    return Collections.emptyList();
                }
                if (!composeRuntime.exactPropertyKeys.isEmpty()) {
                    return new ArrayList<>(composeRuntime.exactPropertyKeys);
                }
                if (!composeRuntime.keys.isEmpty()) {
                    return new ArrayList<>(composeRuntime.keys);
                }
            }
            LinkedHashSet<String> keys = KEYS_BY_OBJECT.get(flow);
            if (keys == null || keys.isEmpty()) return Collections.emptyList();
            return new ArrayList<>(keys);
        }
    }

    private static boolean associateExact(Object value, String key) {
        LinkedHashSet<String> keys = KEYS_BY_OBJECT.get(value);
        if (keys == null) {
            keys = new LinkedHashSet<>();
            KEYS_BY_OBJECT.put(value, keys);
        }
        return keys.add(key);
    }

    /**
     * StateFlow/SharedFlow readonly wrappers often contain the mutable implementation object. Walking
     * those wrappers is useful. We intentionally do NOT recurse arbitrary cold Flow operator objects,
     * because a map/filter Flow usually contains its upstream Flow and aliasing both to the same key
     * would falsely report upstream values as transformed output values.
     */
    private static boolean associateHotFlowRecursively(
            Object value,
            String key,
            int depth,
            IdentityHashMap<Object, Boolean> visited
    ) {
        if (value == null || depth > 3 || visited.put(value, Boolean.TRUE) != null) return false;
        if (!looksLikeStateOrSharedFlow(value)) return false;

        boolean added = associateExact(value, key);

        Class<?> type = value.getClass();
        while (type != null && type != Object.class) {
            Field[] fields;
            try {
                fields = type.getDeclaredFields();
            } catch (Throwable ignored) {
                break;
            }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object nested = field.get(value);
                    if (nested != null && looksLikeStateOrSharedFlow(nested)) {
                        associateHotFlowRecursively(nested, key, depth + 1, visited);
                    }
                } catch (Throwable ignored) {
                    // Debug tooling must never break app behavior because a wrapper is not reflectable.
                }
            }
            type = type.getSuperclass();
        }
        return added;
    }

    private static boolean looksLikeAnyFlow(Object value) {
        Class<?> type = value.getClass();
        while (type != null) {
            for (Class<?> iface : type.getInterfaces()) {
                if (anyFlowInterface(iface)) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean looksLikeComposeState(Object value) {
        if (value == null) return false;
        Class<?> type = value.getClass();
        while (type != null) {
            String name = type.getName();
            if (name.startsWith("androidx.compose.runtime.") &&
                    (name.contains("State") || name.contains("Snapshot"))) return true;
            for (Class<?> iface : type.getInterfaces()) {
                if (composeStateInterface(iface)) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean composeStateInterface(Class<?> iface) {
        String name = iface.getName();
        if ("androidx.compose.runtime.State".equals(name)
                || "androidx.compose.runtime.MutableState".equals(name)
                || "androidx.compose.runtime.IntState".equals(name)
                || "androidx.compose.runtime.MutableIntState".equals(name)
                || "androidx.compose.runtime.LongState".equals(name)
                || "androidx.compose.runtime.MutableLongState".equals(name)
                || "androidx.compose.runtime.FloatState".equals(name)
                || "androidx.compose.runtime.MutableFloatState".equals(name)
                || "androidx.compose.runtime.DoubleState".equals(name)
                || "androidx.compose.runtime.MutableDoubleState".equals(name)) {
            return true;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            if (composeStateInterface(parent)) return true;
        }
        return false;
    }

    private static boolean looksLikeStateOrSharedFlow(Object value) {
        Class<?> type = value.getClass();
        while (type != null) {
            String name = type.getName();
            if (name.contains("StateFlow") || name.contains("SharedFlow")) return true;
            for (Class<?> iface : type.getInterfaces()) {
                if (hotFlowInterface(iface)) return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }

    private static boolean anyFlowInterface(Class<?> iface) {
        String name = iface.getName();
        if ("kotlinx.coroutines.flow.Flow".equals(name)
                || "kotlinx.coroutines.flow.StateFlow".equals(name)
                || "kotlinx.coroutines.flow.MutableStateFlow".equals(name)
                || "kotlinx.coroutines.flow.SharedFlow".equals(name)
                || "kotlinx.coroutines.flow.MutableSharedFlow".equals(name)
                || "androidx.compose.runtime.State".equals(name)
                || "androidx.compose.runtime.MutableState".equals(name)
                || "androidx.compose.runtime.IntState".equals(name)
                || "androidx.compose.runtime.MutableIntState".equals(name)
                || "androidx.compose.runtime.LongState".equals(name)
                || "androidx.compose.runtime.MutableLongState".equals(name)
                || "androidx.compose.runtime.FloatState".equals(name)
                || "androidx.compose.runtime.MutableFloatState".equals(name)
                || "androidx.compose.runtime.DoubleState".equals(name)
                || "androidx.compose.runtime.MutableDoubleState".equals(name)) {
            return true;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            if (anyFlowInterface(parent)) return true;
        }
        return false;
    }

    private static boolean hotFlowInterface(Class<?> iface) {
        String name = iface.getName();
        if ("kotlinx.coroutines.flow.StateFlow".equals(name)
                || "kotlinx.coroutines.flow.MutableStateFlow".equals(name)
                || "kotlinx.coroutines.flow.SharedFlow".equals(name)
                || "kotlinx.coroutines.flow.MutableSharedFlow".equals(name)) {
            return true;
        }
        for (Class<?> parent : iface.getInterfaces()) {
            if (hotFlowInterface(parent)) return true;
        }
        return false;
    }

    private static String kotlinPropertyName(Object propertyReference) {
        try {
            Method method = propertyReference.getClass().getMethod("getName");
            method.setAccessible(true);
            Object value = method.invoke(propertyReference);
            return value == null ? null : String.valueOf(value);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private interface SnapshotReadOperation {
        Object run();
    }

    /**
     * Reads State/MutableState without letting Flow Graph become a snapshot observer itself.
     *
     * This is deliberately fail-closed for Compose state: if the installed Compose runtime does
     * not expose Snapshot.withoutReadObservation, we return NO_VALUE rather than perform an
     * observed read that could alter recomposition behavior. StateFlow/SharedFlow reads are not
     * snapshot reads and continue through the direct path.
     */
    private static boolean composeValuesEqual(final Object left, final Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        ClassLoader loader = left.getClass().getClassLoader();
        Object result = runWithoutComposeReadObservation(loader, new SnapshotReadOperation() {
            @Override public Object run() {
                return Boolean.valueOf(Objects.equals(left, right));
            }
        });
        // If non-observing snapshot access is unavailable, prefer a false negative (an extra debug
        // event) over performing an observed equals() that could mutate the app's dependency graph.
        return Boolean.TRUE.equals(result);
    }

    private static Object readValue(final Object flow) {
        if (flow == null) return NO_VALUE;
        if (!looksLikeComposeState(flow)) return readValueDirect(flow);

        return runWithoutComposeReadObservation(flow.getClass().getClassLoader(), new SnapshotReadOperation() {
            @Override public Object run() {
                return readValueDirect(flow);
            }
        });
    }

    private static Object readValueDirect(Object flow) {
        String[] getters = new String[] {
                "getValue", "getIntValue", "getLongValue", "getFloatValue", "getDoubleValue"
        };
        for (String getter : getters) {
            try {
                Method method = flow.getClass().getMethod(getter);
                method.setAccessible(true);
                return method.invoke(flow);
            } catch (NoSuchMethodException ignored) {
                // Try the next Compose primitive-state accessor.
            } catch (Throwable ignored) {
                return NO_VALUE;
            }
        }
        return NO_VALUE;
    }

    private static Object runWithoutComposeReadObservation(
            ClassLoader preferredLoader,
            final SnapshotReadOperation operation
    ) {
        resolveSnapshotWithoutReadObservation(preferredLoader);
        final Method method = snapshotWithoutReadObservationMethod;
        final Object companion = snapshotCompanion;
        final Class<?> function0 = kotlinFunction0Class;
        if (method == null || companion == null || function0 == null) {
            return NO_VALUE;
        }

        try {
            ClassLoader loader = function0.getClassLoader();
            if (loader == null) loader = preferredLoader;
            if (loader == null) loader = FlowGraphAutoRuntime.class.getClassLoader();
            Object block = Proxy.newProxyInstance(
                    loader,
                    new Class<?>[] { function0 },
                    new InvocationHandler() {
                        @Override public Object invoke(Object proxy, Method invoked, Object[] args) {
                            String name = invoked.getName();
                            if ("invoke".equals(name)) return operation.run();
                            if ("toString".equals(name)) return "FlowGraphWithoutReadObservation";
                            if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                            if ("equals".equals(name)) return proxy == (args == null || args.length == 0 ? null : args[0]);
                            return null;
                        }
                    }
            );
            return method.invoke(companion, block);
        } catch (Throwable ignored) {
            // Debug tooling must never fall back to an observed Compose-state read.
            return NO_VALUE;
        }
    }

    private static void resolveSnapshotWithoutReadObservation(ClassLoader preferredLoader) {
        if (snapshotWithoutReadObservationResolved) return;
        synchronized (SNAPSHOT_OBSERVER_LOCK) {
            if (snapshotWithoutReadObservationResolved) return;
            try {
                ClassLoader loader = preferredLoader;
                if (loader == null) loader = FlowGraphAutoRuntime.class.getClassLoader();
                Class<?> snapshotClass = Class.forName(
                        "androidx.compose.runtime.snapshots.Snapshot",
                        false,
                        loader
                );
                Class<?> function0 = Class.forName("kotlin.jvm.functions.Function0", false, loader);
                Field companionField = snapshotClass.getField("Companion");
                Object companion = companionField.get(null);
                Method withoutReadObservation = companion.getClass().getMethod(
                        "withoutReadObservation",
                        function0
                );
                withoutReadObservation.setAccessible(true);
                snapshotCompanion = companion;
                snapshotWithoutReadObservationMethod = withoutReadObservation;
                kotlinFunction0Class = function0;
            } catch (Throwable ignored) {
                snapshotCompanion = null;
                snapshotWithoutReadObservationMethod = null;
                kotlinFunction0Class = null;
            } finally {
                snapshotWithoutReadObservationResolved = true;
            }
        }
    }

    private static void emit(String stateKey, String kind, Object value, String site, Set<String> fields) {
        emit(stateKey, kind, value, site, fields, null);
    }

    private static void emit(String stateKey, String kind, Object value, String site, Set<String> fields, Integer instanceId) {
        if (!enabled) return;
        emitSummary(stateKey, kind, summarizeEventValue(value, instanceId), site, fields, instanceId);
    }

    private static String summarizeEventValue(final Object value, Integer instanceId) {
        if (instanceId == null || value == null) return summarize(value);
        ClassLoader loader = value.getClass().getClassLoader();
        Object summary = runWithoutComposeReadObservation(loader, new SnapshotReadOperation() {
            @Override public Object run() {
                return summarize(value);
            }
        });
        if (summary instanceof String) return (String) summary;

        // Fail closed: do not call an arbitrary Compose-backed value's toString() while a
        // recomposition read observer may be active. Primitive/enum values are safe to display.
        if (value instanceof String || value instanceof Number || value instanceof Boolean ||
                value instanceof Character || value.getClass().isEnum()) {
            return String.valueOf(value);
        }
        return "<" + value.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(value)) + ">";
    }

    private static void emitSummary(String stateKey, String kind, String valueSummary, String site, Set<String> fields) {
        emitSummary(stateKey, kind, valueSummary, site, fields, null);
    }

    private static void emitSummary(String stateKey, String kind, String valueSummary, String site, Set<String> fields, Integer instanceId) {
        if (!enabled) return;

        // Large image/control responses are explicit user requests and must never be sampled.
        if (isControlPayload(kind)) {
            sendSummaryNow(stateKey, kind, valueSummary, site, fields, 1, System.nanoTime(), instanceId);
            return;
        }

        final String safeSite = site == null ? "" : site;
        final String bucketKey = stateKey + "\u0000" + kind + "\u0000" + (instanceId == null ? "" : instanceId) + "\u0000" + safeSite;

        // Bound pathological cases with thousands of unique dynamic sites. If the coalescer is
        // saturated, still send the event, but do not grow another unbounded in-memory queue.
        if (!PENDING_TRACES.containsKey(bucketKey) && PENDING_TRACES.size() >= MAX_PENDING_TRACE_BUCKETS) {
            sendSummaryNow(stateKey, kind, valueSummary, safeSite, fields, 1, System.nanoTime(), instanceId);
            return;
        }

        final PendingTrace pending = PENDING_TRACES.computeIfAbsent(bucketKey, ignored -> new PendingTrace(stateKey, kind, instanceId));
        boolean scheduleFlush = false;
        synchronized (pending) {
            pending.valueSummary = valueSummary == null ? "" : valueSummary;
            pending.site = safeSite;
            if (fields != null) pending.fields.addAll(fields);
            pending.timestampNanos = System.nanoTime();
            if (pending.occurrences < Integer.MAX_VALUE) pending.occurrences++;
            if (!pending.scheduled) {
                pending.scheduled = true;
                scheduleFlush = true;
            }
        }

        if (scheduleFlush) {
            IO.schedule(() -> flushPendingTrace(bucketKey, pending), TRACE_COALESCE_WINDOW_MS, TimeUnit.MILLISECONDS);
        }
    }

    private static boolean isControlPayload(String kind) {
        return "compose-image".equals(kind)
                || "compose-image-error".equals(kind)
                || "compose-current".equals(kind)
                || "compose-current-error".equals(kind);
    }

    private static void flushPendingTrace(String bucketKey, PendingTrace pending) {
        String valueSummary;
        String site;
        Set<String> fields;
        long timestampNanos;
        int occurrences;
        synchronized (pending) {
            valueSummary = pending.valueSummary == null ? "" : pending.valueSummary;
            site = pending.site == null ? "" : pending.site;
            fields = new LinkedHashSet<>(pending.fields);
            timestampNanos = pending.timestampNanos == 0L ? System.nanoTime() : pending.timestampNanos;
            occurrences = Math.max(1, pending.occurrences);
            pending.fields.clear();
            pending.occurrences = 0;
            pending.scheduled = false;
        }
        PENDING_TRACES.remove(bucketKey, pending);
        sendSummaryNow(pending.stateKey, pending.kind, valueSummary, site, fields, occurrences, timestampNanos, pending.instanceId);
    }

    private static void sendSummaryNow(
            String stateKey,
            String kind,
            String valueSummary,
            String site,
            Set<String> fields,
            int occurrences,
            long timestampNanos,
            Integer instanceId
    ) {
        IO.execute(() -> {
            try {
                BufferedWriter out = ensureWriter();
                String line = "FG1\t"
                        + timestampNanos + "\t"
                        + enc(stateKey) + "\t"
                        + enc(kind) + "\t"
                        + enc(valueSummary == null ? "" : valueSummary) + "\t"
                        + enc(site == null ? "" : site) + "\t"
                        + enc(join(fields)) + "\t"
                        + Math.max(1, occurrences) + "\t"
                        + (instanceId == null ? "" : instanceId);
                out.write(line);
                out.newLine();
                out.flush();
                if (logEvents) {
                    if ("compose-image".equals(kind)) {
                        logDebug("SEND compose-image " + stateKey + " (" + (valueSummary == null ? 0 : valueSummary.length()) + " chars)");
                    } else {
                        String suffix = occurrences > 1 ? " x" + occurrences : "";
                        logDebug("SEND " + kind + suffix + " " + stateKey
                                + (valueSummary == null || valueSummary.length() == 0 ? "" : " = " + valueSummary));
                    }
                }
            } catch (Throwable error) {
                logWarn(
                        "Live trace send failed for " + stateKey + " -> " + host + ":" + port + ": "
                                + error.getClass().getSimpleName() + ": " + error.getMessage()
                                + ". Enable Live trace and run: adb reverse tcp:" + port + " tcp:" + port,
                        error
                );
                closeConnection();
            }
        });
    }

    /**
     * Start one process-wide connection watcher. It connects eagerly as soon as an Activity or
     * traced Flow is observed and keeps retrying if Live Trace / adb reverse was enabled later.
     * Merely accepting this socket is enough for the IDE to show "client connected"; no fake
     * timeline event or malformed handshake is sent.
     */
    public static void startConnectionWatch() {
        if (!enabled || !CONNECTION_WATCH_STARTED.compareAndSet(false, true)) return;
        CONNECTION_IO.scheduleWithFixedDelay(new Runnable() {
            @Override public void run() {
                if (!enabled || writer != null) return;
                try {
                    ensureWriter();
                    failedConnectionAttempts = 0;
                } catch (Throwable error) {
                    int attempt = ++failedConnectionAttempts;
                    // The IDE may intentionally be off. Log the first failure and then only
                    // occasionally so Logcat remains useful rather than becoming a retry stream.
                    if (attempt == 1 || attempt % 15 == 0) {
                        logInfo(
                                "Waiting for Flow Graph live trace at " + host + ":" + port
                                        + " (attempt " + attempt + "). Enable Live trace and run: "
                                        + "adb reverse tcp:" + port + " tcp:" + port
                        );
                    }
                    closeConnection();
                }
            }
        }, 0L, 1L, TimeUnit.SECONDS);
    }

    private static BufferedWriter ensureWriter() throws Exception {
        BufferedWriter existing = writer;
        if (existing != null) return existing;
        synchronized (FlowGraphAutoRuntime.class) {
            if (writer != null) return writer;
            Socket newSocket = new Socket();
            newSocket.setTcpNoDelay(true);
            newSocket.connect(new InetSocketAddress(host, port), connectTimeoutMs);
            socket = newSocket;
            writer = new BufferedWriter(new OutputStreamWriter(newSocket.getOutputStream(), StandardCharsets.UTF_8));
            startControlReader(newSocket);
            logInfo("Connected to Flow Graph live trace at " + host + ":" + port + " (deep automatic instrumentation)");
            return writer;
        }
    }

    /**
     * The trace connection is duplex in v0.17.30. App -> IDE still carries FG1 events; IDE -> app
     * carries tiny FGC1 commands so expensive UI capture only happens when the user asks for it.
     */
    private static void startControlReader(final Socket connectedSocket) {
        Thread thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(connectedSocket.getInputStream(), StandardCharsets.UTF_8)
                    );
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("FGC1\t")) {
                            String runtimeKey = dec(line.substring(5));
                            if (runtimeKey.length() == 0) continue;
                            requestComposeImage(runtimeKey);
                        } else if (line.equals("FGC2\tCURRENT")) {
                            requestCurrentCompose();
                        }
                    }
                } catch (Throwable ignored) {
                    // Normal when the IDE stops/restarts. Drop only this socket; the connection
                    // watcher will reopen it as soon as the server is available again.
                } finally {
                    synchronized (FlowGraphAutoRuntime.class) {
                        if (socket == connectedSocket) {
                            closeConnection();
                        }
                    }
                }
            }
        }, "FlowGraphTrace-Control");
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeConnection() {
        synchronized (FlowGraphAutoRuntime.class) {
            try { if (writer != null) writer.close(); } catch (Throwable ignored) {}
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
            writer = null;
            socket = null;
        }
    }

    private static String summarize(Object value) {
        if (value == null) return "null";
        String text;
        try {
            text = String.valueOf(value);
        } catch (Throwable ignored) {
            text = "<" + value.getClass().getName() + ">";
        }
        text = text.replace('\n', ' ').replace('\r', ' ');
        return text.length() > 500 ? text.substring(0, 500) : text;
    }

    private static String join(Set<String> fields) {
        if (fields == null || fields.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (String field : fields) {
            if (out.length() > 0) out.append(',');
            out.append(field);
        }
        return out.toString();
    }

    // Small URL-safe Base64 encoder so the runtime remains Android/JVM compatible without using
    // java.util.Base64 (API 26) or android.util.Base64 (which would require an Android compile dep).
    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_".toCharArray();

    private static String enc(String text) {
        return encBytes(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String encBytes(byte[] data) {
        StringBuilder out = new StringBuilder((data.length * 4 + 2) / 3);
        int i = 0;
        while (i + 2 < data.length) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
            out.append(B64[(n >>> 18) & 63]);
            out.append(B64[(n >>> 12) & 63]);
            out.append(B64[(n >>> 6) & 63]);
            out.append(B64[n & 63]);
            i += 3;
        }
        int remaining = data.length - i;
        if (remaining == 1) {
            int n = (data[i] & 0xff) << 16;
            out.append(B64[(n >>> 18) & 63]);
            out.append(B64[(n >>> 12) & 63]);
        } else if (remaining == 2) {
            int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
            out.append(B64[(n >>> 18) & 63]);
            out.append(B64[(n >>> 12) & 63]);
            out.append(B64[(n >>> 6) & 63]);
        }
        return out.toString();
    }

    private static String dec(String text) {
        if (text == null || text.length() == 0) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream((text.length() * 3) / 4 + 3);
            int buffer = 0;
            int bits = 0;
            for (int i = 0; i < text.length(); i++) {
                int value = base64Value(text.charAt(i));
                if (value < 0) continue;
                buffer = (buffer << 6) | value;
                bits += 6;
                while (bits >= 8) {
                    bits -= 8;
                    out.write((buffer >>> bits) & 0xff);
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static int base64Value(char c) {
        if (c >= 'A' && c <= 'Z') return c - 'A';
        if (c >= 'a' && c <= 'z') return c - 'a' + 26;
        if (c >= '0' && c <= '9') return c - '0' + 52;
        if (c == '-' || c == '+') return 62;
        if (c == '_' || c == '/') return 63;
        return -1;
    }

    private static void logDebug(String message) { androidLog("d", message, null); }
    private static void logInfo(String message) { androidLog("i", message, null); }
    private static void logWarn(String message, Throwable error) { androidLog("w", message, error); }

    private static void androidLog(String methodName, String message, Throwable error) {
        try {
            Class<?> log = Class.forName("android.util.Log");
            if (error == null) {
                Method method = log.getMethod(methodName, String.class, String.class);
                method.invoke(null, TAG, message);
            } else {
                Method method = log.getMethod(methodName, String.class, String.class, Throwable.class);
                method.invoke(null, TAG, message, error);
            }
        } catch (Throwable ignored) {
            System.out.println(TAG + ": " + message);
            if (error != null) error.printStackTrace(System.out);
        }
    }

    private static final class CollectorBinding {
        final List<String> keys;
        final String collectSite;

        CollectorBinding(List<String> keys, String collectSite) {
            this.keys = Collections.unmodifiableList(new ArrayList<>(keys));
            this.collectSite = collectSite == null ? "" : collectSite;
        }

        boolean sameAs(CollectorBinding other) {
            return other != null && keys.equals(other.keys) && collectSite.equals(other.collectSite);
        }
    }

    private static final class PendingWrite {
        final Object flow;
        final String kind;
        final String site;
        final Object beforeValue;
        final String eventKey;

        PendingWrite(Object flow, String kind, String site, Object beforeValue) {
            this(flow, kind, site, beforeValue, null);
        }

        PendingWrite(Object flow, String kind, String site, Object beforeValue, String eventKey) {
            this.flow = flow;
            this.kind = kind;
            this.site = site;
            this.beforeValue = beforeValue;
            this.eventKey = eventKey;
        }
    }

    private enum NullValue { INSTANCE }
}
