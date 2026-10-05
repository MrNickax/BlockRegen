package nl.aurorion.blockregen;

import lombok.Getter;
import lombok.extern.java.Log;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

@Log
public class AutoSaveTask implements Runnable {

    /**
     * Upper bound of the interval in seconds. A crash loses everything since the last save, so a longer configured
     * interval (live servers keep their old Settings.yml, which shipped with 600) is clamped down to this.
     */
    public static final int MAX_INTERVAL = 300;

    /**
     * Lower bound of the interval in seconds, so a typo can't make the plugin rewrite its data every tick.
     */
    public static final int MIN_INTERVAL = 10;

    @Getter
    private int period;

    private BukkitTask task;

    @Getter
    private volatile boolean running = false;

    // Set by stop(). A run the scheduler already handed to a thread then does nothing.
    private volatile boolean stopped = false;

    // Held while a run is in progress, so the shutdown can wait for it before its own final saves.
    private final ReentrantLock runLock = new ReentrantLock();

    private final BlockRegenPlugin plugin;

    public AutoSaveTask(BlockRegenPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Auto-save is on unless it's explicitly disabled.
     */
    public static boolean isEnabled(@NotNull BlockRegenPlugin plugin) {
        return plugin.getConfig().getBoolean("Auto-Save.Enabled", true);
    }

    public void load() {
        int configured = plugin.getConfig().getInt("Auto-Save.Interval", MAX_INTERVAL);
        this.period = Math.max(MIN_INTERVAL, Math.min(configured, MAX_INTERVAL));

        if (period != configured) {
            log.warning("Auto-Save.Interval of " + configured + " seconds is outside of " + MIN_INTERVAL + "-" + MAX_INTERVAL + ", using " + period + " seconds.");
        }
    }

    public void start() {
        if (running) {
            stop();
        }

        running = true;
        stopped = false;
        task = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this, period * 20L, period * 20L);
        log.info("Starting auto-save.. with an interval of " + period + " seconds.");
    }

    public void stop() {
        stopped = true;

        if (!running) {
            return;
        }

        if (task == null) {
            running = false;
            return;
        }

        task.cancel();
        task = null;
        running = false;
    }

    /**
     * Wait until a run that is in progress has finished, at most the given time.
     *
     * @return False if it's still running.
     */
    public boolean awaitIdle(long timeout, @NotNull TimeUnit unit) {
        try {
            if (runLock.tryLock(timeout, unit)) {
                runLock.unlock();
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    @Override
    public void run() {
        runLock.lock();
        try {
            if (stopped) {
                return;
            }

            saveAll();
        } finally {
            runLock.unlock();
        }
    }

    private void saveAll() {
        // Each save on its own, a failing one must neither skip the other nor break the timer.
        try {
            // Already off the main thread, save right here.
            plugin.getRegenerationManager().save(true);
        } catch (Exception e) {
            log.log(Level.SEVERE, "Could not auto-save regeneration processes: " + e.getMessage(), e);
        }

        try {
            plugin.getRegionManager().save();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Could not auto-save regions: " + e.getMessage(), e);
        }

        try {
            // Hands itself over to the main thread.
            plugin.getRegenerationManager().purgeExpired();
        } catch (Exception e) {
            log.log(Level.SEVERE, "Could not purge expired regeneration processes: " + e.getMessage(), e);
        }
    }
}
