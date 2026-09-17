package dev.flowgraph.runtime;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

    /** Runtime object -> source property keys. Identity semantics are required for Flow objects. */
    private static final IdentityHashMap<Object, LinkedHashSet<String>> KEYS_BY_OBJECT = new IdentityHashMap<>();

    /** Collector identity -> Flow keys whose collect() call supplied that collector. */
    private static final IdentityHashMap<Object, CollectorBinding> COLLECTOR_BINDINGS = new IdentityHashMap<>();

    private static final Map<String, Object> LAST_VALUE_BY_KEY = new ConcurrentHashMap<>();
    private static final Set<String> REGISTERED_KEYS = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Map<String, Long> LAST_READ_BY_SITE = new ConcurrentHashMap<>();

    private static final ThreadLocal<java.util.ArrayDeque<PendingWrite>> PENDING_WRITES =
            new ThreadLocal<java.util.ArrayDeque<PendingWrite>>() {
                @Override protected java.util.ArrayDeque<PendingWrite> initialValue() {
                    return new java.util.ArrayDeque<>();
                }
            };

    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlowGraphTrace");
        t.setDaemon(true);
        return t;
    });

    private static volatile Socket socket;
    private static volatile BufferedWriter writer;
    private static volatile boolean enabled = true;
    private static volatile boolean logEvents = true;
    private static volatile String host = "127.0.0.1";
    private static volatile int port = 50737;
    private static volatile int connectTimeoutMs = 2_000;

    private FlowGraphAutoRuntime() {}

    public static void configure(boolean isEnabled, boolean shouldLogEvents, int tracePort) {
        enabled = isEnabled;
        logEvents = shouldLogEvents;
        port = tracePort;
    }

    /**
     * Called by instrumented Flow-typed field reads/writes. Generic cold Flow objects are associated
     * only with their exact property object. StateFlow/SharedFlow wrappers are additionally walked a
     * few levels so readonly wrappers still correlate with their mutable implementation object.
     */
    public static void registerFlow(Object flow, String runtimeKey) {
        if (!enabled || flow == null || runtimeKey == null || runtimeKey.length() == 0) return;
        if (!looksLikeAnyFlow(flow)) return;

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

        if (REGISTERED_KEYS.add(runtimeKey)) {
            Object current = readValue(flow);
            if (current != NO_VALUE) {
                LAST_VALUE_BY_KEY.put(runtimeKey, normalize(current));
                emit(runtimeKey, "initial", current, "auto-register", Collections.<String>emptySet());
            }
            logInfo(
                    "Auto-registered Flow: " + runtimeKey + " (" + flow.getClass().getName() + ")"
                            + (firstObjectAssociation ? "" : " [alias]")
            );
        }
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
        PENDING_WRITES.get().push(new PendingWrite(flow, kind, site));
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

        for (String key : keys) {
            Object previous = LAST_VALUE_BY_KEY.put(key, normalizedCurrent);
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

    /** Called before an instrumented StateFlow.getValue(). */
    public static void recordRead(Object flow, String site) {
        if (!enabled || flow == null) return;
        List<String> keys = keysFor(flow);
        if (keys.isEmpty()) return;

        long now = System.nanoTime();
        for (String key : keys) {
            String throttleKey = key + "@" + site;
            Long previous = LAST_READ_BY_SITE.put(throttleKey, now);
            // Reads can be extremely hot (Compose in particular). Keep the graph useful instead of
            // turning the socket into a profiler firehose.
            if (previous != null && now - previous < 25_000_000L) continue;
            emit(key, "read", null, site, Collections.<String>emptySet());
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
                || "kotlinx.coroutines.flow.MutableSharedFlow".equals(name)) {
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

    private static Object readValue(Object flow) {
        try {
            Method method = flow.getClass().getMethod("getValue");
            method.setAccessible(true);
            return method.invoke(flow);
        } catch (Throwable ignored) {
            return NO_VALUE;
        }
    }

    private static void emit(String stateKey, String kind, Object value, String site, Set<String> fields) {
        if (!enabled) return;
        final String valueSummary = summarize(value);
        IO.execute(() -> {
            try {
                BufferedWriter out = ensureWriter();
                String line = "FG1\t"
                        + System.nanoTime() + "\t"
                        + enc(stateKey) + "\t"
                        + enc(kind) + "\t"
                        + enc(valueSummary) + "\t"
                        + enc(site == null ? "" : site) + "\t"
                        + enc(join(fields));
                out.write(line);
                out.newLine();
                out.flush();
                if (logEvents) logDebug("SEND " + kind + " " + stateKey + (value == null ? "" : " = " + valueSummary));
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
            logInfo("Connected to Flow Graph live trace at " + host + ":" + port + " (deep automatic instrumentation)");
            return writer;
        }
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
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
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
        PendingWrite(Object flow, String kind, String site) {
            this.flow = flow;
            this.kind = kind;
            this.site = site;
        }
    }

    private enum NullValue { INSTANCE }
}
