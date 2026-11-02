package com.ss.android.ugc.aweme.translation.service;

/**
 * Stands in for TikTok's language service, in the shape a build that carries nothing useful has.
 *
 * <p>No method returns a String, and no method returns something with
 * {@code getSelectedDoNotTranslateLanguageCodes}, so both lookups in the translator come away
 * empty. That is the case worth pinning: the translator used to remember only a successful
 * lookup, so a build like this one was constructed again for every comment.
 */
public final class TranslationLangKevaServiceImpl {
    /** How many times the translator has built this. */
    public static int constructions;
    /** How many times a method that is not a do-not-translate provider was called anyway. */
    public static int strayCalls;

    public TranslationLangKevaServiceImpl() {
        constructions++;
    }

    /**
     * A no-argument method returning something the translator has no use for.
     *
     * <p>{@link Object} has no {@code getSelectedDoNotTranslateLanguageCodes}, so the translator
     * has no reason to call this, and calling it counts as reaching into the host blind.
     */
    public Object getSomethingElse() {
        strayCalls++;
        return new Object();
    }

    public static void reset() {
        constructions = 0;
        strayCalls = 0;
    }
}
