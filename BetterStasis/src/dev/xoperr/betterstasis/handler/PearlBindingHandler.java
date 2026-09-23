package dev.xoperr.betterstasis.handler;

import dev.xoperr.betterstasis.BetterStasisPlugin;
import dev.xoperr.betterstasis.util.FeedbackUtil;
import dev.xoperr.betterstasis.util.PearlDataUtil;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.Collection;

/**
 * Handler for binding ender pearls to fishing rods.
 */
public class PearlBindingHandler {

    /**
     * Handle the pearl binding process.
     * Searches for nearby ender pearls, finds the closest valid one,
     * binds it to the fishing rod, and removes the pearl entity.
     *
     * @param player The player binding the pearl
     * @param rod The fishing rod to bind to
     */
    public static void handleBinding(Player player, ItemStack rod) {
        // Check if rod is already bound
        if (PearlDataUtil.hasBoundPearl(rod)) {
            FeedbackUtil.sendMessage(player,
                "This rod is already bound! Use it to teleport first.",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Charge for binding if economy is enabled
        if (EconomyHandler.isEconomyEnabled()) {
            if (!EconomyHandler.chargeBinding(player)) {
                FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
                return;
            }
        }

        // Get search radius from config
        double searchRadius = BetterStasisPlugin.getInstance()
            .getConfig().getDouble("settings.search-radius", 8.0);
        double minDistance = BetterStasisPlugin.getInstance()
            .getConfig().getDouble("settings.min-pearl-distance", 2.0);

        // Find nearby ender pearls
        Location playerLoc = player.getLocation();
        Collection<Entity> nearbyEntities = playerLoc.getWorld()
            .getNearbyEntities(playerLoc, searchRadius, searchRadius, searchRadius,
                entity -> entity instanceof EnderPearl);

        if (nearbyEntities.isEmpty()) {
            FeedbackUtil.sendMessage(player,
                "No ender pearl found within " + searchRadius + " blocks!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Find the closest pearl
        EnderPearl closestPearl = null;
        double closestDistance = Double.MAX_VALUE;

        for (Entity entity : nearbyEntities) {
            EnderPearl pearl = (EnderPearl) entity;
            double distance = pearl.getLocation().distance(playerLoc);

            // Skip if too close (would be picked up immediately)
            if (distance < minDistance) {
                continue;
            }

            if (distance < closestDistance) {
                closestDistance = distance;
                closestPearl = pearl;
            }
        }

        if (closestPearl == null) {
            FeedbackUtil.sendMessage(player,
                "Ender pearl too close! Move further away.",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Get pearl location with direction (for teleport orientation)
        Location pearlLocation = closestPearl.getLocation().clone();

        // Set yaw/pitch to face the direction the pearl was traveling
        Vector velocity = closestPearl.getVelocity();
        if (velocity.lengthSquared() > 0.001) {
            Location dirLoc = pearlLocation.clone();
            dirLoc.setDirection(velocity);
            pearlLocation.setYaw(dirLoc.getYaw());
            pearlLocation.setPitch(dirLoc.getPitch());
        } else {
            // If pearl is stationary, use player's current direction
            pearlLocation.setYaw(playerLoc.getYaw());
            pearlLocation.setPitch(playerLoc.getPitch());
        }

        // Bind the pearl location to the rod
        PearlDataUtil.bindPearlLocation(rod, pearlLocation, player.getUniqueId());

        // Remove the pearl entity from the world
        closestPearl.remove();

        // Provide feedback
        FeedbackUtil.sendMessage(player,
            String.format("Pearl bound at X: %.1f Y: %.1f Z: %.1f (%.1f blocks away)",
                pearlLocation.getX(), pearlLocation.getY(), pearlLocation.getZ(),
                closestDistance),
            FeedbackUtil.MessageType.SUCCESS);

        FeedbackUtil.playSound(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f);
        FeedbackUtil.spawnParticles(pearlLocation, Particle.PORTAL, 50);
        FeedbackUtil.spawnParticles(playerLoc, Particle.GLOW, 30);
    }
}
