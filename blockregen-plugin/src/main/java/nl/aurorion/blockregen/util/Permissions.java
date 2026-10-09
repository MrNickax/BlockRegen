package nl.aurorion.blockregen.util;

import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Permissions {

    // Nodes built from a permission and a suffix, kept so the same nodes aren't concatenated on every check.
    private static final Map<String, Map<String, String>> NODES = new ConcurrentHashMap<>();

    @NotNull
    private static String node(String permission, String suffix) {
        if (permission == null || suffix == null) {
            // Concurrent maps can't hold a null key.
            return permission + "." + suffix;
        }

        Map<String, String> nodes = NODES.get(permission);
        if (nodes == null) {
            nodes = NODES.computeIfAbsent(permission, k -> new ConcurrentHashMap<>());
        }

        String node = nodes.get(suffix);
        if (node == null) {
            node = permission + "." + suffix;
            nodes.put(suffix, node);
        }
        return node;
    }

    /**
     * We do this our own way, because default permissions don't seem to work well with LuckPerms.
     * (having a wildcard permission with default: true doesn't seem to work)
     * <p>
     * When neither of the permissions are defined allow everything.
     * Specific permission takes precedence over wildcards.
     * <p>
     * OP never lacks.
     *
     * @return true if the player lacks permission (does not have it).
     */
    public static boolean lacksPermission(@NotNull CommandSender sender, @NotNull String permission, @NotNull String specific) {
        if (sender.isOp()) {
            return false;
        }

        String all = node(permission, "*");
        boolean hasAll = sender.hasPermission(all);
        boolean allDefined = sender.isPermissionSet(all);

        String specificNode = node(permission, specific);
        boolean hasSpecific = sender.hasPermission(specificNode);
        boolean specificDefined = sender.isPermissionSet(specificNode);

        return !((hasAll && !specificDefined) || (!allDefined && !specificDefined) || (hasSpecific && specificDefined));
    }

    public static boolean hasAny(@NotNull CommandSender sender, @NotNull String[] permissions) {
        for (String permission : permissions) {
            if (sender.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }
}
