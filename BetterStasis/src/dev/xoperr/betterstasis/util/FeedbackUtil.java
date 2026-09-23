package dev.xoperr.betterstasis.util;

import dev.xoperr.betterstasis.BetterStasisPlugin;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Utility class for providing user feedback through messages, sounds, and particles.
 */
public class FeedbackUtil {

    /**
     * Message types with associated colors.
     */
    public enum MessageType {
        SUCCESS(ChatColor.GREEN),
        ERROR(ChatColor.RED),
        INFO(ChatColor.YELLOW);

        private final ChatColor color;

        MessageType(ChatColor color) {
            this.color = color;
        }

        public ChatColor getColor() {
            return color;
        }
    }

    /**
     * Send a formatted message to a player.
     *
     * @param player The player to send the message to
     * @param message The message content
     * @param type The message type (determines color)
     */
    public static void sendMessage(Player player, String message, MessageType type) {
        if (!BetterStasisPlugin.getInstance()
                .getConfig().getBoolean("settings.feedback.messages", true)) {
            return;
        }

        String formattedMessage = ChatColor.DARK_PURPLE + "[Better Stasis] " + type.getColor() + message;
        player.sendMessage(formattedMessage);
    }

    /**
     * Play a sound at player's location.
     *
     * @param player The player to play the sound for
     * @param sound The sound to play
     * @param volume The volume of the sound
     * @param pitch The pitch of the sound
     */
    public static void playSound(Player player, Sound sound, float volume, float pitch) {
        if (!BetterStasisPlugin.getInstance()
                .getConfig().getBoolean("settings.feedback.sounds", true)) {
            return;
        }

        player.playSound(player.getLocation(), sound, volume, pitch);
    }

    /**
     * Spawn particle effects at a location.
     *
     * @param location The location to spawn particles at
     * @param particle The particle type
     * @param count The number of particles
     */
    public static void spawnParticles(Location location, Particle particle, int count) {
        if (!BetterStasisPlugin.getInstance()
                .getConfig().getBoolean("settings.feedback.particles", true)) {
            return;
        }

        World world = location.getWorld();
        if (world == null) return;

        world.spawnParticle(particle, location, count, 0.5, 0.5, 0.5, 0.1);
    }
}
