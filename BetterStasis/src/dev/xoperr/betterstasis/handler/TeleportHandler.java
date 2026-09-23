package dev.xoperr.betterstasis.handler;

import dev.xoperr.betterstasis.BetterStasisPlugin;
import dev.xoperr.betterstasis.util.FeedbackUtil;
import dev.xoperr.betterstasis.util.PearlDataUtil;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

/**
 * Handler for teleporting players to bound pearl locations.
 */
public class TeleportHandler {

    /**
     * Handle the teleportation process.
     * Retrieves the bound location, validates it, teleports the player,
     * and optionally clears the binding.
     *
     * @param player The player to teleport
     * @param rod The fishing rod with the bound location
     */
    public static void handleTeleport(Player player, ItemStack rod) {
        // Check permission to use this rod
        if (!PearlDataUtil.canUseRod(rod, player.getUniqueId())) {
            FeedbackUtil.sendMessage(player,
                "You don't have permission to use this rod!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Get bound location
        Location destination = PearlDataUtil.getBoundLocation(rod);

        if (destination == null) {
            FeedbackUtil.sendMessage(player,
                "Invalid pearl location! Binding cleared.",
                FeedbackUtil.MessageType.ERROR);
            PearlDataUtil.clearBinding(rod);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Safety checks
        if (!isSafeLocation(destination)) {
            boolean cancelIfUnsafe = BetterStasisPlugin.getInstance()
                .getConfig().getBoolean("settings.teleport.cancel-if-unsafe", true);

            if (cancelIfUnsafe) {
                FeedbackUtil.sendMessage(player,
                    "Destination is unsafe! Teleport cancelled.",
                    FeedbackUtil.MessageType.ERROR);
                FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
                return;
            }
        }

        // Store original location for particle effects
        Location originalLoc = player.getLocation().clone();

        // Charge for teleport if economy is enabled
        if (EconomyHandler.isEconomyEnabled()) {
            if (!EconomyHandler.chargeTeleport(player, originalLoc, destination)) {
                FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
                return;
            }
        }

        // Perform teleport
        boolean success = player.teleport(destination,
            PlayerTeleportEvent.TeleportCause.ENDER_PEARL);

        if (success) {
            // Apply rod durability damage if enabled
            boolean useDurability = BetterStasisPlugin.getInstance()
                .getConfig().getBoolean("settings.rod-appearance.use-durability", false);

            if (useDurability) {
                ItemMeta meta = rod.getItemMeta();
                if (meta instanceof Damageable) {
                    Damageable damageable = (Damageable) meta;
                    int currentDamage = damageable.getDamage();
                    damageable.setDamage(currentDamage + 1);
                    rod.setItemMeta(meta);

                    // Check if rod broke
                    if (damageable.getDamage() >= rod.getType().getMaxDurability()) {
                        rod.setAmount(0); // Remove the rod from inventory
                        FeedbackUtil.sendMessage(player,
                            "Your fishing rod broke!",
                            FeedbackUtil.MessageType.ERROR);
                        FeedbackUtil.playSound(player, Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    }
                }
            }

            // Check if binding should be cleared
            boolean clearAfterTeleport = BetterStasisPlugin.getInstance()
                .getConfig().getBoolean("settings.player-binding.clear-after-teleport", true);

            if (clearAfterTeleport) {
                PearlDataUtil.clearBinding(rod);
            }

            // Provide feedback
            FeedbackUtil.sendMessage(player,
                "Teleported to pearl location!",
                FeedbackUtil.MessageType.SUCCESS);

            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 1.0f);
            FeedbackUtil.spawnParticles(destination, Particle.PORTAL, 100);
            FeedbackUtil.spawnParticles(originalLoc, Particle.PORTAL, 50);

            // No damage applied (per user preference)

        } else {
            // Refund on failed teleport
            if (EconomyHandler.isEconomyEnabled()) {
                double cost = EconomyHandler.calculateTeleportCost(originalLoc, destination);
                EconomyHandler.refundTeleport(player, cost);
            }

            FeedbackUtil.sendMessage(player,
                "Teleport failed! Try again.",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
        }
    }

    /**
     * Check if a location is safe for teleportation.
     *
     * @param loc The location to check
     * @return true if the location is safe, false otherwise
     */
    private static boolean isSafeLocation(Location loc) {
        boolean checkSafe = BetterStasisPlugin.getInstance()
            .getConfig().getBoolean("settings.teleport.check-safe-location", true);

        if (!checkSafe) {
            return true; // Skip safety checks if disabled
        }

        World world = loc.getWorld();
        if (world == null) {
            return false;
        }

        // Check if Y is within world bounds
        if (loc.getY() < world.getMinHeight() || loc.getY() > world.getMaxHeight()) {
            return false;
        }

        boolean preventSuffocation = BetterStasisPlugin.getInstance()
            .getConfig().getBoolean("settings.teleport.prevent-suffocation", true);

        if (preventSuffocation) {
            // Check if blocks at feet and head are passable
            Block feetBlock = loc.getBlock();
            Block headBlock = loc.clone().add(0, 1, 0).getBlock();

            if (feetBlock.getType().isSolid() || headBlock.getType().isSolid()) {
                return false;
            }
        }

        return true;
    }
}
