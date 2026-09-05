package nl.aurorion.blockregen.regeneration;

import lombok.Getter;
import lombok.extern.java.Log;
import nl.aurorion.blockregen.AutoSaveTask;
import nl.aurorion.blockregen.BlockRegenPlugin;
import nl.aurorion.blockregen.Pair;
import nl.aurorion.blockregen.material.BlockRegenMaterial;
import nl.aurorion.blockregen.preset.BlockPreset;
import nl.aurorion.blockregen.regeneration.struct.RegenerationProcess;
import nl.aurorion.blockregen.region.struct.RegenerationArea;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

@Log
public class RegenerationManager {

    private final BlockRegenPlugin plugin;

    private final Map<Block, RegenerationProcess> cache = new ConcurrentHashMap<>();

    @Getter
    private AutoSaveTask autoSaveTask;

    @Getter
    private boolean retry = false;

    private final Set<UUID> bypass = new HashSet<>();

    private final Set<UUID> dataCheck = new HashSet<>();

    public RegenerationManager(BlockRegenPlugin plugin) {
        this.plugin = plugin;
    }

    // --- Bypass

    public boolean hasBypass(@NotNull Player player) {
        return bypass.contains(player.getUniqueId());
    }

    /**
     * Switch the bypass status of the player. Return the state after the change.
     */
    public boolean switchBypass(@NotNull Player player) {
        if (bypass.contains(player.getUniqueId())) {
            bypass.remove(player.getUniqueId());
            return false;
        } else {
            bypass.add(player.getUniqueId());
            return true;
        }
    }

    // --- Data Check

    public boolean hasDataCheck(@NotNull Player player) {
        return dataCheck.contains(player.getUniqueId());
    }

    public boolean switchDataCheck(@NotNull Player player) {
        if (dataCheck.contains(player.getUniqueId())) {
            dataCheck.remove(player.getUniqueId());
            return false;
        } else {
            dataCheck.add(player.getUniqueId());
            return true;
        }
    }

    @NotNull
    public RegenerationProcess createProcess(@NotNull Block block, @NotNull BlockRegenMaterial originalMaterial, @NotNull BlockPreset preset, @Nullable RegenerationArea area) {
        RegenerationProcess process = new RegenerationProcess(block, preset, originalMaterial);

        process.setWorldName(block.getWorld().getName());
        if (area != null) {
            process.setRegionName(area.getName());
        }
        return process;
    }

    /**
     * Helper for creating regeneration processes.
     */
    @NotNull
    public RegenerationProcess createProcess(@NotNull Block block, @NotNull BlockPreset preset, @Nullable RegenerationArea region) {
        Objects.requireNonNull(block);
        Objects.requireNonNull(preset);

        Pair<String, BlockRegenMaterial> result = plugin.getMaterialManager().getMaterial(block);

        if (result == null) {
            // todo: well what now, the preset probably already matched?
            throw new IllegalStateException("Shouldn't return null...");
        }

        RegenerationProcess process = new RegenerationProcess(block, preset, result.getSecond());

        process.setWorldName(block.getWorld().getName());
        if (region != null) {
            process.setRegionName(region.getName());
        }
        return process;
    }

    /**
     * Register the process as running.
     * <p>
     * The process that is running is always the one in the cache. When another process is already registered for the
     * same block, it's stopped and replaced. Keeping the old one would leave the new (running) process outside of the
     * cache - it wouldn't be saved on shutdown and its block would stay in the replace-block state forever.
     */
    public void registerProcess(@NotNull RegenerationProcess process) {
        Objects.requireNonNull(process);

        RegenerationProcess existing = this.getProcess(process.getBlock());

        if (existing == process) {
            return;
        }

        if (existing != null) {
            log.fine(() -> String.format("Replacing process %s with %s", existing.getId(), process.getId()));
            existing.stop();
        }

        cache.put(process.getBlock(), process);
        log.fine(() -> "Registered regeneration process " + process);
    }

    @Nullable
    public RegenerationProcess getProcess(@NotNull Block block) {
        return this.cache.get(block);
    }

    public boolean isRegenerating(@NotNull Block block) {
        RegenerationProcess process = getProcess(block);
        return process != null && process.getRegenerationTime() > System.currentTimeMillis();
    }

    // Only removes the process when it's the one cached for its block.
    // RegenerationProcess#equals compares locations, so removing by block alone would let a stale process evict the
    // one that's actually running.
    public void removeProcess(RegenerationProcess process) {
        Block block = process.getBlock();

        if (cache.get(block) != process) {
            log.fine(() -> String.format("Process %s is not the one cached, not removed.", process));
            return;
        }

        cache.remove(block);
        log.fine(() -> String.format("Removed process from cache: %s", process));
    }

    public void removeProcess(@NotNull Block block) {
        cache.remove(block);
    }

