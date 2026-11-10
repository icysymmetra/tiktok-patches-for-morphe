package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * The feed writes 1.5M under a video, so a filter on that number should take 1.5M. What it must
 * never do is take something it half understands: a value that quietly becomes the wrong number
 * hides videos nobody asked to hide, and there is no sign of it anywhere.
 */
public class CompactCountTest {
    @Test public void plainDigitsAreThemselves() {
        assertEquals(0, CompactCount.parse("0"));
        assertEquals(7, CompactCount.parse("7"));
        assertEquals(1_500_000, CompactCount.parse("1500000"));
        assertEquals(Long.MAX_VALUE, CompactCount.parse(String.valueOf(Long.MAX_VALUE)));
    }

    @Test public void theSuffixesAreTheOnesTheAppItselfUses() {
        assertEquals(20_000, CompactCount.parse("20k"));
        assertEquals(20_000, CompactCount.parse("20K"));
        assertEquals(1_500_000, CompactCount.parse("1.5M"));
        assertEquals(1_500_000, CompactCount.parse("1.5m"));
        assertEquals(2_000_000_000L, CompactCount.parse("2b"));
        assertEquals(2_000_000_000L, CompactCount.parse("2B"));
    }

    @Test public void aFractionIsExactRatherThanRounded() {
        // 1.234M is a real number of views, and turning it into 1.2M would filter the wrong ones.
        assertEquals(1_234_000, CompactCount.parse("1.234M"));
        assertEquals(1_100, CompactCount.parse("1.1K"));
        assertEquals(1_050, CompactCount.parse("1.05K"));
        assertEquals(500, CompactCount.parse(".5K"));
    }

    @Test public void whatCannotBeReadIsRefusedRatherThanGuessedAt() {
        // Every one of these has an obvious "helpful" reading, and every one of those readings
        // would silently change which videos are hidden.
        assertEquals(-1, CompactCount.parse("20x"));
        assertEquals(-1, CompactCount.parse("1.2.3M"));
        assertEquals(-1, CompactCount.parse("M"));
        assertEquals(-1, CompactCount.parse("-5"));
        assertEquals(-1, CompactCount.parse("1 000 K K"));
        assertEquals(-1, CompactCount.parse("twenty"));
        assertEquals(-1, CompactCount.parse(""));
        assertEquals(-1, CompactCount.parse(null));
        // A bare decimal is not a count of anything.
        assertEquals(-1, CompactCount.parse("1.5"));
        // More precision than the suffix can carry: a tenth of a view is not a view.
        assertEquals(-1, CompactCount.parse("1.2345K"));
    }

    @Test public void aNumberTooLargeToHoldIsRefusedRatherThanWrappingRound() {
        // Overflowing would turn a huge maximum into a negative one, which hides everything.
        assertEquals(-1, CompactCount.parse("99999999999999B"));
        assertEquals(-1, CompactCount.parse("9223372036854775808"));
    }

    @Test public void spacingAndGroupingAreToleratedBecauseTheyMeanNothing() {
        assertEquals(20_000, CompactCount.parse(" 20K "));
        assertEquals(1_500_000, CompactCount.parse("1,500,000"));
    }

    @Test public void aNumberReadsBackTheWayItWasWritten() {
        assertEquals("20K", CompactCount.format(20_000));
        assertEquals("1.5M", CompactCount.format(1_500_000));
        assertEquals("2B", CompactCount.format(2_000_000_000L));
        // A tenth is as fine as the short form goes, so 1500 is exactly 1.5K.
        assertEquals("1.5K", CompactCount.format(1500));
        // Nothing rounds: a number the short form cannot hold exactly stays as digits.
        assertEquals("1234", CompactCount.format(1234));
        assertEquals("1050", CompactCount.format(1050));
        assertEquals("999", CompactCount.format(999));
        assertEquals("0", CompactCount.format(0));
    }

    @Test public void everyShortFormReadsBackAsTheNumberItCameFrom() {
        long[] counts = {0, 1, 999, 1_000, 1_500, 20_000, 1_234_000, 1_500_000,
                2_000_000_000L, 12_300_000_000L};
        for (long count : counts) {
            assertEquals("round trip of " + count, count,
                    CompactCount.parse(CompactCount.format(count)));
        }
    }
}
