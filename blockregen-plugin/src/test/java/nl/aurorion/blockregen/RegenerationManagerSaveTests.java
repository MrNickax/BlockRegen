package nl.aurorion.blockregen;

import com.cryptomorin.xseries.XMaterial;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import nl.aurorion.blockregen.material.BlockRegenMaterial;
import nl.aurorion.blockregen.mock.MockBlockRegenPlugin;
import nl.aurorion.blockregen.mock.MockServer;
import nl.aurorion.blockregen.preset.BlockPreset;
import nl.aurorion.blockregen.preset.FixedNumberValue;
import nl.aurorion.blockregen.preset.PresetManager;
import nl.aurorion.blockregen.regeneration.RegenerationManager;
import nl.aurorion.blockregen.regeneration.struct.RegenerationProcess;
import nl.aurorion.blockregen.util.GsonHelper;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

    static final class TestMaterial implements BlockRegenMaterial {

        private final String id;

        TestMaterial(String id) {
            this.id = id;
        }

        @Override
        public boolean check(Block block) {
            return false;
        }

        @Override
        public void setType(Block block) {
        }

        @Override
        public XMaterial getType() {
            return null;
        }

        @Override
        public String getConfigurationString() {
            return id;
        }
    }

    @BeforeAll
    public static void server() {
        MockServer.install();
    }

    @BeforeEach
    public void reset() {
        MockServer.reset();
    }

    static void awaitLoaded(RegenerationManager manager) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!manager.isLoaded()) {
            if (System.currentTimeMillis() > deadline) {
                fail("The stored processes never finished loading");
            }
            Thread.sleep(10);
        }
    }

    private RegenerationManager loadedManager() throws InterruptedException {
        RegenerationManager manager = new RegenerationManager(plugin);
        manager.load();
        awaitLoaded(manager);
        return manager;
    }

    static RegenerationProcess process(int x, long regenerationTime) {
        BlockPreset preset = new BlockPreset("ores");
        preset.setDelay(FixedNumberValue.of(60));

        RegenerationProcess process = new RegenerationProcess(MockServer.block(MockServer.WORLD, x, 64, 0), preset, new TestMaterial("stone"));
        process.setRegenerationTime(regenerationTime);
        return process;
    }

    private Map<Integer, Long> savedTimeLeft() throws Exception {
        JsonArray saved = JsonParser.parseString(new String(Files.readAllBytes(dir.resolve("Data.json")), StandardCharsets.UTF_8)).getAsJsonArray();

        Map<Integer, Long> timeLeft = new HashMap<>();
        for (JsonElement element : saved) {
            JsonObject object = element.getAsJsonObject();
            timeLeft.put(object.getAsJsonObject("location").get("x").getAsInt(), object.get("timeLeft").getAsLong());
        }
        return timeLeft;
    }

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

    // One stored process, its preset uses a material of another plugin.
    private static final String STORED_PROCESS = "[{\"id\":\"8c1f6a1e-6a4e-4a8e-9a43-0c1f6a1e6a4e\","
            + "\"location\":{\"world\":\"world\",\"x\":1,\"y\":64,\"z\":0},"
            + "\"worldName\":\"world\",\"presetName\":\"custom_ore\",\"timeLeft\":60000}]";

    @Test
    public void processesWaitForPresetsThatLoadAfterTheServer() throws Exception {
        Path data = dir.resolve("Data.json");
        byte[] stored = STORED_PROCESS.getBytes(StandardCharsets.UTF_8);
        Files.write(data, stored);

        AtomicBoolean presetsRetry = new AtomicBoolean(true);
        BlockRegenPlugin plugin = new MockBlockRegenPlugin() {
            private final PresetManager presetManager = new PresetManager(this) {
                @Override
                public boolean isRetry() {
                    return presetsRetry.get();
                }
            };

            @Override
            public @NotNull File getDataFolder() {
                return dir.toFile();
            }

            @Override
            public GsonHelper getGsonHelper() {
                return gsonHelper;
            }

            @Override
            public @NotNull PresetManager getPresetManager() {
                return presetManager;
            }
        };

        RegenerationManager manager = new RegenerationManager(plugin);
        manager.load();

        // Give an (asynchronous) load the time to finish.
        long deadline = System.currentTimeMillis() + 500;
        while (!manager.isLoaded() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        // Presets using materials of other plugins load once the server is done loading. Starting the stored processes
        // before that drops the ones using them, and the next save deletes them for good.
        assertTrue(manager.isRetry(), "Loading the processes waits for the presets");
        assertFalse(manager.isLoaded());

        manager.save(true);
        assertArrayEquals(stored, Files.readAllBytes(data), "Nothing is saved before the processes are loaded");

        // The presets are retried first, then the processes load and saving resumes.
        presetsRetry.set(false);
        manager.reattemptLoad();
        awaitLoaded(manager);
    }

    @Test
    public void processThatFailsWithALinkageErrorDoesNotBlockSaving() throws Exception {
        // A process in a world whose plugin is broken: asking for the world throws NoClassDefFoundError.
        Files.write(dir.resolve("Data.json"), STORED_PROCESS.replace(":\"world\"", ":\"" + MockServer.BROKEN_WORLD + "\"").getBytes(StandardCharsets.UTF_8));

        RegenerationManager manager = new RegenerationManager(plugin);
        manager.load();

        // The other processes start and the save gate opens anyway.
        awaitLoaded(manager);
    }

    @Test
    public void savingResumesWhenTheSameInstanceIsEnabledAgain() throws Exception {
        RegenerationManager manager = loadedManager();
        manager.saveOnShutdown();

        // PlugMan and the like disable and enable the same plugin instance.
        manager.load();
        awaitLoaded(manager);

        Path data = dir.resolve("Data.json");
        Files.deleteIfExists(data);
        manager.registerProcess(process(1, System.currentTimeMillis() + 60_000));
        manager.save(true);

        assertTrue(Files.exists(data), "Processes are saved again after the plugin is enabled again");
        assertEquals(1, savedTimeLeft().size());
    }

    @Test
    public void saveLeavesTheRunningProcessesAlone() throws Exception {
        RegenerationManager manager = loadedManager();

        long now = System.currentTimeMillis();
        RegenerationProcess overdue = process(1, now - 5_000);
        RegenerationProcess running = process(2, now + 60_000);
        manager.registerProcess(overdue);
        manager.registerProcess(running);

        manager.save(true);

        // The save may run off the main thread: it must neither change the live processes nor regenerate any.
        assertEquals(-1, overdue.getTimeLeft());
        assertEquals(-1, running.getTimeLeft());
        assertEquals(2, manager.getCache().size());

        // The file gets the time left as of the save. An overdue process regenerates right after the next start.
        Map<Integer, Long> timeLeft = savedTimeLeft();
        assertEquals(0L, timeLeft.get(1));
        assertTrue(timeLeft.get(2) > 50_000 && timeLeft.get(2) <= 60_000, "Time left was " + timeLeft.get(2));
    }
}
