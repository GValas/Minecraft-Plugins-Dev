package dev.xoperr.betterstasis.listener;

import dev.xoperr.betterstasis.BetterStasisPlugin;
import dev.xoperr.betterstasis.handler.PearlBindingHandler;
import dev.xoperr.betterstasis.handler.TeleportHandler;
import dev.xoperr.betterstasis.util.PearlDataUtil;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Listener for handling player interactions with fishing rods.
 * Handles both pearl binding (shift+right-click) and teleportation (normal right-click).
 */
public class PlayerInteractListener implements Listener {

    private final BetterStasisPlugin plugin;

    public PlayerInteractListener(BetterStasisPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Handle SHIFT + RIGHT-CLICK for binding pearls.
     *
     * @param event The player interact event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItem();

        // Check if player is using a fishing rod
        if (item == null || item.getType() != Material.FISHING_ROD) {
            return;
        }

        // Check if player is sneaking (shift)
        if (!player.isSneaking()) {
            return;
        }

        // Check for right-click action
        if (event.getAction() != Action.RIGHT_CLICK_AIR &&
            event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // Cancel the event to prevent normal fishing rod behavior
        event.setCancelled(true);

        // Handle pearl binding
        PearlBindingHandler.handleBinding(player, item);
    }

    /**
     * Handle NORMAL RIGHT-CLICK (reel-in) for teleportation.
     * This uses PlayerFishEvent which fires when rod is cast/reeled.
     *
     * @param event The player fish event
     */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerFish(PlayerFishEvent event) {
        Player player = event.getPlayer();
        ItemStack rod = player.getInventory().getItemInMainHand();

        // Check if this is the main hand rod
        if (rod.getType() != Material.FISHING_ROD) {
            rod = player.getInventory().getItemInOffHand();
            if (rod.getType() != Material.FISHING_ROD) {
                return;
            }
        }

        // Only proceed if the rod has a bound pearl
        if (!PearlDataUtil.hasBoundPearl(rod)) {
            return;
        }

        // Check if this is a reel-in action (not casting)
        // State can be: FISHING, CAUGHT_FISH, CAUGHT_ENTITY, IN_GROUND, REEL_IN, etc.
        PlayerFishEvent.State state = event.getState();

        // Handle teleportation on any reel-in action
        if (state == PlayerFishEvent.State.REEL_IN ||
            state == PlayerFishEvent.State.IN_GROUND ||
            state == PlayerFishEvent.State.FAILED_ATTEMPT) {

            // Cancel normal fishing behavior
            event.setCancelled(true);

            // Handle teleportation
            TeleportHandler.handleTeleport(player, rod);
        }
    }
}
