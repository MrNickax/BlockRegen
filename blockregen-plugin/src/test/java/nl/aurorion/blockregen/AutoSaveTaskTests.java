package nl.aurorion.blockregen;

import nl.aurorion.blockregen.mock.MockBlockRegenPlugin;
import nl.aurorion.blockregen.regeneration.RegenerationManager;
import nl.aurorion.blockregen.region.RegionManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class AutoSaveTaskTests {

    private static BlockRegenPlugin pluginWithSettings(String settings) {
        FileConfiguration configuration = YamlConfiguration.loadConfiguration(new StringReader(settings));
        return new MockBlockRegenPlugin() {
            @Override
            public @NotNull FileConfiguration getConfig() {
                return configuration;
            }
        };
    }

    private static int loadedPeriod(String settings) {
        AutoSaveTask task = new AutoSaveTask(pluginWithSettings(settings));
        task.load();
        return task.getPeriod();
    }

    // A plugin whose process save runs the given action, everything else saved by the auto-save does nothing.
    private static BlockRegenPlugin pluginSaving(Runnable onSave) {
        return new MockBlockRegenPlugin() {
            private final RegenerationManager regenerationManager = new RegenerationManager(this) {
                @Override
                public void save(boolean sync) {
                    onSave.run();
                }

                @Override
                public void purgeExpired() {
                }
            };

            private final RegionManager regionManager = new RegionManager(this) {
                @Override
                public void save() {
                }
            };

            @Override
            public @NotNull RegenerationManager getRegenerationManager() {
                return regenerationManager;
            }

            @Override
            public @NotNull RegionManager getRegionManager() {
                return regionManager;
            }
        };
    }

    @Test
    public void shutdownCanWaitForARunningAutoSave() throws Exception {
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        AutoSaveTask task = new AutoSaveTask(pluginSaving(() -> {
            saving.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));

        // The timer runs the task off the main thread.
        Thread timer = new Thread(task);
        timer.start();
        assertTrue(saving.await(5, TimeUnit.SECONDS));

        // The final saves on shutdown must come after the one in progress, not next to it.
        assertFalse(task.awaitIdle(200, TimeUnit.MILLISECONDS), "An auto-save is still in progress");

        release.countDown();
        assertTrue(task.awaitIdle(5, TimeUnit.SECONDS));
        timer.join(5000);
    }

    @Test
    public void runAfterStopDoesNothing() {
        AtomicInteger saves = new AtomicInteger();
        AutoSaveTask task = new AutoSaveTask(pluginSaving(saves::incrementAndGet));

        // A run that was already handed to the scheduler when the plugin stopped the timer.
        task.stop();
        task.run();

        assertEquals(0, saves.get());
    }

    @Test
    public void intervalAboveFiveMinutesIsClamped() {
        // Live servers keep their old Settings.yml, which shipped with 600.
        assertEquals(300, loadedPeriod("Auto-Save:\n  Interval: 600"));
        assertEquals(300, loadedPeriod("Auto-Save:\n  Interval: 86400"));
    }

    @Test
    public void intervalBelowTheMinimumIsRaised() {
        assertEquals(10, loadedPeriod("Auto-Save:\n  Interval: 1"));
        assertEquals(10, loadedPeriod("Auto-Save:\n  Interval: 0"));
        assertEquals(10, loadedPeriod("Auto-Save:\n  Interval: -5"));
    }

    @Test
    public void intervalWithinBoundsIsKept() {
        assertEquals(120, loadedPeriod("Auto-Save:\n  Interval: 120"));
        assertEquals(300, loadedPeriod("Auto-Save:\n  Interval: 300"));
    }

    @Test
    public void missingIntervalDefaultsToFiveMinutes() {
        assertEquals(300, loadedPeriod("Auto-Save:\n  Enabled: true"));
    }

    @Test
    public void autoSaveIsEnabledWhenTheKeyIsMissing() {
        assertTrue(AutoSaveTask.isEnabled(pluginWithSettings("Auto-Save:\n  Interval: 300")));
        assertTrue(AutoSaveTask.isEnabled(pluginWithSettings("")));
    }

    @Test
    public void autoSaveCanStillBeDisabled() {
        assertFalse(AutoSaveTask.isEnabled(pluginWithSettings("Auto-Save:\n  Enabled: false")));
    }
}
