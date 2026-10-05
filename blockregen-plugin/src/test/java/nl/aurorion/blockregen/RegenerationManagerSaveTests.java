package nl.aurorion.blockregen;

import com.google.gson.GsonBuilder;
import nl.aurorion.blockregen.mock.MockBlockRegenPlugin;
import nl.aurorion.blockregen.regeneration.RegenerationManager;
import nl.aurorion.blockregen.util.GsonHelper;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class RegenerationManagerSaveTests {

    @TempDir
    Path dir;

    private final GsonHelper gsonHelper = new GsonHelper(new GsonBuilder());

    private final BlockRegenPlugin plugin = new MockBlockRegenPlugin() {
        @Override
        public @NotNull File getDataFolder() {
            return dir.toFile();
        }

        @Override
        public GsonHelper getGsonHelper() {
            return gsonHelper;
        }
    };

    @Test
    public void saveBeforeTheStoredProcessesAreLoadedKeepsTheFile() throws Exception {
        Path data = dir.resolve("Data.json");
        byte[] stored = "[{\"id\":\"8c1f6a1e-6a4e-4a8e-9a43-0c1f6a1e6a4e\",\"timeLeft\":60000}]".getBytes(StandardCharsets.UTF_8);
        Files.write(data, stored);

        // The load is asynchronous, a save (auto-save, shutdown) can come before it finishes. The cache is still empty
        // then, writing it would wipe every stored process.
        new RegenerationManager(plugin).save(true);

        assertArrayEquals(stored, Files.readAllBytes(data));
    }
}
