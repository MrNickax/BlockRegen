package nl.aurorion.blockregen.regeneration;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import lombok.Getter;
import lombok.extern.java.Log;
import nl.aurorion.blockregen.AutoSaveTask;
import nl.aurorion.blockregen.BlockRegenPlugin;
import nl.aurorion.blockregen.Pair;
import nl.aurorion.blockregen.material.BlockRegenMaterial;
import nl.aurorion.blockregen.preset.BlockPreset;
import nl.aurorion.blockregen.regeneration.struct.RegenerationProcess;
import nl.aurorion.blockregen.region.struct.RegenerationArea;
import nl.aurorion.blockregen.util.AtomicFiles;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
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

    // A change (process registered or removed) is saved at most this long after it happened, on top of the periodic
    // auto-save. A crash then loses at most that much instead of a whole auto-save interval.
    private static final long CHANGE_SAVE_DELAY_TICKS = 30 * 20L;

    // How long a save waits for another one that is still writing.
    private static final long SAVE_LOCK_TIMEOUT_SECONDS = 10;

    // How long past due a process without a task has to be before purgeExpired regenerates it.
    private static final long PURGE_GRACE_MILLIS = 10_000;

    // Set when a process is registered or removed, cleared when a save takes its snapshot.
    private final AtomicBoolean dirty = new AtomicBoolean(false);

    // True while a change save is scheduled, a burst of changes leads to a single write.
    private final AtomicBoolean changeSaveScheduled = new AtomicBoolean(false);

    // One save at a time. The snapshot is taken under the lock too, an older snapshot never replaces a newer one.
    private final ReentrantLock saveLock = new ReentrantLock();

    // Numbers the snapshots in the order they're taken. Guards the case the final save skips the lock after a
    // timeout: a write only replaces Data.json when no newer snapshot got there first.
    private final AtomicLong snapshotNumber = new AtomicLong();

    // The number of the snapshot Data.json holds, guarded by commitLock (held only for the rename).
    private long committedSnapshot = 0;

    private final Object commitLock = new Object();

    // Data.json is only written once the stored processes are loaded (asynchronously). Saving the still empty cache
    // before that would wipe them.
    @Getter
    private volatile boolean loaded = false;

    // Set by the final save on shutdown, any save after it is dropped.
    private volatile boolean closed = false;

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

        markDirty();
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

        markDirty();
    }

    public void removeProcess(@NotNull Block block) {
        if (cache.remove(block) != null) {
            markDirty();
        }
    }

    // The cache differs from Data.json now, schedule a save.
    private void markDirty() {
        dirty.set(true);
        scheduleChangeSave();
    }

    // At most one change save is pending. It runs off the main thread, only while auto-save is enabled.
    private void scheduleChangeSave() {
        if (closed || autoSaveTask == null || !autoSaveTask.isRunning() || !plugin.isEnabled()) {
            return;
        }

        if (!changeSaveScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, () -> {
                changeSaveScheduled.set(false);

                if (dirty.get()) {
                    save(true);
                }
            }, CHANGE_SAVE_DELAY_TICKS);
        } catch (RuntimeException e) {
            changeSaveScheduled.set(false);
            log.log(Level.WARNING, "Could not schedule a save of regeneration processes: " + e.getMessage(), e);
        }
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

    public void stopAutoSave() {
        if (autoSaveTask != null) {
            autoSaveTask.stop();
        }
    }

    // Revert blocks before disabling
    public void revertAll() {
        cache.values().forEach(process -> {
            // One block failing must not leave the others un-reverted, nor skip the save that follows.
            try {
                // Stop the task, otherwise it could fire before the server is down and undo the revert.
                process.stop();
                process.revertBlock();
            } catch (Exception e) {
                log.log(Level.SEVERE, "Could not revert " + process + ": " + e.getMessage(), e);
            }
        });
    }

    /**
     * Regenerate processes that are past due but have no task left to do it. Leaving them would keep their blocks in
     * the replace-block state until the next restart.
     * <p>
     * Regenerating touches blocks and calls events, so it always happens on the main thread.
     */
    public void purgeExpired() {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, this::purgeExpired);
            return;
        }

        long now = System.currentTimeMillis();

        for (RegenerationProcess process : new ArrayList<>(cache.values())) {
            // The grace leaves processes due right now to their own task.
            if (process.shouldRegenerate() && !process.isRunning() && process.getRegenerationTime() + PURGE_GRACE_MILLIS < now) {
                log.fine(() -> "Regenerating expired process " + process);
                // The regular path: calls the event and waits for solid ground.
                process.regenerate();
            }
        }
    }

    public void save() {
        save(false);
    }

    /**
     * Save the running processes to Data.json.
     *
     * @param sync Save on the calling thread instead of asynchronously.
     */
    public void save(boolean sync) {
        if (sync) {
            write(false);
        } else {
            CompletableFuture.runAsync(() -> write(false));
        }
    }

    /**
     * The final save on shutdown, after the timers were stopped. Runs on the calling thread, any save after it is
     * dropped.
     */
    public void saveOnShutdown() {
        write(true);
    }

    private void write(boolean last) {
        if (!loaded) {
            log.warning("Regeneration processes are not loaded yet, not saving them. Data.json is left untouched.");
            return;
        }

        final File dataFile = new File(plugin.getDataFolder(), "/Data.json");

        boolean locked = lockForSave();

        if (!locked) {
            if (!last) {
                dirty.set(true);
                log.warning("Another save of regeneration processes is still running, skipping this one.");
                return;
            }
            // The snapshot numbers below keep the stuck one from landing after this one.
            log.severe("Another save of regeneration processes is still running after " + SAVE_LOCK_TIMEOUT_SECONDS + "s, writing the final one anyway.");
        }

        try {
            if (closed) {
                return;
            }
            closed = last;

            // Cleared before the snapshot, a change made while writing marks the cache dirty again.
            dirty.set(false);

            final long number = snapshotNumber.incrementAndGet();
            final JsonArray snapshot = snapshot(System.currentTimeMillis());
            byte[] json = plugin.getGsonHelper().getGson().toJson(snapshot).getBytes(StandardCharsets.UTF_8);

            // Atomic, a crash or a failed write leaves the previous file intact.
            try (AtomicFiles.PendingWrite pending = AtomicFiles.prepare(dataFile.toPath(), json)) {
                synchronized (commitLock) {
                    // Only possible when the final save gave up waiting for the lock: it's newer, keep it.
                    if (number < committedSnapshot) {
                        log.warning("A newer save of regeneration processes was written first, dropping this one.");
                        return;
                    }

                    pending.commit();
                    committedSnapshot = number;
                }
            }

            log.fine(() -> "Saved " + snapshot.size() + " regeneration processes..");
        } catch (Exception e) {
            dirty.set(true);
            log.log(Level.SEVERE, "Could not save processes: " + e.getMessage(), e);
        } finally {
            if (locked) {
                saveLock.unlock();
            }
        }
    }

    // Serialized copies of the processes, with the time left as of now. The live processes aren't touched: this may run
    // off the main thread, past due ones are regenerated by their own task (or purgeExpired).
    @NotNull
    private JsonArray snapshot(long now) {
        Gson gson = plugin.getGsonHelper().getGson();
        JsonArray processes = new JsonArray();

        for (RegenerationProcess process : cache.values()) {
            JsonElement element = gson.toJsonTree(process);

            // Processes waiting for a manual regeneration keep their timeLeft, they have no regeneration time to derive it from.
            if (process.shouldRegenerate()) {
                // A past due process regenerates right after the next start.
                element.getAsJsonObject().addProperty("timeLeft", Math.max(0L, process.getRegenerationTime() - now));
            }

            processes.add(element);
        }
        return processes;
    }

    private boolean lockForSave() {
        try {
            return saveLock.tryLock(SAVE_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    // One process failing to start must not keep the others from starting, nor the save gate closed. A LinkageError
    // comes from a broken or missing plugin (its world or material classes).
    private void startAllLoaded(@NotNull List<RegenerationProcess> loadedProcesses) {
        for (RegenerationProcess loadedProcess : loadedProcesses) {
            try {
                startLoaded(loadedProcess);
            } catch (Exception | LinkageError e) {
                log.log(Level.SEVERE, "Could not start stored regeneration process " + loadedProcess + ": " + e.getMessage(), e);
            }
        }
        log.info("Loaded " + this.cache.size() + " regeneration process(es)...");
    }

    // Data.json may be written from now on.
    private void markLoaded() {
        this.loaded = true;

        // Processes registered while loading.
        if (dirty.get()) {
            scheduleChangeSave();
        }
    }

    public void load() {
        // A fresh start. Also when the same instance is enabled again (PlugMan and the like): the final save of the
        // previous run closed saving, and its stopped processes are in Data.json, they're loaded from there again.
        this.closed = false;
        this.loaded = false;
        this.retry = false;
        this.cache.clear();
        this.dirty.set(false);
        // A change save cancelled with the plugin's tasks never cleared its flag.
        this.changeSaveScheduled.set(false);

        // Presets using materials of other plugins only load once the server is done loading
        // (PresetManager#reattemptLoad). Starting the stored processes before that would drop the ones using them, and
        // the next save would delete them for good. They're loaded by reattemptLoad() then, nothing is saved until.
        if (plugin.getPresetManager().isRetry()) {
            this.retry = true;
            log.warning("Some presets are not loaded yet. Loading regeneration processes after a complete server load...");
            return;
        }

        loadFromStorage().thenAcceptAsync(loadedProcesses ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // Null when there's nothing to load (or the file was damaged and kept aside).
                    if (loadedProcesses != null) {
                        // Start em
                        startAllLoaded(loadedProcesses);
                    }
                    markLoaded();
                })).exceptionally(e -> {
            log.log(Level.SEVERE, "Could not load processes: " + e.getMessage() + ". Data.json is left untouched, processes won't be saved until the next start.", e);
            return null;
        });
    }

    public void reattemptLoad() {
        if (!retry) {
            return;
        }

        this.retry = false;

        loadFromStorage().thenAcceptAsync(loadedProcesses ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // We can throw away processes that are not valid. Should do no harm.
                    if (loadedProcesses != null) {
                        startAllLoaded(loadedProcesses);
                    }
                    markLoaded();
                })).exceptionally(e -> {
            log.log(Level.SEVERE, "Could not load processes: " + e.getMessage() + ". Data.json is left untouched, processes won't be saved until the next start.", e);
            return null;
        });
    }

    @NotNull
    public Collection<RegenerationProcess> getCache() {
        return Collections.unmodifiableCollection(cache.values());
    }
}