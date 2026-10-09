package nl.aurorion.blockregen;

import com.cryptomorin.xseries.XMaterial;
import nl.aurorion.blockregen.material.BlockRegenMaterial;
import nl.aurorion.blockregen.material.BlockTypeLookup;
import nl.aurorion.blockregen.material.builtin.MinecraftMaterial;
import nl.aurorion.blockregen.mock.MockBlockRegenPlugin;
import nl.aurorion.blockregen.preset.material.TargetMaterial;
import org.bukkit.block.Block;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// Preset lookups share one block type lookup across every material they check. It has to give the same answers as
// checking each material on its own, and only skip repeated lookups of a type that was found.
public class BlockTypeLookupTests {

    static class CountingPlugin extends MockBlockRegenPlugin {
        private final XMaterial type;
        int lookups = 0;

        CountingPlugin(XMaterial type) {
            this.type = type;
        }

        @Override
        public XMaterial getBlockType(Block block) {
            lookups++;
            return type;
        }
    }

    private static TargetMaterial target(BlockRegenPlugin plugin, XMaterial... materials) {
        List<BlockRegenMaterial> list = new ArrayList<>();
        for (XMaterial material : materials) {
            list.add(new MinecraftMaterial(plugin, material));
        }
        return TargetMaterial.of(list);
    }

    @Test
    public void matchesLikeSeparateChecksWithOneLookup() {
        CountingPlugin plugin = new CountingPlugin(XMaterial.STONE);
        TargetMaterial matching = target(plugin, XMaterial.DIRT, XMaterial.GRASS_BLOCK, XMaterial.STONE);
        TargetMaterial other = target(plugin, XMaterial.DIRT, XMaterial.OAK_LOG);

        assertTrue(matching.matches(null));
        assertFalse(other.matches(null));
        assertEquals(5, plugin.lookups);

        plugin.lookups = 0;
        BlockTypeLookup type = new BlockTypeLookup(plugin, null);
        assertTrue(matching.matches(null, type));
        assertFalse(other.matches(null, type));
        assertEquals(1, plugin.lookups);
    }

    @Test
    public void repeatsALookupThatFoundNothing() {
        CountingPlugin plugin = new CountingPlugin(null);
        TargetMaterial target = target(plugin, XMaterial.DIRT, XMaterial.STONE);

        BlockTypeLookup type = new BlockTypeLookup(plugin, null);
        assertFalse(target.matches(null, type));
        assertEquals(2, plugin.lookups);
    }

    @Test
    public void usesTheMaterialsOwnPluginWhenTheLookupIsForAnother() {
        CountingPlugin own = new CountingPlugin(XMaterial.STONE);
        CountingPlugin other = new CountingPlugin(XMaterial.DIRT);
        TargetMaterial target = target(own, XMaterial.STONE);

        assertTrue(target.matches(null, new BlockTypeLookup(other, null)));
        assertEquals(1, own.lookups);
        assertEquals(0, other.lookups);
    }
}
