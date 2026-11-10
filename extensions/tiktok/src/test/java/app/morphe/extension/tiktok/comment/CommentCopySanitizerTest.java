package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentCopySanitizerTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    /** The comment menu since 46.9.3 hands the name prefix to a ClipData builder apart from the text (issue #28). */
    @Test public void theMenuPrefixIsBlankedWithTheSwitchOnAndKeptWithItOff() {
        Settings.COPY_COMMENTS_WITHOUT_USERNAME.save(true);
        assertEquals("", CommentCopySanitizer.copiedPrefix("@someone: "));
        Settings.COPY_COMMENTS_WITHOUT_USERNAME.save(false);
        assertEquals("@someone: ", CommentCopySanitizer.copiedPrefix("@someone: "));
    }

    @Test public void theJoinedStringRouteStillGivesTheTextAlone() {
        Settings.COPY_COMMENTS_WITHOUT_USERNAME.save(true);
        assertEquals("the text", CommentCopySanitizer.sanitizeCopiedCommentText("@someone: the text", "the text"));
        assertEquals("kept when the text is unknown", CommentCopySanitizer.sanitizeCopiedCommentText("kept when the text is unknown", null));
        Settings.COPY_COMMENTS_WITHOUT_USERNAME.save(false);
        assertEquals("@someone: the text", CommentCopySanitizer.sanitizeCopiedCommentText("@someone: the text", "the text"));
    }
}
