package kamoof.ritual;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.structure.Structure;

import java.io.InputStream;
import java.util.List;
import java.util.Random;

/**
 * Items d'installation admin + generation de l'autel du rituel via les structures NBT
 * (porte de RitualSetup S2). Admin = permission kamooflite.admin.
 */
public final class RitualSetup implements Listener {

    private static final NamespacedKey KEY_ITEM = new NamespacedKey("kamooflite", "setupitem");
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    private static Structure structure;
    private static Structure structureBase;

    public static ItemStack[] getItems() {
        return new ItemStack[]{
                item(Material.NETHER_WART_BLOCK, "§ePoser le rituel (autel construit main)",
                        "§7Pose ce bloc au centre de ton autel pour y ancrer le rituel."),
                item(Material.BREEZE_ROD, "§eGenerer l'autel complet",
                        "§7Clic-droit un bloc : construit l'autel du rituel + le rituel."),
                item(Material.BLAZE_ROD, "§eGenerer la base de l'autel",
                        "§7Clic-droit un bloc : construit la base de l'autel + le rituel.")
        };
    }

    private static ItemStack item(Material material, String name, String lore) {
        ItemStack it = new ItemStack(material);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(LEGACY.deserialize(name));
        meta.lore(List.of(LEGACY.deserialize(lore)));
        meta.addEnchant(Enchantment.FROST_WALKER, 1, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        meta.getPersistentDataContainer().set(KEY_ITEM, org.bukkit.persistence.PersistentDataType.BOOLEAN, true);
        it.setItemMeta(meta);
        return it;
    }

    private static boolean isSetupItem(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(KEY_ITEM);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item.getType() != Material.NETHER_WART_BLOCK || !isSetupItem(item)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!RitualManager.hasAdminLevel(player)) {
            player.sendMessage("§cReserve aux administrateurs (niveau " + RitualManager.REQUIRED_OP_LEVEL + "+).");
            return;
        }
        RitualManager.setRitual(event.getBlockPlaced().getLocation(), player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onUseItem(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null) return;
        Material type = item.getType();
        if (type != Material.BREEZE_ROD && type != Material.BLAZE_ROD) return;
        if (!isSetupItem(item)) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!RitualManager.hasAdminLevel(player)) {
            player.sendMessage("§cReserve aux administrateurs (niveau " + RitualManager.REQUIRED_OP_LEVEL + "+).");
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;

        Block clicked = event.getClickedBlock();
        Location loc = clicked.getLocation();
        if (type == Material.BREEZE_ROD) {
            if (structure == null) structure = loadStructure("ritual.nbt");
            if (structure == null) { player.sendMessage("§cStructure ritual.nbt introuvable."); return; }
            structure.place(loc.clone().add(-13, 0, -13), false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
        } else {
            if (structureBase == null) structureBase = loadStructure("ritualbase.nbt");
            if (structureBase == null) { player.sendMessage("§cStructure ritualbase.nbt introuvable."); return; }
            structureBase.place(loc.clone().add(-9, 0, -9), false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
        }
        RitualManager.setRitual(loc.clone().add(0, 2, 0), player);
    }

    // Construit l'autel complet (ritual.nbt) centre sur une position donnee, puis ancre le rituel.
    // Meme geometrie que le baton de brise : centre = coin de structure + (13, 2, 13).
    public static boolean placeFullAltar(Location center, Player player) {
        if (structure == null) structure = loadStructure("ritual.nbt");
        if (structure == null) return false;
        structure.place(center.clone().add(-13, -2, -13), false, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new Random());
        RitualManager.setRitual(center, player);
        return true;
    }

    private static Structure loadStructure(String resource) {
        try (InputStream is = RitualManager.plugin().getResource(resource)) {
            if (is == null) return null;
            return Bukkit.getStructureManager().loadStructure(is);
        } catch (Throwable t) {
            RitualManager.plugin().getLogger().warning("[rituel] chargement " + resource + " echoue: " + t.getMessage());
            return null;
        }
    }
}
