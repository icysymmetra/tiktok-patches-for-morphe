package app.morphe.extension.shared.diagnostics;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded GC policy requests. No files, logger, target initialization or native calls here. */
public final class PolicyObservation {
    private static final String[] NAMES = {"gc_config", "gc_start", "gc_end", "young_native"};
    private static final AtomicIntegerArray CALLS = new AtomicIntegerArray(4), BYPASSES = new AtomicIntegerArray(4);
    private static final AtomicReferenceArray<Call> FIRST = new AtomicReferenceArray<>(4);
    private static final AtomicInteger YOUNG = new AtomicInteger(), YOUNG_BYPASS = new AtomicInteger();
    private static final AtomicReference<Long> YOUNG_FIRST = new AtomicReference<>();
    private static final AtomicIntegerArray REFERENCES = new AtomicIntegerArray(2);
    private PolicyObservation() {}
    /** Patched constant. The probes and bridge bodies are otherwise the same in M and G. */
    public static boolean bypassEnabled() { return false; }
    public static boolean referenceRequest(int kind) {
        boolean bypass = bypassEnabled();
        try { if (kind >= 0 && kind < 2 && REFERENCES.get(kind) < 2) { tick(REFERENCES,kind); RecoveryTimeline.requestReading(); } }
        catch (Throwable ignored) { /* Preserve the selected policy even if evidence fails. */ }
        return bypass;
    }
    public static boolean youngRequest(double value) {
        boolean bypass = bypassEnabled();
        try {
            tick(YOUNG); if (bypass) tick(YOUNG_BYPASS);
            if (YOUNG_FIRST.get() == null && YOUNG_FIRST.compareAndSet(null, Double.doubleToRawLongBits(value))) RecoveryTimeline.requestReading();
        } catch (Throwable ignored) { /* Evidence failure cannot enable a suppressed native policy. */ }
        return bypass;
    }
    public static int enterConfig(int sdk, long clearLimit, long typeLimit) { return enter(0, sdk, clearLimit, typeLimit, 0, 0); }
    public static int enterStart(int sdk, boolean clear, long clearLimit, boolean type, long typeLimit) {
        return enter(1, sdk, clearLimit, typeLimit, clear ? 1 : 0, type ? 1 : 0);
    }
    public static int enterEnd(int sdk, boolean clear, boolean type) { return enter(2, sdk, 0, 0, clear ? 1 : 0, type ? 1 : 0); }
    public static int enterYoung(double value) { return enter(3, 0, Double.doubleToRawLongBits(value), 0, 0, 0); }
    private static int enter(int kind, long a, long b, long c, long d, long e) {
        return record(kind, a, b, c, d, e, bypassEnabled());
    }
    static int record(int kind, long a, long b, long c, long d, long e, boolean bypass) {
        try {
            tick(CALLS, kind); if (bypass) tick(BYPASSES, kind);
            if (FIRST.get(kind) != null) return 0;
            if (!FIRST.compareAndSet(kind, null, new Call(a,b,c,d,e,bypass))) return 0;
            RecoveryTimeline.requestReading(); return kind + 1;
        } catch (Throwable ignored) { return 0; }
    }
    public static void returned(int token, int result) {
        try {
            if (token < 1 || token > 4) return;
            Call c = FIRST.get(token - 1); if (c == null || c.phase != 1) return;
            c.result = result; c.phase = 2; RecoveryTimeline.requestReading();
        } catch (Throwable ignored) { /* Preserve native result. */ }
    }
    private static final class Call {
        final long a,b,c,d,e; volatile int phase, result;
        Call(long a,long b,long c,long d,long e,boolean bypass) {
            this.a=a;this.b=b;this.c=c;this.d=d;this.e=e;phase=bypass?3:1;
        }
    }
    // Counts saturate at "multiple", bounding both metadata and receipt updates over a long run.
    private static void tick(AtomicInteger n) { int v; do {v=n.get();if(v>=2)return;} while(!n.compareAndSet(v,v+1)); }
    private static void tick(AtomicIntegerArray n,int i) { int v; do {v=n.get(i);if(v>=2)return;} while(!n.compareAndSet(i,v,v+1)); }
    private static String count(int n) { return n < 2 ? Integer.toString(n) : "multiple"; }
    static String state() {
        StringBuilder out = new StringBuilder("policy_mode=").append(bypassEnabled()?"bypass":"observe")
            .append(" reference_requests=").append(count(REFERENCES.get(0))).append(',').append(count(REFERENCES.get(1)))
            .append(" young_requests=").append(count(YOUNG.get())).append(" young_request_bypassed=").append(count(YOUNG_BYPASS.get()));
        Long first=YOUNG_FIRST.get();if(first!=null)out.append(" young_request_hex=").append(ControlObservation.bits(first));
        for(int i=0;i<4;i++) {
            out.append(' ').append(NAMES[i]).append("_calls=").append(count(CALLS.get(i)))
                .append(" bypassed=").append(count(BYPASSES.get(i)));
            Call c=FIRST.get(i);if(c==null)continue;int phase=c.phase;
            if(i==3)out.append(" u_hex=").append(ControlObservation.bits(c.b));
            else {
                out.append(" sdk=").append(c.a);
                if(i!=2)out.append(" clear_hex=").append(ControlObservation.bits(c.b)).append(" type_hex=").append(ControlObservation.bits(c.c));
                if(i!=0)out.append(" flags=").append(c.d).append(',').append(c.e);
            }
            out.append(" phase=").append(phase==3?"bypassed":phase==2?"returned":"native_pending");
            if(phase==2)out.append(" result_hex=").append(ControlObservation.bits(c.result));
        }
        return out.append(" first_per_kind=true").toString();
    }
    static void resetForTests() {
        YOUNG.set(0);YOUNG_BYPASS.set(0);YOUNG_FIRST.set(null);
        REFERENCES.set(0,0);REFERENCES.set(1,0);
        for(int i=0;i<4;i++){CALLS.set(i,0);BYPASSES.set(i,0);FIRST.set(i,null);}
    }
}
