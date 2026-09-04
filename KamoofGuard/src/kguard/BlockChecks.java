package kguard;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.EnumSet;
import java.util.Set;

/**
 * Checks lies aux blocs : fastbreak, nuker, fastplace, scaffold, blockreach, fastuse,
 * plus l heuristique xray (alerte seulement).
 */
public final class BlockChecks implements Listener {

    private final KamoofGuard plugin;

    /** Minerais dont la decouverte repetee sans exploration est suspecte. */
    private static final Set<Material> PRECIEUX = EnumSet.of(
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.ANCIENT_DEBRIS,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE);

    public BlockChecks(KamoofGuard plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ casse

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        Data d = plugin.data(p);
        Block b = event.getBlock();
        long now = System.currentTimeMillis();

        if (verifierBlockReach(p, b.getLocation().add(0.5, 0.5, 0.5), "cassage")) {
            event.setCancelled(true);
            return;
        }

        if (p.getGameMode() == GameMode.SURVIVAL) {
            // fastbreak : cadence de cassage
            if (plugin.conf().enabled("fastbreak") && b.getType().getHardness() >= 1.5f) {
                d.breaks.addLast(now);
                int parSeconde = Data.trim(d.breaks, now, 1000);
                if (parSeconde > plugin.conf().num("fastbreak", "max-per-second", 15)) {
                    plugin.flag(p, "fastbreak", parSeconde + " blocs/s (" + b.getType() + ")");
                }
            }
            // nuker : deux blocs eloignes casses dans le meme instant
            if (plugin.conf().enabled("nuker") && b.getType().getHardness() > 0 && d.lastBreak != null
                    && d.lastBreak.getWorld() == b.getWorld() && now - d.lastBreakMs < 120) {
                double ecart = d.lastBreak.distance(b.getLocation());
                if (ecart > 2.5) {
                    plugin.flag(p, "nuker", "2 blocs a " + Util.fmt(ecart) + " blocs en "
                            + (now - d.lastBreakMs) + " ms");
                }
            }
            verifierXray(p, d, b);
        }

        d.lastBreak = b.getLocation();
        d.lastBreakMs = now;
    }

    /** Heuristique purement statistique : on signale, on ne sanctionne jamais. */
    private void verifierXray(Player p, Data d, Block b) {
        if (!plugin.conf().enabled("xray")) return;
        if (b.getY() > plugin.conf().num("xray", "max-y", 16)) return;
        if (b.getType().getHardness() <= 0) return;

        d.minedDeep++;
        if (PRECIEUX.contains(b.getType())) d.minedOre++;

        int minimum = (int) plugin.conf().num("xray", "min-blocks", 250);
        if (d.minedDeep < minimum) return;
        if (d.minedDeep % 50 != 0) return;

        double ratio = (double) d.minedOre / d.minedDeep;
        if (ratio > plugin.conf().num("xray", "max-ratio", 0.045) && d.xrayReported < 3) {
            d.xrayReported++;
            plugin.flag(p, "xray", d.minedOre + " minerais precieux sur " + d.minedDeep
                    + " blocs sous y=" + (int) plugin.conf().num("xray", "max-y", 16)
                    + " (" + (int) (ratio * 1000) / 10.0 + "%)");
        }
    }

    // ------------------------------------------------------------------ pose

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player p = event.getPlayer();
        Data d = plugin.data(p);
        Block b = event.getBlockPlaced();
        long now = System.currentTimeMillis();

        if (verifierBlockReach(p, b.getLocation().add(0.5, 0.5, 0.5), "pose")) {
            event.setCancelled(true);
            return;
        }

        if (plugin.conf().enabled("fastplace")) {
            d.places.addLast(now);
            int parSeconde = Data.trim(d.places, now, 1000);
            if (parSeconde > plugin.conf().num("fastplace", "max-per-second", 9)) {
                plugin.flag(p, "fastplace", parSeconde + " blocs/s");
            }
        }

        verifierScaffold(p, b);
    }

    /**
     * Scaffold : poser un bloc sous soi en avancant sans regarder vers le bas.
     * Un pont vanilla impose de viser la tranche du bloc precedent, donc un pitch marque.
     */
    private void verifierScaffold(Player p, Block b) {
        if (!plugin.conf().enabled("scaffold")) return;
        Location loc = p.getLocation();
        if (b.getY() >= loc.getBlockY()) return;                       // pas un pont sous les pieds
        if (Math.abs(b.getX() - loc.getBlockX()) > 1 || Math.abs(b.getZ() - loc.getBlockZ()) > 1) return;

        double minPitch = plugin.conf().num("scaffold", "min-pitch", 30.0);
        if (loc.getPitch() < minPitch) {
            plugin.flag(p, "scaffold", "pose sous les pieds avec un pitch de " + (int) loc.getPitch());
            return;
        }
        double angle = Util.angleTo(p, b.getLocation().add(0.5, 0.5, 0.5));
        if (angle > 110) {
            plugin.flag(p, "scaffold", "bloc pose a " + (int) angle + " degres du regard");
        }
    }

    // ------------------------------------------------------------------ portee sur les blocs

    private boolean verifierBlockReach(Player p, Location centre, String quoi) {
        if (!plugin.conf().enabled("blockreach")) return false;
        if (plugin.exempt(p, "blockreach")) return false;
        double max = plugin.conf().num("blockreach", "max-distance", 5.3);
        if (p.getGameMode() == GameMode.CREATIVE) max += 1.0;
        Location oeil = p.getEyeLocation();
        if (oeil.getWorld() != centre.getWorld()) return false;
        double d = oeil.distance(centre) - 0.87;                       // du centre vers la face la plus proche
        if (d <= max) return false;
        return plugin.flag(p, "blockreach", quoi + " a " + Util.fmt(d) + " blocs", 1);
    }

    // ------------------------------------------------------------------ usage d objets

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!plugin.conf().enabled("fastuse")) return;
        Player p = event.getPlayer();
        Data d = plugin.data(p);
        long now = System.currentTimeMillis();
        d.uses.addLast(now);
        int parSeconde = Data.trim(d.uses, now, 1000);
        if (parSeconde > plugin.conf().num("fastuse", "max-per-second", 15)) {
            plugin.flag(p, "fastuse", parSeconde + " utilisations/s");
        }
    }
}
