package app.hushfeed.verification.api23;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Member evidence is complete only when every native operation and negative control ran. */
final class ContractChecks {
    static final Set<String> REQUIRED = new HashSet<>(Arrays.asList(
            "caption-disabled", "caption-layout", "caption-native-views", "caption-restored",
            "empty-cached-batch", "offline-disabled", "offline-emptied", "feed-hook-status",
            "map-default-negative", "list-sort-negative"));
    private final Set<String> passed = new HashSet<>();
    void pass(String name) {
        if (!REQUIRED.contains(name) || !passed.add(name)) throw new IllegalArgumentException("unknown or duplicate contract: " + name);
    }
    boolean accepted(int sdk, boolean art, boolean payloadIdentity, boolean restored, boolean failed) {
        return sdk == 23 && art && payloadIdentity && restored && !failed && passed.equals(REQUIRED);
    }
}
