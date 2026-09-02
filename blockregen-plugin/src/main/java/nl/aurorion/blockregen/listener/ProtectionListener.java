package nl.aurorion.blockregen.listener;

import com.cryptomorin.xseries.XMaterial;
import lombok.extern.java.Log;
import nl.aurorion.blockregen.BlockRegenPlugin;
import nl.aurorion.blockregen.region.struct.RegenerationArea;
import nl.aurorion.blockregen.util.Blocks;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Protects blocks from everything that removes them without ever firing a BlockBreakEvent.
 * <p>
 * Arrows cracking decorated pots, explosions, fire, liquids, pistons,... none of those go through the
 * regeneration event handler, so blocks could still be destroyed with Disable-Other-Break turned on.
 * <p>
 * Blocks removed this way are only protected, never regenerated. There is no player to hand the rewards to.
 */
@Log
public class ProtectionListener implements Listener {

    private final BlockRegenPlugin plugin;

    // Cache the options, some of these events fire a lot and the memory section lookup takes a long time.
    private boolean useRegions;
    private boolean disableOtherBreak;
    private List<String> worldsEnabled;

    public ProtectionListener(BlockRegenPlugin plugin) {
        this.plugin = plugin;
    }

    public void load() {
        this.useRegions = plugin.getConfig().getBoolean("Use-Regions", false);
        this.disableOtherBreak = plugin.getConfig().getBoolean("Disable-Other-Break", false);
        this.worldsEnabled = plugin.getConfig().getStringList("Worlds-Enabled");
    }

    // Projectiles cracking decorated pots, endermen picking blocks up, sheep eating grass, withers,
    // falling blocks, mobs trampling farmland,...
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();

        // A falling block landing in the air adds a block, it doesn't destroy one.
        if (block.isEmpty()) {
            return;
        }

        if (hasBypass(event.getEntity())) {
            return;
        }

        deny(event, block);
    }

    // Mobs stepping on farmland and turtle eggs.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityInteract(EntityInteractEvent event) {
        Block block = event.getBlock();

        XMaterial type = plugin.getBlockType(block);

        // Anything else (pressure plates, tripwires,...) leaves the block alone.
        if (type != XMaterial.FARMLAND && type != XMaterial.TURTLE_EGG) {
            return;
        }

        if (hasBypass(event.getEntity())) {
            return;
        }

        deny(event, block);
    }

    // Creepers, TNT, end crystals, ghasts,...
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        denyAll(event.blockList());
    }

    // Beds and respawn anchors.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        denyAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        deny(event, event.getBlock());
    }

    // Melting ice and snow, drying farmland, dying coral,...
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        Block block = event.getBlock();

        XMaterial type = plugin.getBlockType(block);

        // Fire burning out is a fade as well. Denying it would leave the fire burning forever.
        if (type == XMaterial.FIRE || type == XMaterial.SOUL_FIRE) {
            return;
        }

        deny(event, block);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        deny(event, event.getBlock());
    }

    // Liquids washing away torches, flowers,...
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFromTo(BlockFromToEvent event) {
        Block to = event.getToBlock();

        // Flowing into air or into another liquid destroys nothing.
        if (to.isEmpty() || to.isLiquid()) {
            return;
        }

        deny(event, to);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        if (movesProtected(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
            log.fine(() -> "Denied a piston from moving protected blocks.");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        if (movesProtected(event.getBlocks(), event.getDirection())) {
            event.setCancelled(true);
            log.fine(() -> "Denied a piston from moving protected blocks.");
        }
    }

    private boolean movesProtected(List<Block> blocks, BlockFace direction) {
        for (Block block : blocks) {
            if (isProtected(block)) {
                return true;
            }

            // Blocks that cannot be pushed (torches, flowers,...) break when a block is moved into them.
            Block destination = block.getRelative(direction);
            if (!destination.isEmpty() && isProtected(destination)) {
                return true;
            }
        }
        return false;
    }

    private void deny(Cancellable event, Block block) {
        if (!isProtected(block)) {
            return;
        }

        event.setCancelled(true);
        log.fine(() -> String.format("Denied destruction of %s.", Blocks.blockToString(block)));
    }

    private void denyAll(List<Block> blocks) {
        blocks.removeIf(block -> {
            if (!isProtected(block)) {
                return false;
            }

            log.fine(() -> String.format("Denied destruction of %s.", Blocks.blockToString(block)));
            return true;
        });
    }

    /**
     * Whether the block has to be kept in place, no matter what is trying to destroy it.
     */
    public boolean isProtected(@NotNull Block block) {
        // A regenerating block can never be destroyed, the process would be left hanging.
        if (plugin.getRegenerationManager().getProcess(block) != null) {
            return true;
        }

        RegenerationArea area = this.useRegions ? plugin.getRegionManager().getArea(block) : null;

        boolean isInZone = this.useRegions
                ? area != null
                : this.worldsEnabled.contains(block.getWorld().getName());

        if (!isInZone) {
            return false;
        }

        if (area != null && area.getDisableOtherBreak() != null) {
            return area.getDisableOtherBreak();
        }

        return this.disableOtherBreak;
    }

    private boolean hasBypass(@Nullable Entity entity) {
        Player player = resolvePlayer(entity);
        return player != null && plugin.getRegenerationEventHandler().hasBypass(player);
    }

    // The entity destroying the block is usually the projectile, not the player that shot it.
    @Nullable
    private Player resolvePlayer(@Nullable Entity entity) {
        if (entity instanceof Player) {
            return (Player) entity;
        }

        if (entity instanceof Projectile) {
            ProjectileSource shooter = ((Projectile) entity).getShooter();
            if (shooter instanceof Player) {
                return (Player) shooter;
            }
        }

        return null;
    }
}