    public void startAutoSave() {
        this.autoSaveTask = new AutoSaveTask(plugin);

        autoSaveTask.load();
        autoSaveTask.start();
    }

    public void reloadAutoSave() {
        if (autoSaveTask == null) {
            startAutoSave();
        } else {
            autoSaveTask.stop();
            autoSaveTask.load();
            autoSaveTask.start();
        }
    }

    // Revert blocks before disabling
    public void revertAll() {
        cache.values().forEach(process -> {
            // Stop the task, otherwise it could fire before the server is down and undo the revert.
            process.stop();
            process.revertBlock();
        });
    }

    // Regenerate processes that are past due and drop them from the cache.
    // Leaving them cached would block the registration of any later process on the same block.
    private void purgeExpired() {
        for (RegenerationProcess process : cache.values()) {
            if (process.getTimeLeft() < 0 && process.shouldRegenerate()) {
                process.stop();
                removeProcess(process);

                if (Bukkit.isPrimaryThread()) {
                    process.regenerateBlock();
                } else {
                    Bukkit.getScheduler().runTask(plugin, process::regenerateBlock);
                }
            }
        }
    }

    public void save() {
        save(false);
    }

    public void save(boolean sync) {
        final File dataFile = new File(plugin.getDataFolder(), "/Data.json");

        if (cache.isEmpty()) {
            log.fine(() -> "No processes to save.");
            try {
                Files.write(dataFile.toPath(), "[]\n".getBytes(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            } catch (IOException e) {
                log.severe(() -> "Failed to create empty Data.json.");

                // Try to force delete.
                //noinspection ResultOfMethodCallIgnored
                dataFile.delete();
            }
            return;
        }

        // Processes waiting for a manual regeneration keep their timeLeft, they have no regeneration time to derive it from.
        cache.values().forEach(process -> {
            if (process.shouldRegenerate()) {
                process.setTimeLeft(process.getRegenerationTime() - System.currentTimeMillis());
            }
        });

        // TODO: Shouldn't be required
        purgeExpired();

        final List<RegenerationProcess> finalCache = new ArrayList<>(cache.values());

        CompletableFuture<Void> future = plugin.getGsonHelper().save(finalCache, dataFile.toPath())
                .exceptionally(e -> {
                    log.log(Level.SEVERE, "Could not save processes: " + e.getMessage(), e);
                    return null;
                });

        if (sync) {
            future.join();
        }

        log.fine(() -> "Saved " + finalCache.size() + " regeneration processes..");
    }

    private boolean convertProcess(@NotNull RegenerationProcess process) {
        return process.convertLocation() && process.convertPreset();
    }

    // Start a process read from storage.
    // Listeners are registered before the (async) load finishes, so a block might already have a process running.
    // That one is newer than the stored snapshot and has to win.
    private void startLoaded(@Nullable RegenerationProcess loadedProcess) {
        if (loadedProcess == null || !convertProcess(loadedProcess)) {
            return;
        }

        if (getProcess(loadedProcess.getBlock()) != null) {
            log.fine(() -> "A process is already running at " + loadedProcess.getBlock() + ", skipping the stored one.");
            return;
        }

        loadedProcess.start();
    }

    private CompletableFuture<List<RegenerationProcess>> loadFromStorage() {
        return plugin.getGsonHelper().loadListAsync(plugin.getDataFolder().getPath() + "/Data.json", RegenerationProcess.class);
    }

    public void load() {
        loadFromStorage().thenAcceptAsync(loadedProcesses ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (loadedProcesses == null) {
                        return;
                    }

                    if (plugin.getPresetManager().isRetry() && this.retry) {
                        log.warning("Some process couldn't be loaded, but might be salvageable. Trying again after a complete server load...");
                    } else {
                        // Start em
                        for (RegenerationProcess loadedProcess : loadedProcesses) {
                            startLoaded(loadedProcess);
                        }
                        log.info("Loaded " + this.cache.size() + " regeneration process(es)...");
                    }
                })).exceptionally(e -> {
            log.log(Level.SEVERE, "Could not load processes: " + e.getMessage(), e);
            return null;
        });
    }

    public void reattemptLoad() {
        if (!retry) {
            return;
        }

        this.retry = false;

        loadFromStorage().thenAcceptAsync(loadedProcesses -> {
            if (loadedProcesses == null) {
                throw new RuntimeException("Could not load processes from storage.");
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                // We can throw away processes that are not valid. Should do no harm.
                for (RegenerationProcess loadedProcess : loadedProcesses) {
                    startLoaded(loadedProcess);
                }
                log.info("Loaded " + this.cache.size() + " regeneration process(es)...");
            });
        }).exceptionally(e -> {
            log.log(Level.SEVERE, "Could not load processes: " + e.getMessage(), e);
            return null;
        });
    }

    @NotNull
    public Collection<RegenerationProcess> getCache() {
        return Collections.unmodifiableCollection(cache.values());
    }
}