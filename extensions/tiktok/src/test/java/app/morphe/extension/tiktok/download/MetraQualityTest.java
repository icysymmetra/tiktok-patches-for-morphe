package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest.Gear;
import java.util.List;
import org.junit.Test;

public class MetraQualityTest {
    @Test public void balancedKeepsTheMedianMeaningWithoutChoosingAnUnplayableFile() {
        Gear small = new Gear("normal_360_0", 100, "https://example.com/360");
        Gear medium = new Gear("normal_720_0", 200, "https://example.com/720");
        Gear large = new Gear("normal_1080_0", 300, "https://example.com/1080");
        Gear bvc = new Gear("normal_2160_2", 500, "https://example.com/2160").codec(2);
        Gear missing = new Gear("normal_4320_0", 900, null);
        assertSame(medium, QualitySelector.chooseForFile(List.of(large, bvc, small, missing, medium), "medium"));
        assertSame(medium, QualitySelector.chooseForFile(List.of(small, medium), "medium"));
        assertNull(QualitySelector.chooseForFile(List.of(bvc, missing), "medium"));
        assertNull(QualitySelector.chooseForFile(List.of(small), "invalid"));
    }
}
