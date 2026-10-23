package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.*;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import app.morphe.extension.tiktok.SettingsContextRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class BenchmarkOwnershipTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    private Context context;
    private ComponentName component;
    private PackageManager packages;

    @Before public void service() {
        context = RuntimeEnvironment.getApplication();
        component = new ComponentName(context.getPackageName(), BenchmarkRuns.SERVICE);
        packages = context.getPackageManager();
        ServiceInfo service = new ServiceInfo();
        service.packageName = context.getPackageName();
        service.name = BenchmarkRuns.SERVICE;
        service.enabled = true;
        service.applicationInfo = context.getApplicationInfo();
        Shadows.shadowOf(packages).addOrUpdateService(service);
        context.getSharedPreferences(BenchmarkRuns.STATE_FILE, 0).edit().clear().commit();
    }

    @Test public void restoresExactPriorDefaultOrEnabledStateAcrossRepeatedCalls() {
        for (int prior : new int[]{0, 1}) {
            packages.setComponentEnabledSetting(component, prior, PackageManager.DONT_KILL_APP);
            BenchmarkRuns.apply(context, true);
            BenchmarkRuns.apply(context, true);
            assertEquals(2, packages.getComponentEnabledSetting(component));
            BenchmarkRuns.apply(context, false);
            BenchmarkRuns.apply(context, false);
            assertEquals(prior, packages.getComponentEnabledSetting(component));
            assertFalse(context.getSharedPreferences(BenchmarkRuns.STATE_FILE, 0).contains(BenchmarkRuns.ORIGINAL_STATE));
        }
    }

    @Test public void independentlyDisabledServiceIsNeverAdoptedOrEnabled() {
        for (int external : new int[]{2, 3, 4}) {
            packages.setComponentEnabledSetting(component, external, PackageManager.DONT_KILL_APP);
            BenchmarkRuns.apply(context, true);
            BenchmarkRuns.apply(context, false);
            assertEquals(external, packages.getComponentEnabledSetting(component));
        }
    }

    @Test public void externalChangeAfterOurDisableWinsAndRecoveryDropsOwnership() {
        BenchmarkRuns.apply(context, true);
        packages.setComponentEnabledSetting(component, 1, PackageManager.DONT_KILL_APP);
        BenchmarkRuns.apply(context, false);
        assertEquals(1, packages.getComponentEnabledSetting(component));
        assertFalse(context.getSharedPreferences(BenchmarkRuns.STATE_FILE, 0).contains(BenchmarkRuns.ORIGINAL_STATE));
    }

    @Test public void savedOwnershipBeforeInterruptedDisableCanBeReconciled() {
        context.getSharedPreferences(BenchmarkRuns.STATE_FILE, 0).edit().putInt(BenchmarkRuns.ORIGINAL_STATE, 1).commit();
        packages.setComponentEnabledSetting(component, 1, PackageManager.DONT_KILL_APP);
        BenchmarkRuns.apply(context, true);
        BenchmarkRuns.apply(context, false);
        assertEquals(1, packages.getComponentEnabledSetting(component));
    }

    @Test public void aNewDisableRecordsAnExternalReenableInsteadOfAnObsoleteState() {
        packages.setComponentEnabledSetting(component, 0, PackageManager.DONT_KILL_APP);
        BenchmarkRuns.apply(context, true);
        packages.setComponentEnabledSetting(component, 1, PackageManager.DONT_KILL_APP);
        BenchmarkRuns.apply(context, true);
        BenchmarkRuns.apply(context, false);
        assertEquals(1, packages.getComponentEnabledSetting(component));
    }

    @Test public void sharedStartupRestoresOwnedStateWhenTheOptionalPatchWasOmitted() throws Exception {
        BenchmarkRuns.apply(context, true);
        assertEquals(2, packages.getComponentEnabledSetting(component));
        boolean previous = app.morphe.extension.tiktok.settings.SettingsStatus.resourceGovernorEnabled;
        app.morphe.extension.tiktok.settings.SettingsStatus.resourceGovernorEnabled = false;
        app.morphe.extension.tiktok.settings.Settings.STOP_BENCHMARK_RUNS.save(true);
        try (var owner = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup()) {
            app.morphe.extension.tiktok.MetraStartup.onActivity(owner.get());
            long deadline = System.nanoTime() + 2_000_000_000L;
            while (packages.getComponentEnabledSetting(component) != 0 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(0, packages.getComponentEnabledSetting(component));
        } finally {
            app.morphe.extension.tiktok.settings.SettingsStatus.resourceGovernorEnabled = previous;
            app.morphe.extension.tiktok.settings.Settings.STOP_BENCHMARK_RUNS.resetToDefault();
        }
    }
}
