package dev.xoperr.betterstasis.util;

import dev.xoperr.betterstasis.BetterStasisPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Utility class for managing pearl location data in fishing rods using PersistentDataContainer.
 */
public class PearlDataUtil {

    // PDC keys for storing pearl location in fishing rod
    private static final NamespacedKey PEARL_X_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "pearl_x");
    private static final NamespacedKey PEARL_Y_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "pearl_y");
    private static final NamespacedKey PEARL_Z_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "pearl_z");
    private static final NamespacedKey PEARL_WORLD_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "pearl_world");
    private static final NamespacedKey PEARL_YAW_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "pearl_yaw");
    private static final NamespacedKey PEARL_PITCH_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "pearl_pitch");
    private static final NamespacedKey ROD_OWNER_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "rod_owner");
    private static final NamespacedKey SHARED_PLAYERS_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "shared_players");
    private static final NamespacedKey HIDE_INFO_KEY =
        new NamespacedKey(BetterStasisPlugin.getInstance(), "hide_info");

    /**
     * Bind a pearl location to a fishing rod item.
     *
     * @param rod The fishing rod to bind the location to
     * @param location The location to bind
     * @param ownerUUID The UUID of the player binding the pearl
     */
    public static void bindPearlLocation(ItemStack rod, Location location, UUID ownerUUID) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        // Store location data in PDC
        pdc.set(PEARL_X_KEY, PersistentDataType.DOUBLE, location.getX());
        pdc.set(PEARL_Y_KEY, PersistentDataType.DOUBLE, location.getY());
        pdc.set(PEARL_Z_KEY, PersistentDataType.DOUBLE, location.getZ());
        pdc.set(PEARL_WORLD_KEY, PersistentDataType.STRING,
                location.getWorld().getName());
        pdc.set(PEARL_YAW_KEY, PersistentDataType.FLOAT, location.getYaw());
        pdc.set(PEARL_PITCH_KEY, PersistentDataType.FLOAT, location.getPitch());

        // Set owner
        pdc.set(ROD_OWNER_KEY, PersistentDataType.STRING, ownerUUID.toString());

        // Check if rod information should be displayed
        boolean showInfo = BetterStasisPlugin.getInstance()
            .getConfig().getBoolean("settings.rod-appearance.show-information", true);
        boolean hideInfo = isInfoHidden(rod);

        if (showInfo && !hideInfo) {
            // Update lore to show it's bound
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.LIGHT_PURPLE + "Bound Pearl");
            lore.add(ChatColor.GRAY + String.format("X: %.1f Y: %.1f Z: %.1f",
                location.getX(), location.getY(), location.getZ()));

            // Add sharing count if any
            List<UUID> sharedPlayers = getSharedPlayers(rod);
            if (!sharedPlayers.isEmpty()) {
                lore.add(ChatColor.AQUA + "Shared with " + sharedPlayers.size() + " player(s)");
            }

            meta.setLore(lore);

            // Add enchantment glint for visual indicator
            meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        rod.setItemMeta(meta);
    }

    /**
     * Check if a fishing rod has a bound pearl.
     *
     * @param rod The fishing rod to check
     * @return true if the rod has a bound pearl, false otherwise
     */
    public static boolean hasBoundPearl(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return false;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        return pdc.has(PEARL_X_KEY, PersistentDataType.DOUBLE);
    }

    /**
     * Retrieve the bound pearl location from a fishing rod.
     *
     * @param rod The fishing rod to retrieve the location from
     * @return The bound location, or null if no binding exists or world doesn't exist
     */
    public static Location getBoundLocation(ItemStack rod) {
        if (!hasBoundPearl(rod)) return null;

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        String worldName = pdc.get(PEARL_WORLD_KEY, PersistentDataType.STRING);
        World world = Bukkit.getWorld(worldName);

        if (world == null) return null; // World no longer exists

        double x = pdc.get(PEARL_X_KEY, PersistentDataType.DOUBLE);
        double y = pdc.get(PEARL_Y_KEY, PersistentDataType.DOUBLE);
        double z = pdc.get(PEARL_Z_KEY, PersistentDataType.DOUBLE);
        float yaw = pdc.get(PEARL_YAW_KEY, PersistentDataType.FLOAT);
        float pitch = pdc.get(PEARL_PITCH_KEY, PersistentDataType.FLOAT);

        return new Location(world, x, y, z, yaw, pitch);
    }

    /**
     * Clear the binding from a fishing rod.
     *
     * @param rod The fishing rod to clear the binding from
     */
    public static void clearBinding(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        // Remove all pearl data from PDC
        pdc.remove(PEARL_X_KEY);
        pdc.remove(PEARL_Y_KEY);
        pdc.remove(PEARL_Z_KEY);
        pdc.remove(PEARL_WORLD_KEY);
        pdc.remove(PEARL_YAW_KEY);
        pdc.remove(PEARL_PITCH_KEY);
        pdc.remove(ROD_OWNER_KEY);
        pdc.remove(SHARED_PLAYERS_KEY);
        pdc.remove(HIDE_INFO_KEY);

        // Check if rod information was displayed and should be removed
        boolean showInfo = BetterStasisPlugin.getInstance()
            .getConfig().getBoolean("settings.rod-appearance.show-information", true);

        if (showInfo) {
            // Remove lore and enchantment glint
            meta.setLore(null);
            meta.removeEnchant(Enchantment.LUCK_OF_THE_SEA);
            meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        rod.setItemMeta(meta);
    }

    /**
     * Set the owner of a fishing rod.
     *
     * @param rod The fishing rod
     * @param ownerUUID The UUID of the owner
     */
    public static void setRodOwner(ItemStack rod, UUID ownerUUID) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        pdc.set(ROD_OWNER_KEY, PersistentDataType.STRING, ownerUUID.toString());

        rod.setItemMeta(meta);
    }

    /**
     * Get the owner of a fishing rod.
     *
     * @param rod The fishing rod
     * @return The UUID of the owner, or null if no owner is set
     */
    public static UUID getRodOwner(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return null;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return null;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String ownerStr = pdc.get(ROD_OWNER_KEY, PersistentDataType.STRING);

        if (ownerStr == null) return null;

        try {
            return UUID.fromString(ownerStr);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Set the list of players who can use this rod.
     *
     * @param rod The fishing rod
     * @param sharedPlayers List of UUIDs of players who can use the rod
     */
    public static void setSharedPlayers(ItemStack rod, List<UUID> sharedPlayers) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (sharedPlayers.isEmpty()) {
            pdc.remove(SHARED_PLAYERS_KEY);
        } else {
            String sharedStr = sharedPlayers.stream()
                .map(UUID::toString)
                .collect(Collectors.joining(","));
            pdc.set(SHARED_PLAYERS_KEY, PersistentDataType.STRING, sharedStr);
        }

        rod.setItemMeta(meta);
    }

    /**
     * Get the list of players who can use this rod.
     *
     * @param rod The fishing rod
     * @return List of UUIDs of players who can use the rod
     */
    public static List<UUID> getSharedPlayers(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return new ArrayList<>();
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return new ArrayList<>();

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String sharedStr = pdc.get(SHARED_PLAYERS_KEY, PersistentDataType.STRING);

        if (sharedStr == null || sharedStr.isEmpty()) {
            return new ArrayList<>();
        }

        return Arrays.stream(sharedStr.split(","))
            .map(s -> {
                try {
                    return UUID.fromString(s);
                } catch (IllegalArgumentException e) {
                    return null;
                }
            })
            .filter(uuid -> uuid != null)
            .collect(Collectors.toList());
    }

    /**
     * Check if a player can use a fishing rod.
     * A player can use a rod if:
     * - They are the owner
     * - They are in the shared players list
     * - The rod has no owner (legacy support)
     *
     * @param rod The fishing rod
     * @param playerUUID The UUID of the player
     * @return true if the player can use the rod, false otherwise
     */
    public static boolean canUseRod(ItemStack rod, UUID playerUUID) {
        UUID owner = getRodOwner(rod);

        // No owner = legacy rod, anyone can use
        if (owner == null) {
            return true;
        }

        // Owner can always use
        if (owner.equals(playerUUID)) {
            return true;
        }

        // Check if player is in shared list
        List<UUID> sharedPlayers = getSharedPlayers(rod);
        return sharedPlayers.contains(playerUUID);
    }

    /**
     * Set whether rod information should be hidden.
     *
     * @param rod The fishing rod
     * @param hidden true to hide information, false to show
     */
    public static void setInfoHidden(ItemStack rod, boolean hidden) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();

        if (hidden) {
            pdc.set(HIDE_INFO_KEY, PersistentDataType.BYTE, (byte) 1);
        } else {
            pdc.remove(HIDE_INFO_KEY);
        }

        rod.setItemMeta(meta);
    }

    /**
     * Check if rod information is hidden.
     *
     * @param rod The fishing rod
     * @return true if information is hidden, false otherwise
     */
    public static boolean isInfoHidden(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            return false;
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return false;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        Byte hidden = pdc.get(HIDE_INFO_KEY, PersistentDataType.BYTE);

        return hidden != null && hidden == 1;
    }

    /**
     * Toggle the visibility of rod information.
     *
     * @param rod The fishing rod
     */
    public static void toggleInfoHidden(ItemStack rod) {
        boolean currentlyHidden = isInfoHidden(rod);
        setInfoHidden(rod, !currentlyHidden);

        // Update visual appearance
        if (!hasBoundPearl(rod)) {
            return; // Nothing to update if not bound
        }

        ItemMeta meta = rod.getItemMeta();
        if (meta == null) return;

        boolean showInfo = BetterStasisPlugin.getInstance()
            .getConfig().getBoolean("settings.rod-appearance.show-information", true);

        if (showInfo && currentlyHidden) {
            // Was hidden, now showing - add lore and enchant
            Location loc = getBoundLocation(rod);
            if (loc != null) {
                List<String> lore = new ArrayList<>();
                lore.add(ChatColor.LIGHT_PURPLE + "Bound Pearl");
                lore.add(ChatColor.GRAY + String.format("X: %.1f Y: %.1f Z: %.1f",
                    loc.getX(), loc.getY(), loc.getZ()));

                List<UUID> sharedPlayers = getSharedPlayers(rod);
                if (!sharedPlayers.isEmpty()) {
                    lore.add(ChatColor.AQUA + "Shared with " + sharedPlayers.size() + " player(s)");
                }

                meta.setLore(lore);
                meta.addEnchant(Enchantment.LUCK_OF_THE_SEA, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
        } else {
            // Now hiding - remove lore and enchant
            meta.setLore(null);
            meta.removeEnchant(Enchantment.LUCK_OF_THE_SEA);
            meta.removeItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        rod.setItemMeta(meta);
    }
}
