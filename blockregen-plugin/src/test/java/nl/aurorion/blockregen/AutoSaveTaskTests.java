package nl.aurorion.blockregen;

import nl.aurorion.blockregen.mock.MockBlockRegenPlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

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
