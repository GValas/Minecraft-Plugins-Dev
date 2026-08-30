package superslip;

import java.util.List;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class SuperSlip extends JavaPlugin implements Listener {

    private NamespacedKey cleSlip;
    // Interrupteur global (/slip on|off). Actif par defaut, non persiste : retour a "on" a chaque demarrage.
    private boolean actif = true;

    @Override
    public void onEnable() {
        cleSlip = new NamespacedKey(this, "slip");
        getServer().getPluginManager().registerEvents(this, this);
        // Les joueurs deja en ligne (reload) recoivent aussi leur slip.
        getServer().getOnlinePlayers().forEach(this::equiperSiTeteNue);
        getLogger().info("Slips distribues avec fierte.");
    }

    // --- Le slip : casque en cuir blanc, marque via PersistentData pour ne jamais le confondre avec un vrai casque ---

    private ItemStack creerSlip() {
        ItemStack slip = new ItemStack(Material.LEATHER_HELMET);
        LeatherArmorMeta meta = (LeatherArmorMeta) slip.getItemMeta();
        meta.setColor(Color.WHITE);
        meta.displayName(Component.text("Slip", NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(Component.text("Porte avec fierte.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, true)));
        meta.setUnbreakable(true);
        meta.getPersistentDataContainer().set(cleSlip, PersistentDataType.BYTE, (byte) 1);
        slip.setItemMeta(meta);
        return slip;
    }

    private boolean estUnSlip(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(cleSlip, PersistentDataType.BYTE);
    }

    private void equiperSiTeteNue(Player joueur) {
        if (!actif) return;
        ItemStack casque = joueur.getInventory().getHelmet();
        if (casque == null || casque.getType() == Material.AIR) {
            joueur.getInventory().setHelmet(creerSlip());
        }
    }

    // --- Distribution automatique ---

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        equiperSiTeteNue(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        // L'inventaire n'est pret qu'au tick suivant le respawn.
        getServer().getScheduler().runTask(this, () -> equiperSiTeteNue(event.getPlayer()));
    }

    // --- Anti-farm : le slip ne se duplique pas ---

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(this::estUnSlip);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (!estUnSlip(event.getItemDrop().getItemStack())) return;
        event.getItemDrop().remove();
        event.getPlayer().sendMessage(Component.text("Le slip s'envole et disparait...", NamedTextColor.GRAY));
    }

    // --- /slip [joueur|all] ---

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player joueur)) {
                sender.sendMessage(Component.text("Console : /slip <joueur|all>", NamedTextColor.RED));
                return true;
            }
            basculer(joueur);
            return true;
        }
        if (!sender.isOp()) {
            sender.sendMessage(Component.text("Seul un op peut slipper les autres.", NamedTextColor.RED));
            return true;
        }
        if (args[0].equalsIgnoreCase("on")) {
            actif = true;
            getServer().getOnlinePlayers().forEach(this::equiperSiTeteNue);
            sender.sendMessage(Component.text("SuperSlip active : slips redistribues.", NamedTextColor.GREEN));
            return true;
        }
        if (args[0].equalsIgnoreCase("off")) {
            actif = false;
            // On retire les slips en cours de port (uniquement les slips, jamais un vrai casque).
            getServer().getOnlinePlayers().forEach(j -> {
                if (estUnSlip(j.getInventory().getHelmet())) {
                    j.getInventory().setHelmet(null);
                }
            });
            sender.sendMessage(Component.text("SuperSlip desactive : slips retires.", NamedTextColor.YELLOW));
            return true;
        }
        if (args[0].equalsIgnoreCase("all")) {
            if (!actif) {
                sender.sendMessage(Component.text("SuperSlip est desactive (/slip on pour le reactiver).", NamedTextColor.RED));
                return true;
            }
            getServer().getOnlinePlayers().forEach(this::equiperSiTeteNue);
            sender.sendMessage(Component.text("Slips distribues a tous les joueurs a la tete nue.", NamedTextColor.GREEN));
            return true;
        }
        Player cible = getServer().getPlayerExact(args[0]);
        if (cible == null) {
            sender.sendMessage(Component.text("Joueur introuvable : " + args[0], NamedTextColor.RED));
            return true;
        }
        basculer(cible);
        sender.sendMessage(Component.text("Slip bascule pour " + cible.getName() + ".", NamedTextColor.GREEN));
        return true;
    }

    private void basculer(Player joueur) {
        ItemStack casque = joueur.getInventory().getHelmet();
        if (estUnSlip(casque)) {
            joueur.getInventory().setHelmet(null);
            joueur.sendMessage(Component.text("Slip retire. Quel dommage.", NamedTextColor.GRAY));
        } else if (!actif) {
            joueur.sendMessage(Component.text("SuperSlip est desactive (/slip on pour le reactiver).", NamedTextColor.RED));
        } else if (casque == null || casque.getType() == Material.AIR) {
            joueur.getInventory().setHelmet(creerSlip());
            joueur.sendMessage(Component.text("Slip enfile sur la tete !", NamedTextColor.WHITE));
        } else {
            joueur.sendMessage(Component.text("Retire d'abord ton casque pour porter le slip.", NamedTextColor.RED));
        }
    }
}
