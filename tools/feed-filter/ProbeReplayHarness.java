package app.morphe.extension.tiktok.feedfilter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ProbeReplayHarness {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            throw new IllegalArgumentException("usage: ProbeReplayHarness <first.tsv> <positive-count> <second.tsv> <positive-count>");
        }
        replay(Path.of(args[0]), Integer.parseInt(args[1]), true);
        replay(Path.of(args[2]), Integer.parseInt(args[3]), false);
        System.out.println("ProbeReplayHarness OK");
    }

    private static void replay(Path path, int expectedPositive, boolean requireType2AndCreated) throws Exception {
        List<String> lines = Files.readAllLines(path);
        int positives = 0;
        for (int index = 1; index < lines.size(); index++) {
            String[] fields = lines.get(index).split("\\t", -1);
            int result = AiContentClassifier.classify(
                Integer.parseInt(fields[1]),
                Boolean.parseBoolean(fields[2]),
                Boolean.parseBoolean(fields[3]),
                Integer.parseInt(fields[4])
            );
            if (!AiContentClassifier.removes(result)) continue;
            positives++;
            if (requireType2AndCreated) {
                check((result & AiContentClassifier.TIKTOK_AI_LABEL) != 0, "positive missing type-2 bit");
                check((result & AiContentClassifier.CREATED_BY_AI) != 0, "positive missing createdByAI bit");
            }
        }
        check(positives == expectedPositive,
            path + ": expected positives=" + expectedPositive + " actual=" + positives);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
