/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.featuregatelab;

/**
 * Why a Lab rule was not used, with the offending value kept beside the reason.
 *
 * <p>A model-layer refusal, carrying no wording tied to the reader's language. The screen turns
 * it into a sentence through {@link FeatureGateLabText}; {@link #text()} is the English line the
 * log carries, which is where these reasons lived when they were raw concatenations.
 */
public final class FeatureGateFailure {
    public enum Reason {
        /** TikTok has not handed the getter an object of the class the override patches. */
        NO_OBJECT,
        /** The configuration class has no constructor the Lab can copy through. */
        CANNOT_COPY,
        /** A root-array override with no value under {@code $value}. */
        NO_LIST_VALUE,
        /** The override names a field this build does not have, or cannot write. */
        UNSUPPORTED_FIELD,
        /** The override patches nothing. */
        NO_FIELDS,
        /** Reflection threw while copying or coercing. */
        THREW,
        /** The key is not in the local catalogue, so the rule's type cannot be checked. */
        NOT_IN_CATALOGUE,
        /** The catalogue's type for the key and the rule's type disagree. */
        TYPE_MISMATCH
    }

    public final Reason reason;
    /** The field name, the catalogue's type, or the throwable's own text. Never null. */
    public final String detail;
    /** The rule's type, for {@link Reason#TYPE_MISMATCH}. Never null. */
    public final String other;

    private FeatureGateFailure(Reason reason, String detail, String other) {
        this.reason = reason;
        this.detail = detail == null ? "" : detail;
        this.other = other == null ? "" : other;
    }

    static FeatureGateFailure of(Reason reason) {
        return new FeatureGateFailure(reason, "", "");
    }

    static FeatureGateFailure of(Reason reason, String detail) {
        return new FeatureGateFailure(reason, detail, "");
    }

    static FeatureGateFailure of(Reason reason, String detail, String other) {
        return new FeatureGateFailure(reason, detail, other);
    }

    /** The line the log carries, unchanged from when these reasons were built by hand. */
    public String text() {
        switch (reason) {
            case NO_OBJECT:
                return "TikTok returned no compatible object";
            case CANNOT_COPY:
                return "Configuration class cannot be copied safely";
            case NO_LIST_VALUE:
                return "No array value selected";
            case UNSUPPORTED_FIELD:
                return "Unsupported field: " + detail;
            case NO_FIELDS:
                return "No editable fields selected";
            case NOT_IN_CATALOGUE:
                return "Not in the catalogue, so the type cannot be checked";
            case TYPE_MISMATCH:
                return "Catalogue says " + detail + ", this rule is " + other;
            case THREW:
            default:
                return detail;
        }
    }
}
