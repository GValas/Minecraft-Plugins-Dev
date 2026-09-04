package kguard;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Checks divers : objets impossibles en survie, spam du chat, deplacement avec un conteneur ouvert.
 */
public final class MiscChecks implements Listener {

    private final KamoofGuard plugin;

    /** Blocs et objets que la survie ne peut pas produire. */
    private static final Set<Material> INTERDITS = EnumSet.of(
            Material.BEDROCK, Material.BARRIER, Material.LIGHT, Material.STRUCTURE_BLOCK,
            Material.STRUCTURE_VOID, Material.JIGSAW, Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK, Material.REPEATING_COMMAND_BLOCK, Material.COMMAND_BLOCK_MINECART,
            Material.DEBUG_STICK, Material.SPAWNER, Material.END_PORTAL_FRAME, Material.BUDDING_AMETHYST,
            Material.REINFORCED_DEEPSLATE, Material.PETRIFIED_OAK_SLAB, Material.FARMLAND,
            Material.DIRT_PATH, Material.NETHER_PORTAL, Material.END_PORTAL, Material.END_GATEWAY);

    public MiscChecks(KamoofGuard plugin) {
        this.plugin = plugin;
        // Scan periodique des inventaires (toutes les 15 s).
        Bukkit.getScheduler().runTaskTimer(plugin, this::scannerInventaires, 200L, 300L);
    }

    // ------------------------------------------------------------------ objets illegaux

    private void scannerInventaires() {
        if (!plugin.conf().enabled("illegalitems")) return;
        boolean retirer = plugin.conf().flag("illegalitems", "remove", true);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            if (plugin.exempt(p, "illegalitems")) continue;
            ItemStack[] contenu = p.getInventory().getContents();
            for (int i = 0; i < contenu.length; i++) {
                ItemStack item = contenu[i];
                String raison = raisonIllegale(item);
                if (raison == null) continue;
                plugin.flag(p, "illegalitems", item.getType() + " : " + raison);
                if (retirer) p.getInventory().setItem(i, null);
            }
        }
    }

    private String raisonIllegale(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        if (INTERDITS.contains(item.getType())) return "objet reserve au creatif";
        if (item.getAmount() > item.getType().getMaxStackSize()) {
            return "pile de " + item.getAmount() + " (max " + item.getType().getMaxStackSize() + ")";
        }
        for (Map.Entry<Enchantment, Integer> e : item.getEnchantments().entrySet()) {
            if (e.getValue() > e.getKey().getMaxLevel()) {
                return "enchantement " + e.getKey().getKey().getKey() + " niveau " + e.getValue();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ spam du chat

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (!plugin.conf().enabled("chatspam")) return;
        Player p = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        Bukkit.getScheduler().runTask(plugin, () -> {
            Data d = plugin.data(p);
            long now = System.currentTimeMillis();
            long delta = now - d.lastMessageMs;
            double minDelay = plugin.conf().num("chatspam", "min-delay-ms", 500);
            if (d.lastMessageMs > 0 && delta < minDelay) {
                plugin.flag(p, "chatspam", delta + " ms entre deux messages");
            }
            if (message.equalsIgnoreCase(d.lastMessage)) {
                d.messageRepeats++;
                if (d.messageRepeats >= 3) {
                    d.messageRepeats = 0;
                    plugin.flag(p, "chatspam", "message repete 3 fois");
                }
            } else {
                d.messageRepeats = 0;
            }
            d.lastMessage = message;
            d.lastMessageMs = now;
        });
    }

    // ------------------------------------------------------------------ deplacement inventaire ouvert

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        if (event.getInventory().getType() == InventoryType.CRAFTING
                || event.getInventory().getType() == InventoryType.PLAYER) return;
        Data d = plugin.data(p);
        d.inventoryOpenMs = System.currentTimeMillis();
        d.inventoryMoved = 0;
        d.inventoryMoves = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;
        Data d = plugin.data(p);
        d.inventoryOpenMs = 0;
        d.inventoryMoved = 0;
        d.inventoryMoves = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.conf().enabled("inventorymove")) return;
        Player p = event.getPlayer();
        Data d = plugin.data(p);
        if (d.inventoryOpenMs == 0) return;
        // Les 15 premiers ticks servent a absorber l inertie au moment de l ouverture.
        if (System.currentTimeMillis() - d.inventoryOpenMs < 750) return;
        Location to = event.getTo();
        if (to == null) return;
        double h = Util.horizontal(event.getFrom(), to);
        if (h < 0.01) {
            d.inventoryMoves = 0;
            return;
        }
        d.inventoryMoved += h;
        d.inventoryMoves++;
        if (d.inventoryMoves > 12 && d.inventoryMoved > 1.5) {
            d.inventoryMoves = 0;
            d.inventoryMoved = 0;
            plugin.flag(p, "inventorymove", "deplacement avec un conteneur ouvert");
        }
    }
}
