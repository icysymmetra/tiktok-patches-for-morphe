package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class InboxQueryTextTest {
    @Test public void bodyExpandsOneBasedNativeTextLinksWithoutIndexingTheirActions() {
        Template template = new Template();
        template.content = "[[text_link_2]] replied to [[text_link_1]]";
        template.contentTextLinkConfigs = Arrays.asList(new Link("your comment"), new Link("Alice"));
        template.schema = "private://do-not-search";
        template.buttonContent = "unrelated action";
        String text = InboxQueryText.textOfNotice(new Notice(template));
        assertTrue(text.contains("alice replied to your comment"));
        assertFalse(text.contains("text_link"));
        assertFalse(text.contains("private"));
        assertFalse(text.contains("unrelated action"));
    }

    @Test public void emptyBodyFallsBackToEventAndIncludesQuotesTitlesNamesAndUsernames() {
        Template template = new Template();
        template.content = "";
        template.event = "Mentioned you";
        template.quoteContent = "A quoted sentence";
        template.titleTemplate = new Title("Activity title", new User("Nickname", "user.handle"));
        String text = InboxQueryText.textOfNotice(new Notice(template));
        for (String part : Arrays.asList("mentioned you", "a quoted sentence", "activity title", "nickname", "user.handle")) {
            assertTrue(part, text.contains(part));
        }
        assertFalse("the numeric account ID isn't a search field", text.contains("secret-account-id"));
    }

    @Test public void onlyTheNativeSingleDigitPlaceholderGrammarIsExpanded() {
        Template template = new Template();
        template.content = "[[text_link_10]] [[text_link_1]]";
        template.contentTextLinkConfigs = Collections.nCopies(10, new Link("native link"));
        String text = InboxQueryText.textOfNotice(new Notice(template));
        assertTrue(text.contains("[[text_link_10]] native link"));
    }

    @Test public void nativeStructuredTextPairsAreReadThroughTheirNamedTypedPaths() {
        Template template = new Template();
        template.textPair = Collections.singletonList(new Pair(new Struct(new Config("Pair title")),
                new Struct(new Config("Pair body"), new Config("Rich span"))));
        String text = InboxQueryText.textOfNotice(new Notice(template));
        assertTrue(text.contains("pair title"));
        assertTrue(text.contains("pair body"));
        assertTrue(text.contains("rich span"));
        assertFalse(text.contains("schema-from-config"));
    }

    @Test public void unknownModelsAndMissingTemplatesDoNotBecomeArbitraryObjectStrings() {
        assertFalse(InboxQueryText.MATCHER.notification(new Object()));
        assertEquals("", InboxQueryText.textOfNotice(new Object() {
            @Override public String toString() { throw new AssertionError("No raw model strings"); }
        }));
    }

    @Test public void oversizedTextFailsTheTransactionInsteadOfSilentlyTruncatingMatches() {
        Template template = new Template();
        template.content = String.join("", Collections.nCopies(16_385, "x"));
        try { InboxQueryText.textOfNotice(new Notice(template)); fail("oversized native text must refuse projection"); }
        catch (IllegalStateException expected) { assertFalse(expected.getMessage().contains(template.content)); }
        template.content = "";
        template.titleTemplate = new Title("", new User("", ""));
        template.titleTemplate.fromUsers = Collections.nCopies(129, new User("Name", "handle"));
        try { InboxQueryText.textOfNotice(new Notice(template)); fail("oversized native parts must refuse projection"); }
        catch (IllegalStateException expected) { /* The caller restores/refuses a query atomically. */ }
    }

    public static final class Notice {
        public final General templateNotice;
        Notice(Template template) { templateNotice = new General(template); }
    }
    public static final class General {
        public final Template uiTemplate;
        General(Template template) { uiTemplate = template; }
    }
    public static final class Template {
        public String content, event, quoteContent, schema, buttonContent;
        public Title titleTemplate;
        public List<Link> contentTextLinkConfigs;
        public List<Pair> textPair;
    }
    public static final class Link {
        public String text;
        public String schema = "private-action";
        Link(String text) { this.text = text; }
    }
    public static final class Title {
        public String title;
        public List<User> fromUsers;
        Title(String title, User user) { this.title = title; fromUsers = Collections.singletonList(user); }
    }
    public static final class User {
        final String nickname, username;
        public String uid = "secret-account-id";
        User(String nickname, String username) { this.nickname = nickname; this.username = username; }
        public String getNickname() { return nickname; }
        public String getUniqueId() { return username; }
    }
    public static final class Pair {
        public Struct title, content;
        Pair(Struct title, Struct content) { this.title = title; this.content = content; }
    }
    public static final class Struct {
        public Config content;
        public List<Config> textConfigs;
        Struct(Config content, Config... spans) { this.content = content; textConfigs = Arrays.asList(spans); }
    }
    public static final class Config {
        public String text, schema = "schema-from-config";
        Config(String text) { this.text = text; }
    }
}
