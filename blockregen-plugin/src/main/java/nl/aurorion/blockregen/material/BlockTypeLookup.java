package nl.aurorion.blockregen.material;

import com.cryptomorin.xseries.XMaterial;
import nl.aurorion.blockregen.BlockRegenPlugin;
import org.bukkit.block.Block;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Looks up the type of one block for a series of material checks against it, so the block isn't read again for
 * every check.
 * <p>
 * Only a found type is kept. When the lookup finds nothing (an unknown material that's ignored) it runs again on the
 * next check, so it logs exactly as often as calling {@link BlockRegenPlugin#getBlockType(Block)} every time would.
 */
public final class BlockTypeLookup {

    private final BlockRegenPlugin plugin;

    private final Block block;

    private XMaterial type;

    public BlockTypeLookup(@NotNull BlockRegenPlugin plugin, @NotNull Block block) {
        this.plugin = plugin;
        this.block = block;
    }

    // Whether this looks up through the given plugin, i.e. gives what plugin.getBlockType(block) would.
    public boolean isFor(@Nullable BlockRegenPlugin plugin) {
        return this.plugin == plugin;
    }

    @Nullable
    public XMaterial get() {
        XMaterial type = this.type;
        if (type == null) {
            type = this.plugin.getBlockType(this.block);
            this.type = type;
        }
        return type;
    }
}
