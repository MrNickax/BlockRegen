package nl.aurorion.blockregen;

import nl.aurorion.blockregen.configuration.ConfigFile;
import nl.aurorion.blockregen.mock.MockBlockRegenPlugin;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class ConfigFileTests {

    @TempDir
    Path dir;

    @Test
    public void saveReplacesTheFileInsteadOfRewritingItInPlace() throws Exception {
        Path regions = dir.resolve("Regions.yml");
        Files.write(regions, "Regions:\n  mine:\n    All: true\n".getBytes(StandardCharsets.UTF_8));

        // A second name for the current file. Rewriting it in place shows through this name, a crash in the middle of
        // that leaves a torn file behind (Regions.yml is rewritten by every auto-save).
        Path previous = dir.resolve("previous.yml");
        Files.createLink(previous, regions);
        byte[] before = Files.readAllBytes(previous);

        ConfigFile configFile = new ConfigFile(new MockBlockRegenPlugin() {
            @Override
            public @NotNull File getDataFolder() {
                return dir.toFile();
            }
        }, "Regions.yml");
        configFile.load();
        configFile.getFileConfiguration().set("Regions.mine.All", false);

        configFile.save();

        assertArrayEquals(before, Files.readAllBytes(previous));
        assertTrue(new String(Files.readAllBytes(regions), StandardCharsets.UTF_8).contains("All: false"));

        try (Stream<Path> files = Files.list(dir)) {
            assertEquals(2, files.count(), "No temporary files are left behind");
        }
    }
}
