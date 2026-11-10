/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import android.content.Context;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SeekbarHandleTest {
    private LinearLayout bar;
    private float density;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Settings.SEEKBAR_BIG_HANDLE.save(false);
        PausedProcess.set(false);
        bar = new LinearLayout(context);
        density = context.getResources().getDisplayMetrics().density;
    }

    @After
    public void tearDown() {
        Settings.SEEKBAR_BIG_HANDLE.save(false);
        PausedProcess.set(false);
        SeekbarHandle.beginStyle(bar, SeekbarHandle.STYLE_REST);
    }

    private Float dp(float value) {
        return value * density;
    }

    @Test
    public void atRestTheHandleGrowsRoundAndTheLineThickens() {
        Settings.SEEKBAR_BIG_HANDLE.save(true);
        SeekbarHandle.beginStyle(bar, SeekbarHandle.STYLE_REST);
        // TikTok's rest style: a 4dp handle with a 2dp corner on a 2dp line.
        assertEquals(12f * density, SeekbarHandle.handleSide(dp(4f)), 0.001f);
        assertEquals(6f * density, SeekbarHandle.handleCorner(dp(2f)), 0.001f);
        assertEquals(3f * density, SeekbarHandle.lineHeight(dp(2f)), 0.001f);
    }

    @Test
    public void pausedTheHandleGrowsAndTikToksThickerLineStays() {
        Settings.SEEKBAR_BIG_HANDLE.save(true);
        SeekbarHandle.beginStyle(bar, SeekbarHandle.STYLE_PAUSED);
        // TikTok's paused style: an 8dp handle with a 4dp corner on a 4dp line.
        assertEquals(12f * density, SeekbarHandle.handleSide(dp(8f)), 0.001f);
        assertEquals(6f * density, SeekbarHandle.handleCorner(dp(4f)), 0.001f);
        Float line = dp(4f);
        assertSame(line, SeekbarHandle.lineHeight(line));
    }

    @Test
    public void dragAndOtherStylesKeepTikToksSizes() {
        Settings.SEEKBAR_BIG_HANDLE.save(true);
        Float handle = dp(4f);
        Float line = dp(2f);
        for (int style : new int[]{2, 3, 4, 100, 101, 102}) {
            SeekbarHandle.beginStyle(bar, style);
            assertSame("style " + style, handle, SeekbarHandle.handleSide(handle));
            assertSame("style " + style, handle, SeekbarHandle.handleCorner(handle));
            assertSame("style " + style, line, SeekbarHandle.lineHeight(line));
        }
    }

    @Test
    public void nothingIsMadeSmaller() {
        Settings.SEEKBAR_BIG_HANDLE.save(true);
        SeekbarHandle.beginStyle(bar, SeekbarHandle.STYLE_REST);
        Float big = dp(20f);
        assertSame(big, SeekbarHandle.handleSide(big));
        assertSame(big, SeekbarHandle.handleCorner(big));
        assertSame(big, SeekbarHandle.lineHeight(big));
    }

    @Test
    public void offOrPausedEveryValueGoesThroughAsTikTokWroteIt() {
        Float small = dp(4f);
        SeekbarHandle.beginStyle(bar, SeekbarHandle.STYLE_REST);
        assertSame(small, SeekbarHandle.handleSide(small));
        assertSame(small, SeekbarHandle.lineHeight(small));

        Settings.SEEKBAR_BIG_HANDLE.save(true);
        PausedProcess.set(true);
        SeekbarHandle.beginStyle(bar, SeekbarHandle.STYLE_REST);
        assertSame(small, SeekbarHandle.handleSide(small));
        assertSame(small, SeekbarHandle.handleCorner(small));
        assertSame(small, SeekbarHandle.lineHeight(small));
    }

    @Test
    public void aRestStyleDoesNotCarryIntoTheDragThatFollows() {
        Settings.SEEKBAR_BIG_HANDLE.save(true);
        Float small = dp(4f);
        SeekbarHandle.beginStyle(bar, SeekbarHandle.STYLE_REST);
        assertEquals(12f * density, SeekbarHandle.handleSide(small), 0.001f);
        SeekbarHandle.beginStyle(bar, 100);
        assertSame(small, SeekbarHandle.handleSide(small));
    }

    @Test
    public void aMissingValueStaysMissing() {
        Settings.SEEKBAR_BIG_HANDLE.save(true);
        SeekbarHandle.beginStyle(bar, SeekbarHandle.STYLE_REST);
        assertNull(SeekbarHandle.handleSide(null));
        assertNull(SeekbarHandle.handleCorner(null));
        assertNull(SeekbarHandle.lineHeight(null));
        SeekbarHandle.beginStyle(null, SeekbarHandle.STYLE_REST);
        Float small = dp(4f);
        assertSame("no bar, no restyle", small, SeekbarHandle.handleSide(small));
    }
}
