package kguard;

import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * Checks de combat : reach, killaura (angle / mur / multi-cibles), noswing, autoclicker,
 * criticals, velocity (anti-knockback).
 */
public final class CombatChecks implements Listener {

    private final KamoofGuard plugin;

    public CombatChecks(KamoofGuard plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ clics

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        Player p = event.getPlayer();
        Data d = plugin.data(p);
        long now = System.currentTimeMillis();

        if (d.lastSwingMs > 0) d.pushClickGap(now - d.lastSwingMs);
        d.lastSwingMs = now;
        d.lastSwingTick = Bukkit.getCurrentTick();
        d.swings.addLast(now);
        int cps = Data.trim(d.swings, now, 1000);

        if (!plugin.conf().enabled("autoclicker")) return;
        // Miner envoie un swing par tick : on ne compte que les clics hors minage,
        // sauf si le joueur frappe des entites en ce moment (cas du killaura devant un mur).
        if (now - d.lastAttackMs > 1500 && viseUnBloc(p)) return;
        double maxCps = plugin.conf().num("autoclicker", "max-cps", 16);
        if (cps > maxCps) {
            plugin.flag(p, "autoclicker", cps + " clics/s");
            return;
        }
        // Un humain ne clique jamais avec des intervalles quasi identiques.
        double ecart = d.clickDeviation();
        double minEcart = plugin.conf().num("autoclicker", "min-deviation-ms", 8.0);
        if (ecart >= 0 && ecart < minEcart && cps >= 7) {
            plugin.flag(p, "autoclicker", "intervalles trop reguliers (ecart-type "
                    + Util.fmt(ecart) + " ms, " + cps + " cps)");
        }
    }

    /** Le joueur a-t-il un bloc a portee dans son viseur ? (probable minage) */
    private boolean viseUnBloc(Player p) {
        RayTraceResult r = p.getWorld().rayTraceBlocks(p.getEyeLocation(),
                p.getEyeLocation().getDirection(), 4.5, FluidCollisionMode.NEVER, true);
        return r != null && r.getHitBlock() != null;
    }

    // ------------------------------------------------------------------ attaque

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (!event.willAttack()) return;
        Player p = event.getPlayer();
        Entity cible = event.getAttacked();
        if (cible.equals(p)) return;

        Data d = plugin.data(p);
        long now = System.currentTimeMillis();
        int tick = Bukkit.getCurrentTick();
        boolean annuler = false;

        if (!plugin.exempt(p, "combat")) {
            annuler |= verifierReach(p, d, cible);
            verifierAngle(p, cible);
            verifierMur(p, cible);
            verifierMultiAura(p, d, cible, now);
            verifierNoSwing(p, d, tick);

            verifierCriticals(p, d, tick);
        }

        d.lastTarget = cible.getUniqueId();
        d.lastTargetMs = now;
        d.lastAttackMs = now;
        d.lastAttackTick = tick;

