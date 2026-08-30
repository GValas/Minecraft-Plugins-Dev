package lancesansfaim;

import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

public final class LanceSansFaim extends JavaPlugin implements Listener {

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("Lunge de lance sans cout de faim actif.");
    }

    // Le lunge de la lance depense la faim via l'effet d'enchantement apply_exhaustion,
    // qui arrive ici avec la raison ENCHANTMENT_EFFECT (seul enchantement vanilla a l'utiliser).
    @EventHandler(ignoreCancelled = true)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (event.getExhaustionReason() != EntityExhaustionEvent.ExhaustionReason.ENCHANTMENT_EFFECT) return;
        if (!(event.getEntity() instanceof Player player)) return;
        if (!estLanceAvecLunge(player.getInventory().getItemInMainHand())
                && !estLanceAvecLunge(player.getInventory().getItemInOffHand())) return;
        event.setCancelled(true);
    }

    private boolean estLanceAvecLunge(ItemStack item) {
        return item != null
                && Tag.ITEMS_SPEARS.isTagged(item.getType())
                && item.containsEnchantment(Enchantment.LUNGE);
    }
}
