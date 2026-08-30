package kamoof.ritual;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.profile.PlayerProfile;

import java.util.ArrayList;
import java.util.List;

import static kamoof.ritual.RitualManager.armorStands;

/**
 * Declenchement du rituel (pose des tetes sur les armor stands) + entretien du
 * pacte Oublie (Weakness reappliquee). Porte de RitualListener S2.
 * La mort (drop de tetes / retrait du modifier) est geree dans KamoofLite.onDeath.
 */
public final class RitualListener implements Listener {

    @EventHandler
    public void onPlaceHead(PlayerArmorStandManipulateEvent event) {
        Player player = event.getPlayer();
        ArmorStand entity = event.getRightClicked();

        // Animation en cours -> stands verrouilles.
        if (Boolean.TRUE.equals(entity.getPersistentDataContainer()
                .getOrDefault(RitualManager.KEY, PersistentDataType.BOOLEAN, false))) {
            event.setCancelled(true);
            return;
        }

        boolean contains = false;
        for (ArmorStand stand : armorStands) {
            if (stand.getEntityId() == entity.getEntityId()) { contains = true; break; }
        }
        if (!contains) {
            // Stand marque mais absent de la liste (entites chargees apres le
            // onEnable, ou liste perdue) -> on retente un scan complet.
            if (!entity.getPersistentDataContainer().has(RitualManager.KEY)) return;
            if (!RitualManager.rescan()) return;
            for (ArmorStand stand : armorStands) {
                if (stand.getEntityId() == entity.getEntityId()) { contains = true; break; }
            }
            if (!contains) return;
        }

        ItemStack playerItem = event.getPlayerItem();
        if (playerItem.getType() != Material.PLAYER_HEAD) {
            // Autorise le retrait d'une tete a main vide ; interdit tout autre item.
            if (entity.getEquipment() != null && entity.getEquipment().getHelmet() != null
                    && entity.getEquipment().getHelmet().getType() == Material.PLAYER_HEAD
                    && playerItem.getType() == Material.AIR) {
                return;
            }
            event.setCancelled(true);
            return;
        }

        long time = player.getWorld().getTime();
        if (time < RitualManager.MIN_TIME || time > RitualManager.MAX_TIME) {
            RitualManager.send(player, RitualManager.MSG_WRONG_TIME);
            event.setCancelled(true);
            return;
        }

        String comparable = headName(playerItem);
        if (comparable == null) { event.setCancelled(true); return; }

        List<ArmorStand> dupes = new ArrayList<>();
        boolean canRunRitual = true;
        for (ArmorStand stand : armorStands) {
            if (stand.getEntityId() == entity.getEntityId()) continue;
            ItemStack helmet = stand.getEquipment() == null ? null : stand.getEquipment().getHelmet();
            if (helmet == null || !helmet.hasItemMeta() || helmet.getType() != Material.PLAYER_HEAD) {
                canRunRitual = false;
                continue;
            }
            String comparator = headName(helmet);
            if (comparator != null && comparable.equalsIgnoreCase(comparator)) dupes.add(stand);
        }

        if (dupes.size() >= RitualManager.DUPELIMIT) {
            dupes.forEach(stand -> player.spawnParticle(Particle.DUST, stand.getLocation().clone().add(0, 1.9, 0),
                    4, 0, 0, 0, 0, new Particle.DustOptions(Color.ORANGE, 3.0f), true));
            player.spawnParticle(Particle.DUST, entity.getLocation().clone().add(0, 1.9, 0),
                    4, 0, 0, 0, 0, new Particle.DustOptions(Color.ORANGE, 3.0f), true);
            event.setCancelled(true);
            return;
        }

        // La tete se pose normalement (event non annule) ; la 9e lance le rituel.
        if (canRunRitual) RitualManager.runAnimation();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        applyWeakness(event.getPlayer());
    }

    @EventHandler
    public void onMilk(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTaskLater(RitualManager.plugin(), () -> {
            if (player.isOnline()) applyWeakness(player);
        }, 1L);
    }

    @EventHandler
    public void onTotem(EntityResurrectEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        Bukkit.getScheduler().runTaskLater(RitualManager.plugin(), () -> {
            if (player.isOnline()) applyWeakness(player);
        }, 1L);
    }

    private void applyWeakness(Player player) {
        String pacte = RitualManager.getPacte(player);
        if (!"2".equalsIgnoreCase(pacte)) return;
        int level = RitualManager.FORGOTTEN_WEAKNESS - 1;
        if (level >= 0) player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, -1, level));
    }

    // Nom du proprietaire d'une tete (pour la detection de doublons).
    static String headName(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !(item.getItemMeta() instanceof SkullMeta meta)) return null;
        PlayerProfile prof = meta.getOwnerProfile();
        if (prof != null && prof.getName() != null) return prof.getName();
        OfflinePlayer op = meta.getOwningPlayer();
        return op != null ? op.getName() : null;
    }
}
