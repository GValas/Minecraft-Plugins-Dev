package dev.xoperr.betterstasis.handler;

import dev.xoperr.betterstasis.BetterStasisPlugin;
import dev.xoperr.betterstasis.util.FeedbackUtil;
import dev.xoperr.betterstasis.util.PearlDataUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handler for managing pearl sharing operations.
 */
public class SharingHandler {

    /**
     * Share a rod with another player.
     *
     * @param owner The player who owns the rod
     * @param rod The fishing rod to share
     * @param targetName The name of the player to share with
     */
    public static void shareRod(Player owner, ItemStack rod, String targetName) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            FeedbackUtil.sendMessage(owner,
                "You must be holding a fishing rod!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        if (!PearlDataUtil.hasBoundPearl(rod)) {
            FeedbackUtil.sendMessage(owner,
                "This rod is not bound to a pearl!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Set owner if not set (backward compatibility)
        UUID rodOwner = PearlDataUtil.getRodOwner(rod);
        if (rodOwner == null) {
            PearlDataUtil.setRodOwner(rod, owner.getUniqueId());
            rodOwner = owner.getUniqueId();
        }

        // Validate ownership
        if (!rodOwner.equals(owner.getUniqueId())) {
            FeedbackUtil.sendMessage(owner,
                "You don't own this rod!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Find target player
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            FeedbackUtil.sendMessage(owner,
                "Player '" + targetName + "' not found!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Can't share with yourself
        if (target.getUniqueId().equals(owner.getUniqueId())) {
            FeedbackUtil.sendMessage(owner,
                "You already own this rod!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Get current shared list
        List<UUID> sharedPlayers = PearlDataUtil.getSharedPlayers(rod);

        // Check if already shared
        if (sharedPlayers.contains(target.getUniqueId())) {
            FeedbackUtil.sendMessage(owner,
                "This rod is already shared with " + target.getName() + "!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Check max limit
        int maxShared = BetterStasisPlugin.getInstance()
            .getConfig().getInt("settings.pearl-sharing.max-shared-players", 5);

        if (sharedPlayers.size() >= maxShared) {
            FeedbackUtil.sendMessage(owner,
                "Maximum shared players reached (" + maxShared + ")!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Add to shared list
        sharedPlayers.add(target.getUniqueId());
        PearlDataUtil.setSharedPlayers(rod, sharedPlayers);

        // Update lore
        updateRodLore(rod);

        // Feedback
        FeedbackUtil.sendMessage(owner,
            "Successfully shared rod with " + target.getName() + "!",
            FeedbackUtil.MessageType.SUCCESS);
        FeedbackUtil.playSound(owner, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f);

        FeedbackUtil.sendMessage(target,
            owner.getName() + " shared a bound fishing rod with you!",
            FeedbackUtil.MessageType.SUCCESS);
        FeedbackUtil.playSound(target, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f);
    }

    /**
     * Unshare a rod from a player.
     *
     * @param owner The player who owns the rod
     * @param rod The fishing rod to unshare
     * @param targetName The name of the player to unshare from
     */
    public static void unshareRod(Player owner, ItemStack rod, String targetName) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            FeedbackUtil.sendMessage(owner,
                "You must be holding a fishing rod!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        if (!PearlDataUtil.hasBoundPearl(rod)) {
            FeedbackUtil.sendMessage(owner,
                "This rod is not bound to a pearl!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Validate ownership
        UUID rodOwner = PearlDataUtil.getRodOwner(rod);
        if (rodOwner == null || !rodOwner.equals(owner.getUniqueId())) {
            FeedbackUtil.sendMessage(owner,
                "You don't own this rod!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Find target player (try online first, then use UUID lookup for offline)
        Player target = Bukkit.getPlayer(targetName);
        UUID targetUUID = null;

        if (target != null) {
            targetUUID = target.getUniqueId();
        } else {
            // Try to find UUID from shared list by name
            List<UUID> sharedPlayers = PearlDataUtil.getSharedPlayers(rod);
            for (UUID uuid : sharedPlayers) {
                Player offlineCheck = Bukkit.getPlayer(uuid);
                if (offlineCheck != null && offlineCheck.getName().equalsIgnoreCase(targetName)) {
                    targetUUID = uuid;
                    break;
                }
            }
        }

        if (targetUUID == null) {
            FeedbackUtil.sendMessage(owner,
                "Player '" + targetName + "' not found in shared list!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Get current shared list
        List<UUID> sharedPlayers = PearlDataUtil.getSharedPlayers(rod);

        // Check if not shared
        if (!sharedPlayers.contains(targetUUID)) {
            FeedbackUtil.sendMessage(owner,
                "This rod is not shared with " + targetName + "!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(owner, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        // Remove from shared list
        sharedPlayers.remove(targetUUID);
        PearlDataUtil.setSharedPlayers(rod, sharedPlayers);

        // Update lore
        updateRodLore(rod);

        // Feedback
        FeedbackUtil.sendMessage(owner,
            "Successfully unshared rod from " + targetName + "!",
            FeedbackUtil.MessageType.SUCCESS);
        FeedbackUtil.playSound(owner, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f);

        if (target != null && target.isOnline()) {
            FeedbackUtil.sendMessage(target,
                owner.getName() + " unshared a fishing rod from you.",
                FeedbackUtil.MessageType.INFO);
        }
    }

    /**
     * List all players who can use the rod.
     *
     * @param player The player viewing the list
     * @param rod The fishing rod
     */
    public static void listSharedPlayers(Player player, ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            FeedbackUtil.sendMessage(player,
                "You must be holding a fishing rod!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        if (!PearlDataUtil.hasBoundPearl(rod)) {
            FeedbackUtil.sendMessage(player,
                "This rod is not bound to a pearl!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return;
        }

        UUID owner = PearlDataUtil.getRodOwner(rod);
        List<UUID> sharedPlayers = PearlDataUtil.getSharedPlayers(rod);

        // Build message
        player.sendMessage(ChatColor.DARK_PURPLE + "===== " + ChatColor.LIGHT_PURPLE +
            "Rod Sharing Info" + ChatColor.DARK_PURPLE + " =====");

        if (owner != null) {
            Player ownerPlayer = Bukkit.getPlayer(owner);
            String ownerName = ownerPlayer != null ? ownerPlayer.getName() : "Unknown";
            player.sendMessage(ChatColor.YELLOW + "Owner: " + ChatColor.WHITE + ownerName);
        } else {
            player.sendMessage(ChatColor.YELLOW + "Owner: " + ChatColor.GRAY + "None (legacy rod)");
        }

        if (sharedPlayers.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "Shared with: " + ChatColor.GRAY + "None");
        } else {
            player.sendMessage(ChatColor.YELLOW + "Shared with " + sharedPlayers.size() + " player(s):");
            for (UUID uuid : sharedPlayers) {
                Player sharedPlayer = Bukkit.getPlayer(uuid);
                String name = sharedPlayer != null ? sharedPlayer.getName() : uuid.toString();
                player.sendMessage(ChatColor.GRAY + "  - " + ChatColor.WHITE + name);
            }
        }

        FeedbackUtil.playSound(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f);
    }

    /**
     * Update the lore on a rod to reflect sharing status.
     *
     * @param rod The fishing rod to update
     */
    public static void updateRodLore(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return;
        }

        if (!PearlDataUtil.hasBoundPearl(rod)) {
            return;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return;

        // Check if info should be shown
        boolean showInfo = BetterStasisPlugin.getInstance()
            .getConfig().getBoolean("settings.rod-appearance.show-information", true);
        boolean hideInfo = PearlDataUtil.isInfoHidden(rod);

        if (!showInfo || hideInfo) {
            return; // Don't update lore if hidden
        }

        Location loc = PearlDataUtil.getBoundLocation(rod);
        List<UUID> sharedPlayers = PearlDataUtil.getSharedPlayers(rod);

        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.LIGHT_PURPLE + "Bound Pearl");

        if (loc != null) {
            lore.add(ChatColor.GRAY + String.format("X: %.1f Y: %.1f Z: %.1f",
                loc.getX(), loc.getY(), loc.getZ()));
        }

        if (!sharedPlayers.isEmpty()) {
            lore.add(ChatColor.AQUA + "Shared with " + sharedPlayers.size() + " player(s)");
        }

        meta.setLore(lore);
        meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);

        rod.setItemMeta(meta);
    }
}
