/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.popups;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.compliance.api.model.UserDetailsInfoBean;
import com.ss.android.ugc.aweme.compliance.api.services.teenmode.IProtectionService;
import com.ss.android.ugc.aweme.compliance.protection.familypairing.FamilyPairingManagerV2;
import com.ss.android.ugc.aweme.compliance.protection.timelock.ui.assem.STMDailyScreenTimeTrigger;
import com.ss.android.ugc.aweme.compliance.protection.timelock.ui.assem.STMMeditationTrigger;
import com.ss.android.ugc.aweme.framework.services.PluggableExtentionKt;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Block popups' switch for TikTok's wind-down, breathing exercise and daily limit screens. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class WindDownScreensTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** The protection service as TikTok's lookup hands it out, with the details it has saved. */
    private static final class Service implements IProtectionService {
        UserDetailsInfoBean saved;

        Service(UserDetailsInfoBean saved) {
            this.saved = saved;
        }

        @Override public UserDetailsInfoBean details() {
            return saved;
        }

        @Override public boolean sleepHourEnabled() {
            return true;
        }
    }

    private final Object meditation = new STMMeditationTrigger();
    private final Object dailyLimit = new STMDailyScreenTimeTrigger();

    @Before @After public void reset() {
        PausedProcess.set(false);
        Settings.HIDE_WIND_DOWN_SCREENS.resetToDefault();
        Settings.LEAVE_ON_REST_REMINDER.resetToDefault();
        WindDownScreens.resetForTests();
        SignedInUser.idForTests = null;
        FamilyPairingManagerV2.current = FamilyPairingManagerV2.Role.NONE;
        PluggableExtentionKt.service = null;
        PluggableExtentionKt.lookups = 0;
    }

    private static void adult(boolean adult) {
        WindDownScreens.adultCheck = () -> adult;
    }

    @Test public void offLeavesEveryScreenAlone() {
        adult(true);
        assertTrue(WindDownScreens.eligible(meditation, true));
        assertTrue(WindDownScreens.eligible(dailyLimit, true));
    }

    @Test public void onKeepsTheScreensBackOnAnAdultsAccount() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        adult(true);
        assertFalse(WindDownScreens.eligible(meditation, true));
        assertFalse(WindDownScreens.eligible(dailyLimit, true));
    }

    @Test public void anAccountTikTokDoesNotCallAnAdultsKeepsThem() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        adult(false);
        assertTrue(WindDownScreens.eligible(meditation, true));
        assertTrue(WindDownScreens.eligible(dailyLimit, true));
    }

    @Test public void aNoFromTikTokStaysANo() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        adult(true);
        assertFalse(WindDownScreens.eligible(meditation, false));
        Settings.HIDE_WIND_DOWN_SCREENS.save(false);
        assertFalse(WindDownScreens.eligible(meditation, false));
        assertFalse(WindDownScreens.eligible(null, false));
        assertTrue(WindDownScreens.eligible(null, true));
    }

    @Test public void theLeaveSwitchKeepsTheDailyLimitScreenOnly() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        Settings.LEAVE_ON_REST_REMINDER.save(true);
        adult(true);
        assertTrue("the leave switch acts on the daily limit screen", WindDownScreens.eligible(dailyLimit, true));
        assertFalse(WindDownScreens.eligible(meditation, true));
        assertEquals(STMDailyScreenTimeTrigger.class.getName(), WindDownScreens.DAILY_LIMIT_TRIGGER);
    }

    @Test public void pauseBringsTheScreensBack() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        adult(true);
        PausedProcess.set(true);
        assertTrue(WindDownScreens.eligible(meditation, true));
    }

    @Test public void aFailingCheckLeavesTheScreen() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        WindDownScreens.adultCheck = () -> {
            throw new IllegalStateException("the host isn't ready");
        };
        assertTrue(WindDownScreens.eligible(meditation, true));
    }

    @Test public void onlyANotPairedOrParentRoleWithASavedNoCounts() throws Exception {
        UserDetailsInfoBean grownUp = new UserDetailsInfoBean(Boolean.FALSE);
        assertTrue(WindDownScreens.adultFrom(FamilyPairingManagerV2.Role.NONE, grownUp));
        assertTrue(WindDownScreens.adultFrom(FamilyPairingManagerV2.Role.PARENT, grownUp));
        assertFalse(WindDownScreens.adultFrom(FamilyPairingManagerV2.Role.CHILD, grownUp));
        assertFalse(WindDownScreens.adultFrom(FamilyPairingManagerV2.Role.UNLINK_LOCKED, grownUp));
        assertFalse(WindDownScreens.adultFrom("NONE", grownUp));
        assertFalse(WindDownScreens.adultFrom(null, grownUp));
        assertFalse(WindDownScreens.adultFrom(FamilyPairingManagerV2.Role.NONE, new UserDetailsInfoBean(Boolean.TRUE)));
        assertFalse("nothing saved yet", WindDownScreens.adultFrom(FamilyPairingManagerV2.Role.NONE, new UserDetailsInfoBean(null)));
        assertFalse(WindDownScreens.adultFrom(FamilyPairingManagerV2.Role.NONE, null));
    }

    @Test public void tiktoksOwnAnswersAreReadByTheirRealNames() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        SignedInUser.idForTests = "7000000000000000001";
        Service service = new Service(new UserDetailsInfoBean(Boolean.FALSE));
        PluggableExtentionKt.service = service;
        assertFalse("an adult outside Family Pairing", WindDownScreens.eligible(meditation, true));

        FamilyPairingManagerV2.current = FamilyPairingManagerV2.Role.PARENT;
        assertFalse("a parent's own account", WindDownScreens.eligible(meditation, true));
        FamilyPairingManagerV2.current = FamilyPairingManagerV2.Role.CHILD;
        assertTrue("a teen's side of Family Pairing", WindDownScreens.eligible(meditation, true));
        FamilyPairingManagerV2.current = FamilyPairingManagerV2.Role.NONE;

        service.saved = new UserDetailsInfoBean(Boolean.TRUE);
        assertTrue("a minor's account", WindDownScreens.eligible(meditation, true));
        service.saved = null;
        assertTrue("nothing saved", WindDownScreens.eligible(meditation, true));
        service.saved = new UserDetailsInfoBean(Boolean.FALSE);
        assertFalse(WindDownScreens.eligible(meditation, true));
        assertEquals("the service is looked up once", 1, PluggableExtentionKt.lookups);

        SignedInUser.idForTests = "";
        assertTrue("signed out, the saved details may be someone else's", WindDownScreens.eligible(meditation, true));
    }

    @Test public void aSwitchOfAccountWaitsForTikTokToSaveTheNewOnesDetails() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        SignedInUser.idForTests = "7000000000000000001";
        Service service = new Service(new UserDetailsInfoBean(Boolean.FALSE));
        PluggableExtentionKt.service = service;
        assertFalse("the adult's own details", WindDownScreens.eligible(meditation, true));

        SignedInUser.idForTests = "7000000000000000002";
        assertTrue("the copy is still the adult's after the switch", WindDownScreens.eligible(meditation, true));
        service.saved = new UserDetailsInfoBean(Boolean.FALSE);
        assertFalse("TikTok saved the new account's", WindDownScreens.eligible(meditation, true));

        SignedInUser.idForTests = "7000000000000000001";
        assertTrue("switching back waits again", WindDownScreens.eligible(meditation, true));
    }

    @Test public void aServiceThatIsntThereYetIsAskedForAgain() {
        Settings.HIDE_WIND_DOWN_SCREENS.save(true);
        SignedInUser.idForTests = "7000000000000000001";
        assertTrue(WindDownScreens.eligible(meditation, true));
        PluggableExtentionKt.service = new Service(new UserDetailsInfoBean(Boolean.FALSE));
        assertFalse(WindDownScreens.eligible(meditation, true));
        assertEquals(2, PluggableExtentionKt.lookups);
    }

    @Test public void theLookupsTakeTheOneMemberOfEachShape() throws Exception {
        assertSame(FamilyPairingManagerV2.INSTANCE, WindDownScreens.ownInstance(FamilyPairingManagerV2.class));
        assertEquals("role", WindDownScreens.noArgumentMethod(FamilyPairingManagerV2.class, null).getName());
        assertEquals("details",
                WindDownScreens.noArgumentMethod(IProtectionService.class, UserDetailsInfoBean.class).getName());
        assertNull("no enum with a CHILD constant", WindDownScreens.noArgumentMethod(Service.class, null));
        assertNull("no instance of itself", WindDownScreens.ownInstance(Service.class));
    }
}
