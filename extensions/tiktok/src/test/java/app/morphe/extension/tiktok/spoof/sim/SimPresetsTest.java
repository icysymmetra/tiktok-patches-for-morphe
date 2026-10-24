package app.morphe.extension.tiktok.spoof.sim;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.junit.Test;

/** Null, whitespace and validity boundaries for the bundled SIM presets. */
public class SimPresetsTest {
    @Test
    public void everyBundledPresetIsValidAndMatchesTrimmedCurrentValues() {
        for (SimPreset preset : SimPresets.PRESETS) {
            assertTrue(preset.country, preset.isValid());
            assertSame(preset, SimPresets.findSelected(
                    " " + preset.iso + " ", " " + preset.mccMnc + " ",
                    " " + preset.operatorName + " "));
        }
    }

    @Test
    public void everyPresetZoneIsARealZoneAndTheBalkansAreListed() {
        Set<String> isos = new HashSet<>();
        // RegionSpoof.validCountry takes only these, so a preset outside them couldn't be saved.
        Set<String> known = new HashSet<>(Arrays.asList(Locale.getISOCountries()));
        for (SimPreset preset : SimPresets.PRESETS) {
            assertEquals(preset.country, preset.timeZone, TimeZone.getTimeZone(preset.timeZone).getID());
            assertTrue("one preset per country: " + preset.iso, isos.add(preset.iso));
            assertTrue("an ISO country: " + preset.iso, known.contains(preset.iso.toUpperCase(Locale.ROOT)));
        }
        for (String iso : new String[]{"rs", "hr", "ba", "si", "me", "mk", "al", "bg"}) {
            assertTrue("#198 preset " + iso, isos.contains(iso));
        }
        SimPreset serbia = SimPresets.findSelected("rs", "22003", "mt:s");
        assertEquals("Europe/Belgrade", serbia.timeZone);
        assertTrue(serbia.matches("serbia"));
    }

    @Test
    public void missingCurrentValuesAreTreatedAsEmptyInsteadOfCrashing() {
        assertTrue(SimPresets.hasEmptyCurrentValues(null, null, null));
        assertFalse(SimPresets.hasEmptyCurrentValues("us", null, null));
        assertNull(SimPresets.findSelected(null, null, null));
        assertNull(SimPresets.findSelected("us", null, "T-Mobile"));
    }
}
