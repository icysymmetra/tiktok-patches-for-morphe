package app.hushfeed.verification.api23;

import app.morphe.extension.shared.diagnostics.HookStatus;

/** Uses the compiled production HookStatus, without replacement family or snapshot implementations. */
public final class HookBindingsTest {
    private static final String[][] REQUIRED = {
            {"captions", "47.4.3:dm5"}, {"captions", "47.4.3:dly"}, {"main feed", "FeedItemList.getItems"}};
    private static int checks;

    public static void main(String[] args) throws Exception {
        try {
            bindExcept(-1);
            String evidence = HookBindings.require(HookStatus.snapshot());
            require(evidence.contains("47.4.3:dm5") && evidence.contains("47.4.3:dly")
                    && evidence.contains("FeedItemList.getItems"), "receipt omits exact positive bindings");
            String report = HookStatus.report().toString();
            require(report.contains("captions") && !report.contains("47.4.3:dm5")
                    && !report.contains("FeedItemList.getItems"), "production report should expose counts, not bound names");
            for (int i = 0; i < REQUIRED.length; i++) {
                bindExcept(i);
                reject(() -> HookBindings.require(HookStatus.snapshot()));
            }
            bindExcept(0);
            HookStatus.bound("captions", "47.4.3:dm5-extra");
            reject(() -> HookBindings.require(HookStatus.snapshot()));
            bindExcept(0);
            HookStatus.bound("main feed", "47.4.3:dm5");
            reject(() -> HookBindings.require(HookStatus.snapshot()));
            bindExcept(0);
            HookStatus.Snapshot before = HookStatus.snapshot();
            HookStatus.bound("captions", "47.4.3:dm5");
            reject(() -> HookBindings.require(before));
            require(HookBindings.require(HookStatus.snapshot()).contains("47.4.3:dm5"), "fresh snapshot omitted new binding");
            reject(() -> HookBindings.require(null));
            reject(() -> HookBindings.require(new Object()));
            System.out.println("[api23-art] " + checks + " actual HookStatus binding contracts passed");
        } finally { HookStatus.clear(); }
    }

    private static void bindExcept(int omitted) {
        HookStatus.clear();
        for (int i = 0; i < REQUIRED.length; i++) if (i != omitted) HookStatus.bound(REQUIRED[i][0], REQUIRED[i][1]);
    }
    private interface Work { void run() throws Exception; }
    private static void reject(Work work) throws Exception {
        try { work.run(); throw new IllegalStateException("invalid positive binding evidence accepted"); }
        catch (AssertionError expected) { checks++; }
    }
    private static void require(boolean value, String message) { checks++; if (!value) throw new AssertionError(message); }
}
