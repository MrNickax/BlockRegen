package nl.aurorion.blockregen.listener;

import lombok.extern.java.Log;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SpongeAbsorbEvent;

/**
 * Sponges soak up liquids without firing a BlockBreakEvent, the same way a bucket picks them up.
 * <p>
 * Kept separate from {@link ProtectionListener}, SpongeAbsorbEvent only exists on 1.13+.
 */
@Log
public class SpongeListener implements Listener {

    private final ProtectionListener protectionListener;

    public SpongeListener(ProtectionListener protectionListener) {
        this.protectionListener = protectionListener;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        event.getBlocks().removeIf(state -> {
            boolean denied = protectionListener.isProtected(state.getBlock());
            if (denied) {
                log.fine(() -> "Denied a sponge from absorbing a protected block.");
            }
            return denied;
        });
    }
}
