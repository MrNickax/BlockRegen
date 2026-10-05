package nl.aurorion.blockregen.mock;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Just enough of a Bukkit server for the save and load tests.
 * <p>
 * Tasks for "now" (runTask, runTaskAsynchronously) run right away on the calling thread, delayed and repeating ones are
 * kept until {@link #runPending()}. Only the world {@link #WORLD} exists, asking for {@link #BROKEN_WORLD} throws a
 * {@link LinkageError} (like a class of a broken plugin would).
 */
public final class MockServer {

    public static final String WORLD = "world";

    public static final String BROKEN_WORLD = "broken";

    private static final Logger LOGGER = Logger.getLogger("MockServer");

    private static final AtomicInteger TASK_IDS = new AtomicInteger();

    private static final List<MockTask> PENDING = new CopyOnWriteArrayList<>();

    private static final Map<String, World> WORLDS = new ConcurrentHashMap<>();

    private static final Map<String, Block> BLOCKS = new ConcurrentHashMap<>();

    private static final BukkitScheduler SCHEDULER = proxy(BukkitScheduler.class, MockServer::schedule);

    private static final PluginManager PLUGIN_MANAGER = proxy(PluginManager.class, (proxy, method, args) -> defaultValue(method));

    private MockServer() {
    }

    public static synchronized void install() {
        if (Bukkit.getServer() == null) {
            Bukkit.setServer(proxy(Server.class, MockServer::serve));
        }
    }

    /**
     * Drop the delayed tasks of a previous test.
     */
    public static void reset() {
        PENDING.clear();
    }

    /**
     * Run the delayed and repeating tasks scheduled so far, once.
     */
    public static void runPending() {
        List<MockTask> tasks = new ArrayList<>(PENDING);
        PENDING.removeAll(tasks);
        for (MockTask task : tasks) {
            if (!task.isCancelled()) {
                task.runnable.run();
            }
        }
    }

    public static int pendingCount() {
        return (int) PENDING.stream().filter(task -> !task.isCancelled()).count();
    }

    @NotNull
    public static World world(@NotNull String name) {
        return WORLDS.computeIfAbsent(name, key -> proxy(World.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getName":
                    return key;
                case "getUID":
                    return UUID.nameUUIDFromBytes(key.getBytes());
                case "getBlockAt":
                    if (args.length == 3) {
                        return block(key, (int) args[0], (int) args[1], (int) args[2]);
                    }
                    return null;
                default:
                    return objectMethod(proxy, method, args, "MockWorld{" + key + "}");
            }
        }));
    }

    @NotNull
    public static Block block(@NotNull String worldName, int x, int y, int z) {
        String key = worldName + ";" + x + ";" + y + ";" + z;
        return BLOCKS.computeIfAbsent(key, k -> proxy(Block.class, (proxy, method, args) -> {
            switch (method.getName()) {
                case "getWorld":
                    return world(worldName);
                case "getX":
                    return x;
                case "getY":
                    return y;
                case "getZ":
                    return z;
                case "getLocation":
                    if (args == null || args.length == 0) {
                        return new Location(world(worldName), x, y, z);
                    }
                    return null;
                default:
                    return objectMethod(proxy, method, args, "MockBlock{" + k + "}");
            }
        }));
    }

    private static Object serve(Object proxy, Method method, Object[] args) {
        switch (method.getName()) {
            case "getLogger":
                return LOGGER;
            // Parsed by XSeries.
            case "getName":
                return "MockServer";
            case "getVersion":
                return "git-MockServer-1 (MC: 1.21.11)";
            case "getBukkitVersion":
                return "1.21.11-R0.1-SNAPSHOT";
            case "getScheduler":
                return SCHEDULER;
            case "getPluginManager":
                return PLUGIN_MANAGER;
            case "isPrimaryThread":
                return true;
            case "getWorld":
                if (args[0] instanceof String) {
                    if (BROKEN_WORLD.equals(args[0])) {
                        throw new NoClassDefFoundError("Broken world plugin");
                    }
                    return WORLD.equals(args[0]) ? world(WORLD) : null;
                }
                return null;
            default:
                return objectMethod(proxy, method, args, "MockServer");
        }
    }

    private static Object schedule(Object proxy, Method method, Object[] args) {
        String name = method.getName();

        if (args == null || args.length < 2 || !(args[1] instanceof Runnable)) {
            return objectMethod(proxy, method, args, "MockScheduler");
        }

        Runnable runnable = (Runnable) args[1];

        switch (name) {
            case "runTask":
            case "runTaskAsynchronously": {
                MockTask task = new MockTask((Plugin) args[0], runnable);
                runnable.run();
                return task;
            }
            case "runTaskLater":
            case "runTaskLaterAsynchronously":
            case "runTaskTimer":
            case "runTaskTimerAsynchronously": {
                MockTask task = new MockTask((Plugin) args[0], runnable);
                PENDING.add(task);
                return task;
            }
            default:
                return objectMethod(proxy, method, args, "MockScheduler");
        }
    }

    private static Object objectMethod(Object proxy, Method method, Object[] args, String description) {
        switch (method.getName()) {
            case "equals":
                return proxy == args[0];
            case "hashCode":
                return System.identityHashCode(proxy);
            case "toString":
                return description;
            default:
                return defaultValue(method);
        }
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (type == boolean.class) {
            return false;
        } else if (type == int.class) {
            return 0;
        } else if (type == long.class) {
            return 0L;
        } else if (type == double.class) {
            return 0D;
        } else if (type == float.class) {
            return 0F;
        } else if (type == short.class) {
            return (short) 0;
        } else if (type == byte.class) {
            return (byte) 0;
        } else if (type == char.class) {
            return (char) 0;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(MockServer.class.getClassLoader(), new Class<?>[]{type}, handler);
    }

    private static final class MockTask implements BukkitTask {

        private final int id = TASK_IDS.incrementAndGet();

        private final Plugin owner;

        private final Runnable runnable;

        private volatile boolean cancelled = false;

        private MockTask(Plugin owner, Runnable runnable) {
            this.owner = owner;
            this.runnable = runnable;
        }

        @Override
        public int getTaskId() {
            return id;
        }

        @Override
        public @NotNull Plugin getOwner() {
            return owner;
        }

        @Override
        public boolean isSync() {
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }
}
