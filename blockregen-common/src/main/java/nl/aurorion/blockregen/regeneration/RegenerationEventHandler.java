package nl.aurorion.blockregen.regeneration;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.jetbrains.annotations.NotNull;

public interface RegenerationEventHandler {
    <E extends Event> void handleEvent(Block block, Player player, E event, EventControl<E> eventControl, RegenerationEventType type);

    /**
     * Whether the player bypasses regeneration and protection.
     */
    boolean hasBypass(@NotNull Player player);
}
