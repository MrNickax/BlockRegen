package nl.aurorion.blockregen;

import nl.aurorion.blockregen.mock.MockBlockRegenPlugin;
import nl.aurorion.blockregen.region.RegionManager;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RegionManagerSaveTests {

    @TempDir
    Path dir;

    private final BlockRegenPlugin plugin = new MockBlockRegenPlugin() {
        @Override
        public @NotNull File getDataFolder() {
            return dir.toFile();
        }
    };

    @Test
    public void saveBuildsAFreshConfigurationAndKeepsEveryRegion() throws Exception {
        // A world region and a region that fails to load (no Min), kept as raw data for the next start.
        Files.write(dir.resolve("Regions.yml"), ("Regions:\n"
                + "  spawn:\n"
                + "    worldName: world\n"
                + "    All: false\n"
                + "    Presets:\n"
                + "    - ores\n"
                + "    Priority: 3\n"
                + "  broken:\n"
                + "    Max: world;10.0;20.0;30.0\n"
                + "    All: false\n"
                + "    Presets:\n"
                + "    - logs\n"
                + "    Priority: 7\n").getBytes(StandardCharsets.UTF_8));

        RegionManager regionManager = new RegionManager(plugin);
        regionManager.load();

        FileConfiguration loaded = plugin.getFiles().getRegions().getFileConfiguration();
        String loadedBefore = loaded.saveToString();

        regionManager.save();

        // The auto-save runs off the main thread, building the file in the shared configuration raced other saves.
        assertEquals(loadedBefore, loaded.saveToString(), "The loaded configuration must not be modified by a save");

        FileConfiguration saved = YamlConfiguration.loadConfiguration(dir.resolve("Regions.yml").toFile());

        assertEquals("world", saved.getString("Regions.spawn.worldName"));
        assertEquals(3, saved.getInt("Regions.spawn.Priority"));
        assertFalse(saved.getBoolean("Regions.spawn.All"));

        assertEquals("world;10.0;20.0;30.0", saved.getString("Regions.broken.Max"));
        assertEquals(7, saved.getInt("Regions.broken.Priority"), "A region that failed to load keeps its priority");
        assertFalse(saved.getBoolean("Regions.broken.All"));
        assertEquals("logs", saved.getStringList("Regions.broken.Presets").get(0));
    }
}