        if (annuler) event.setCancelled(true);
    }

    private boolean verifierReach(Player p, Data d, Entity cible) {
        if (!plugin.conf().enabled("reach")) return false;
        Location oeil = p.getEyeLocation();
        // Tolerance de ping : la cible a pu bouger depuis la position connue du client.
        double compensation = Math.min(0.6, p.getPing() / 1000.0 * 1.2);
        double distance = Util.distanceToBox(oeil, cible, 0.03) - compensation;
        if (distance < 0) distance = 0;

        d.reaches.addLast(distance);
        while (d.reaches.size() > 8) d.reaches.pollFirst();

        double max = plugin.conf().num("reach", "max-distance", 3.5);
        if (p.getGameMode() == GameMode.CREATIVE) max += 2.0;

        if (distance > max) {
            return plugin.flag(p, "reach", Util.fmt(distance) + " blocs", 1);
        }
        if (d.reaches.size() == 8) {
            double moyenne = 0;
            for (double r : d.reaches) moyenne += r;
            moyenne /= d.reaches.size();
            double maxMoyenne = plugin.conf().num("reach", "average-distance", 3.25);
            if (p.getGameMode() == GameMode.CREATIVE) maxMoyenne += 2.0;
            if (moyenne > maxMoyenne) {
                d.reaches.clear();
                return plugin.flag(p, "reach", "moyenne " + Util.fmt(moyenne) + " blocs sur 8 coups", 1);
            }
        }
        return false;
    }

    private void verifierAngle(Player p, Entity cible) {
        if (!plugin.conf().enabled("killaura")) return;
        Location centre = cible.getBoundingBox().getCenter().toLocation(cible.getWorld());
        double angle = Util.angleTo(p, centre);
        double max = plugin.conf().num("killaura", "max-angle", 85.0);
        if (angle > max) {
            plugin.flag(p, "killaura", "cible a " + (int) angle + " degres du regard");
        }
    }

    private void verifierMur(Player p, Entity cible) {
        if (!plugin.conf().enabled("killaura")) return;
        Location oeil = p.getEyeLocation();
        Location centre = cible.getBoundingBox().getCenter().toLocation(cible.getWorld());
        Vector direction = centre.toVector().subtract(oeil.toVector());
        double distance = direction.length();
        if (distance < 0.5 || distance > 6) return;
        RayTraceResult r = p.getWorld().rayTraceBlocks(oeil, direction.normalize(), distance - 0.35,
                FluidCollisionMode.NEVER, true);
        if (r != null && r.getHitBlock() != null) {
            plugin.flag(p, "killaura", "coup a travers " + r.getHitBlock().getType());
        }
    }

    private void verifierMultiAura(Player p, Data d, Entity cible, long now) {
        if (!plugin.conf().enabled("multiaura")) return;
        double fenetre = plugin.conf().num("multiaura", "window-ms", 250);
        if (d.lastTarget != null && !d.lastTarget.equals(cible.getUniqueId())
                && now - d.lastTargetMs < fenetre) {
            plugin.flag(p, "multiaura", "2 cibles en " + (now - d.lastTargetMs) + " ms");
        }
    }

    private void verifierNoSwing(Player p, Data d, int tick) {
        if (!plugin.conf().enabled("noswing")) return;
        // Le client vanilla envoie le paquet d attaque PUIS le paquet de swing dans le meme tick :
        // on ne peut donc juger qu apres coup.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            if (d.lastSwingTick < tick) {
                plugin.flag(p, "noswing", "coup sans animation de bras");
            }
        }, 2L);
    }

    private void verifierCriticals(Player p, Data d, int tick) {
        if (!plugin.conf().enabled("criticals")) return;
        float chute = p.getFallDistance();
        if (chute <= 0 || chute > 0.4 || Util.onGround(p.getLocation(), 0.55)
                || tick - d.lastJumpTick < 12) {
            d.critStreak = 0;
            return;
        }
        if (p.isInsideVehicle() || p.isClimbing() || p.isInWater() || p.isGliding()) return;
        if (p.getPotionEffect(PotionEffectType.LEVITATION) != null) return;
        // Descendre d un bloc en frappant produit la meme signature une fois : on exige une serie.
        d.critStreak++;
        if (d.critStreak >= 3) {
            d.critStreak = 0;
            plugin.flag(p, "criticals", "critiques repetes sans saut (chute " + Util.fmt(chute) + ")");
        }
    }

    // ------------------------------------------------------------------ knockback

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player p = event.getPlayer();
        Data d = plugin.data(p);
        d.lastVelocity = System.currentTimeMillis();

        if (!plugin.conf().enabled("velocity")) return;
        Vector v = event.getVelocity();
        if (v.getX() * v.getX() + v.getZ() * v.getZ() < 0.02) return;   // knockback trop faible pour juger

        d.kbExpected = v.clone();
        d.kbTicks = 0;
        d.kbMovedH = 0;
        Location depart = p.getLocation().clone();

        // On mesure le deplacement horizontal reel sur les 5 ticks suivants.
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline() || d.kbExpected == null) return;
            double attendu = Math.sqrt(d.kbExpected.getX() * d.kbExpected.getX()
                    + d.kbExpected.getZ() * d.kbExpected.getZ()) * 3.2;
            double reel = Util.horizontal(depart, p.getLocation());
            d.kbExpected = null;
            if (attendu < 0.15) return;
            if (contreUnMur(p, v)) return;
            double ratio = reel / attendu;
            if (ratio < plugin.conf().num("velocity", "min-ratio", 0.30)) {
                plugin.flag(p, "velocity", (int) (ratio * 100) + "% du recul subi");
            }
        }, 5L);
    }

    /** Un mur dans la direction du recul explique legitimement un deplacement nul. */
    private boolean contreUnMur(Player p, Vector kb) {
        Vector dir = new Vector(kb.getX(), 0, kb.getZ());
        if (dir.lengthSquared() < 1.0E-6) return true;
        RayTraceResult r = p.getWorld().rayTraceBlocks(p.getLocation().add(0, 0.9, 0), dir.normalize(), 1.2,
                FluidCollisionMode.NEVER, true);
        return r != null && r.getHitBlock() != null;
    }

    // ------------------------------------------------------------------ suivi des degats

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            plugin.data(p).lastDamage = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p && event.getEntity() instanceof LivingEntity) {
            plugin.data(p).lastAttackMs = System.currentTimeMillis();
        }
    }
}
