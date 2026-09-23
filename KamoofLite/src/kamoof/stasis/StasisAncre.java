package kamoof.stasis;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityRemoveEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Stasis chamber a la canne a peche, a distance (v3.8).
 *
 * En vanilla, le bouchon d'une canne disparait des que son proprietaire est a plus de
 * 32 blocs (coordonnees brutes, meme depuis une autre dimension -> dans le Nether, /8).
 * Une chambre dont la trappe est tenue ouverte par un bouchon pose sur une plaque de
 * pression se declenche donc des qu'on s'eloigne ou qu'on passe un portail.
 *
 * Ici : si le bouchon disparait PARCE QUE le joueur est loin / dans un autre monde, on
 * pose a sa place une ancre invisible (armor stand) sur la plaque -> la plaque reste
 * enfoncee, la trappe reste ouverte. Shift + clic droit avec une canne (sans bouchon
 * lance) retire les ancres du joueur -> la plaque se relache -> la perle le ramene.
 * Pres de la chambre (<= 32 blocs, meme monde), le comportement vanilla est inchange.
 */
public class StasisAncre implements Listener {

    private static final double DISTANCE_VANILLA_SQ = 32.0 * 32.0;
    // Duree pendant laquelle on force le chargement du chunk de l'ancre au declenchement
    // (la redstone et la perle doivent tourner meme si personne n'est a cote).
    private static final long TICKET_TICKS = 20L * 15;

    private final JavaPlugin plugin;
    private final NamespacedKey cleProprio;
    private final File fichier;
    // proprietaire -> ancres posees (monde;x;y;z;uuidEntite)
    private final Map<UUID, List<String>> ancres = new HashMap<>();

    public StasisAncre(JavaPlugin plugin) {
        this.plugin = plugin;
        this.cleProprio = new NamespacedKey(plugin, "stasis_owner");
        this.fichier = new File(plugin.getDataFolder(), "stasis.yml");
        charger();
    }

    // ---------------------------------------------------------------------
    // Le bouchon disparait : faut-il le remplacer par une ancre ?
    // ---------------------------------------------------------------------
    @EventHandler(priority = EventPriority.MONITOR)
    public void onHookRemove(EntityRemoveEvent event) {
        if (!(event.getEntity() instanceof FishHook hook)) return;
        if (event.getCause() != EntityRemoveEvent.Cause.DESPAWN) return;

        Block plaque = hook.getLocation().getBlock();
        if (!Tag.PRESSURE_PLATES.isTagged(plaque.getType())) return;

        UUID proprio = hook.getOwnerUniqueId();
        if (proprio == null) return;
        Player joueur = Bukkit.getPlayer(proprio);
        if (joueur == null) return; // deconnexion : la perle part avec le joueur de toute facon

        // Pres de la chambre -> vanilla (ramener la canne / changer d'objet declenche normalement)
        if (joueur.getWorld().equals(plaque.getWorld())
                && joueur.getLocation().distanceSquared(hook.getLocation()) <= DISTANCE_VANILLA_SQ) {
            return;
        }

        // Tick suivant : on ne fait pas apparaitre d'entite pendant le retrait du bouchon.
        Bukkit.getScheduler().runTask(plugin, () -> poserAncre(plaque, proprio, joueur));
    }

    private void poserAncre(Block plaque, UUID proprio, Player joueur) {
        if (!Tag.PRESSURE_PLATES.isTagged(plaque.getType())) return;
        Location loc = plaque.getLocation().add(0.5, 0.0, 0.5);
        ArmorStand ancre = plaque.getWorld().spawn(loc, ArmorStand.class, a -> {
            a.setInvisible(true);
            a.setSmall(true);
            a.setGravity(false);
            a.setInvulnerable(true);
            a.setSilent(true);
            a.setPersistent(true);
            a.setCollidable(false);
            a.setCanPickupItems(false);
            a.setDisabledSlots(org.bukkit.inventory.EquipmentSlot.values());
            a.getPersistentDataContainer().set(cleProprio, PersistentDataType.STRING, proprio.toString());
        });
        ancres.computeIfAbsent(proprio, k -> new ArrayList<>()).add(encoder(loc, ancre.getUniqueId()));
        sauver();
        plugin.getLogger().info("[stasis] ancre posee pour " + joueur.getName() + " en "
                + plaque.getWorld().getName() + " " + plaque.getX() + " " + plaque.getY() + " " + plaque.getZ());
        joueur.sendActionBar(Component.text(
                "Stasis gardee ouverte - Shift + clic droit avec une canne pour revenir",
                NamedTextColor.LIGHT_PURPLE));
    }

    // ---------------------------------------------------------------------
    // Shift + clic droit avec une canne : declenchement a distance
    // ---------------------------------------------------------------------
    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.FISHING_ROD) return;
        Player joueur = event.getPlayer();
        if (!joueur.isSneaking()) return;
        List<String> liste = ancres.get(joueur.getUniqueId());
        if (liste == null || liste.isEmpty()) return;
        // Un bouchon deja lance : on laisse le vanilla le ramener
        if (joueur.getFishHook() != null) return;

        event.setCancelled(true);
        ancres.remove(joueur.getUniqueId());
        sauver();
        for (String s : liste) {
            retirerAncre(s, 0);
        }
        joueur.sendActionBar(Component.text("Stasis declenchee !", NamedTextColor.LIGHT_PURPLE));
        plugin.getLogger().info("[stasis] " + joueur.getName() + " declenche " + liste.size() + " ancre(s).");
    }

    // Retire une ancre ; les entites d'un chunk tout juste charge arrivent en differe
    // sur Paper, d'ou quelques essais.
    private void retirerAncre(String code, int essai) {
        String[] p = code.split(";");
        World w = Bukkit.getWorld(p[0]);
        if (w == null) return;
        int x = (int) Math.floor(Double.parseDouble(p[1]));
        int z = (int) Math.floor(Double.parseDouble(p[3]));
        UUID id = UUID.fromString(p[4]);

        Chunk chunk = w.getChunkAt(x >> 4, z >> 4);
        if (essai == 0) {
            chunk.addPluginChunkTicket(plugin);
            Bukkit.getScheduler().runTaskLater(plugin, () -> chunk.removePluginChunkTicket(plugin), TICKET_TICKS);
        }
        Entity e = Bukkit.getEntity(id);
        if (e != null) {
            e.remove();
            return;
        }
        if (essai < 20) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> retirerAncre(code, essai + 1), 5L);
        } else {
            plugin.getLogger().warning("[stasis] ancre introuvable : " + code);
        }
    }

    // ---------------------------------------------------------------------
    // Persistance (stasis.yml)
    // ---------------------------------------------------------------------
    private static String encoder(Location l, UUID id) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + id;
    }

    private void charger() {
        if (!fichier.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(fichier);
        ConfigurationSection sec = yml.getConfigurationSection("ancres");
        if (sec == null) return;
        for (String k : sec.getKeys(false)) {
            try {
                ancres.put(UUID.fromString(k), new ArrayList<>(sec.getStringList(k)));
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void sauver() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<UUID, List<String>> e : ancres.entrySet()) {
            yml.set("ancres." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            yml.save(fichier);
        } catch (Exception ex) {
            plugin.getLogger().warning("[stasis] sauvegarde impossible : " + ex.getMessage());
        }
    }
}
