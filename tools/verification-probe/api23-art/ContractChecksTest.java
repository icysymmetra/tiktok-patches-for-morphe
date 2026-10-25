package app.hushfeed.verification.api23;

public final class ContractChecksTest {
    private static int checks;
    public static void main(String[] args) {
        ContractChecks complete = allExcept(null);
        require(complete.accepted(23, true, true, true, false), "all actual operations accept only with qualified native execution");
        for (String missing : ContractChecks.REQUIRED) require(!allExcept(missing).accepted(23, true, true, true, false),
                "missing member operation or negative control cannot pass: " + missing);
        require(!complete.accepted(24, true, true, true, false), "a newer ART runtime cannot qualify API23");
        require(!complete.accepted(23, false, true, true, false), "a synthetic or host VM cannot qualify ART");
        require(!complete.accepted(23, true, false, true, false), "different installed payload cannot qualify");
        require(!complete.accepted(23, true, true, false, false), "incomplete settings restoration cannot qualify");
        require(!complete.accepted(23, true, true, true, true), "a captured native failure cannot qualify");
        try { complete.pass("caption-layout"); throw new AssertionError("duplicate operation accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { complete.pass("fake-caption"); throw new AssertionError("unknown operation accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        System.out.println("[api23-art] " + checks + " receipt contracts passed");
    }
    private static ContractChecks allExcept(String missing) {
        ContractChecks result = new ContractChecks();
        for (String name : ContractChecks.REQUIRED) if (!name.equals(missing)) result.pass(name);
        return result;
    }
    private static void require(boolean valid, String message) { checks++; if (!valid) throw new AssertionError(message); }
}
