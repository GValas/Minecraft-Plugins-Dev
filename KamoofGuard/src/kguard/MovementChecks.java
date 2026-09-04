package kguard;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

/**
 * Checks de deplacement : fly, gravity, speed, nofall, step, jesus, timer, phase, fastclimb, noslow.
 * Tout part de PlayerMoveEvent : un evenement = un paquet de position du client.
 */
public final class MovementChecks implements Listener {

    private final KamoofGuard plugin;

    public MovementChecks(KamoofGuard plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJump(PlayerJumpEvent event) {
        plugin.data(event.getPlayer()).lastJumpTick = Bukkit.getCurrentTick();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player p = event.getPlayer();
        Data d = plugin.data(p);
        long now = System.currentTimeMillis();

        // --- timer : cadence des paquets de position, y compris les simples rotations ---
        d.moves.addLast(now);
        int recents = Data.trim(d.moves, now, 2000);
        if (plugin.conf().enabled("timer") && !plugin.exemptMovement(p) && Bukkit.getTPS()[0] > 19.0) {
            double max = plugin.conf().num("timer", "max-moves-per-second", 25) * 2;
            if (recents > max) {
                plugin.flag(p, "timer", recents / 2 + " paquets/s");
            }
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) return;
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) return;

        if (plugin.exempt(p, "moving") || plugin.exemptMovement(p)) {
            reinit(d, to);
            return;
        }

        double dy = to.getY() - from.getY();
        double hDist = Util.horizontal(from, to);

        boolean groundTo = Util.onGround(to, 0.55);
        boolean groundFrom = Util.onGround(from, 0.55);
        boolean clientGround = p.isOnGround();
        boolean climbing = p.isClimbing();
        boolean liquide = p.isInWater() || p.isInLava() || to.getBlock().isLiquid();
        boolean bizarre = Util.weirdEnvironment(to);
        boolean effetVertical = p.getPotionEffect(PotionEffectType.LEVITATION) != null
                || p.getPotionEffect(PotionEffectType.SLOW_FALLING) != null;

        if (groundTo) {
            d.airTicks = 0;
            d.ascendTicks = 0;
            d.hoverTicks = 0;
            d.gravityTicks = 0;
            d.airMaxY = to.getY();
        } else {
            d.airTicks++;
            d.airMaxY = Math.max(d.airMaxY, to.getY());
        }

        verifierPhase(p, d, event, from, to);
        verifierFly(p, d, dy, groundTo, climbing, liquide, bizarre, effetVertical);
        verifierGravity(p, d, dy, groundTo, climbing, liquide, bizarre, effetVertical);
        verifierSpeed(p, d, hDist, groundTo, liquide, climbing);
        verifierNoFall(p, d, to, clientGround, climbing, liquide, bizarre);
        verifierStep(p, d, dy, groundFrom, groundTo, climbing, bizarre);
        verifierJesus(p, d, to, dy, hDist, groundTo);
        verifierFastClimb(p, d, dy, climbing);
        verifierNoSlow(p, d, hDist, groundTo, liquide, climbing);

        d.lastDy = dy;
        d.lastTo = to.clone();
    }

    private void reinit(Data d, Location to) {
        d.lastTo = to.clone();
        d.lastDy = 0;
        d.airTicks = 0;
        d.ascendTicks = 0;
        d.hoverTicks = 0;
        d.gravityTicks = 0;
        d.jesusTicks = 0;
        d.groundSpoofTicks = 0;
        d.phaseTicks = 0;
        d.speedTicks = 0;
        d.noSlowTicks = 0;
        d.climbTicks = 0;
        d.airMaxY = to.getY();
        d.resetSpeed();
    }

    // ------------------------------------------------------------------ fly

    private void verifierFly(Player p, Data d, double dy, boolean groundTo,
                             boolean climbing, boolean liquide, boolean bizarre, boolean effetVertical) {
        if (!plugin.conf().enabled("fly")) return;
        if (groundTo || climbing || liquide || bizarre || effetVertical) {
            d.ascendTicks = 0;
            d.hoverTicks = 0;
            return;
        }
        // Une chute normale accelere vers le bas ; une montee vanilla (saut) dure ~6 ticks.
        if (d.airTicks > 8 && dy > 0.0 && d.lastDy > 0.0) {
            d.ascendTicks++;
        } else {
            d.ascendTicks = 0;
        }
        if (d.airTicks > 4 && Math.abs(dy) < 0.005) {
            d.hoverTicks++;
        } else {
            d.hoverTicks = 0;
        }

        int maxAscend = (int) plugin.conf().num("fly", "ascend-ticks", 12);
        int maxHover = (int) plugin.conf().num("fly", "hover-ticks", 14);
        if (d.ascendTicks > maxAscend) {
            d.ascendTicks = 0;
            plugin.flag(p, "fly", "montee de " + maxAscend + " ticks sans support");
        }
        if (d.hoverTicks > maxHover) {
            d.hoverTicks = 0;
            plugin.flag(p, "fly", "vol stationnaire (" + d.airTicks + " ticks en l air)");
        }
    }

    // ------------------------------------------------------------------ gravity

    private void verifierGravity(Player p, Data d, double dy, boolean groundTo,
                                 boolean climbing, boolean liquide, boolean bizarre, boolean effetVertical) {
        if (!plugin.conf().enabled("gravity")) return;
        if (groundTo || climbing || liquide || bizarre || effetVertical || d.airTicks < 4 || d.lastDy >= 0) {
            d.gravityTicks = 0;
            return;
        }
        double attendu = (d.lastDy - 0.08) * 0.98;
        if (dy > attendu + 0.05) {
            d.gravityTicks++;
            if (d.gravityTicks > 6) {
                d.gravityTicks = 0;
                plugin.flag(p, "gravity", "chute " + Util.fmt(dy) + " au lieu de " + Util.fmt(attendu));
            }
        } else {
            d.gravityTicks = 0;
        }
    }

    // ------------------------------------------------------------------ speed

    private void verifierSpeed(Player p, Data d, double hDist, boolean groundTo, boolean liquide, boolean climbing) {
        if (!plugin.conf().enabled("speed")) return;
        if (liquide || climbing || p.getPotionEffect(PotionEffectType.DOLPHINS_GRACE) != null) {
            d.resetSpeed();
            d.speedTicks = 0;
            return;
        }

        AttributeInstance attr = p.getAttribute(Attribute.MOVEMENT_SPEED);
        double base = (attr != null ? attr.getValue() : 0.1) * 2.15;
        if (p.isSprinting()) base *= 1.32;
        PotionEffect vitesse = p.getPotionEffect(PotionEffectType.SPEED);
        if (vitesse != null) base *= 1 + 0.2 * (vitesse.getAmplifier() + 1);

        double autorise = base * plugin.conf().num("speed", "tolerance", 1.35);
        if (!groundTo) autorise *= 1.9;                                  // elan de saut / sprint-jump
        if (Util.specialGround(p.getLocation())) autorise *= 2.4;        // glace, slime, miel...

        d.pushSpeed(hDist / Math.max(autorise, 0.001));
        if (d.speedFilled < d.speedSamples.length) return;

        double ratio = d.avgSpeed();
        if (ratio > 1.0) {
            d.speedTicks++;
            if (d.speedTicks > 5) {
                d.speedTicks = 0;
                plugin.flag(p, "speed", "moyenne " + Util.fmt(ratio * autorise) + "/tick pour "
                        + Util.fmt(autorise) + " autorises");
            }
        } else {
            d.speedTicks = 0;
        }
    }

    // ------------------------------------------------------------------ nofall / ground spoof

    private void verifierNoFall(Player p, Data d, Location to, boolean clientGround,
                                boolean climbing, boolean liquide, boolean bizarre) {
        if (!plugin.conf().enabled("nofall")) return;
        if (climbing || liquide || bizarre) {
            d.groundSpoofTicks = 0;
            return;
        }
        if (clientGround && !Util.onGround(to, 1.0)) {
            d.groundSpoofTicks++;
        } else {
            d.groundSpoofTicks = 0;
            return;
        }
        if (d.groundSpoofTicks < 3) return;

        double chute = d.airMaxY - to.getY();
        double minFall = plugin.conf().num("nofall", "min-fall", 2.5);
        if (chute > minFall) {
            boolean corriger = plugin.flag(p, "nofall", "sol annonce a tort, chute de " + Util.fmt(chute), 1);
            if (corriger) p.setFallDistance((float) chute);
        } else if (d.groundSpoofTicks > 10) {
            d.groundSpoofTicks = 0;
            plugin.flag(p, "nofall", "sol annonce a tort pendant 10 ticks");
        }
    }

    // ------------------------------------------------------------------ step

    private void verifierStep(Player p, Data d, double dy, boolean groundFrom, boolean groundTo,
                              boolean climbing, boolean bizarre) {
        if (!plugin.conf().enabled("step")) return;
        if (!groundFrom || !groundTo || climbing || bizarre) return;
        double max = plugin.conf().num("step", "max-step", 0.68);
        if (dy > max) {
            plugin.flag(p, "step", "montee instantanee de " + Util.fmt(dy));
        }
    }

    // ------------------------------------------------------------------ jesus

    private void verifierJesus(Player p, Data d, Location to, double dy, double hDist, boolean groundTo) {
        if (!plugin.conf().enabled("jesus")) return;
        Block pieds = to.getBlock();
        Block dessous = to.clone().add(0, -0.15, 0).getBlock();
        boolean surface = !pieds.isLiquid() && dessous.isLiquid();
        if (surface && !groundTo && !p.isSwimming() && !p.isInWater() && !p.isInsideVehicle()
                && Math.abs(dy) < 0.02 && hDist > 0.1) {
            d.jesusTicks++;
            if (d.jesusTicks > 4) {
                d.jesusTicks = 0;
                plugin.flag(p, "jesus", "marche sur " + dessous.getType());
            }
        } else {
            d.jesusTicks = 0;
        }
    }

    // ------------------------------------------------------------------ fastclimb

    private void verifierFastClimb(Player p, Data d, double dy, boolean climbing) {
        if (!plugin.conf().enabled("fastclimb")) return;
        if (!climbing) {
            d.climbTicks = 0;
            return;
        }
        double max = plugin.conf().num("fastclimb", "max-speed", 0.24);
        if (dy > max) {
            d.climbTicks++;
            if (d.climbTicks > 3) {
                d.climbTicks = 0;
                plugin.flag(p, "fastclimb", Util.fmt(dy) + "/tick a l echelle");
            }
        } else {
            d.climbTicks = 0;
        }
    }

    // ------------------------------------------------------------------ noslow

    private void verifierNoSlow(Player p, Data d, double hDist, boolean groundTo, boolean liquide, boolean climbing) {
        if (!plugin.conf().enabled("noslow")) return;
        if (!p.isHandRaised() || p.getHandRaisedTime() < 6 || !groundTo || liquide || climbing
                || p.isInsideVehicle() || Util.specialGround(p.getLocation())) {
            d.noSlowTicks = 0;
            return;
        }
        double max = plugin.conf().num("noslow", "max-speed", 0.13);
        if (hDist > max) {
            d.noSlowTicks++;
            if (d.noSlowTicks > 5) {
                d.noSlowTicks = 0;
                plugin.flag(p, "noslow", Util.fmt(hDist) + "/tick en utilisant "
                        + p.getActiveItem().getType());
            }
        } else {
            d.noSlowTicks = 0;
        }
    }

    // ------------------------------------------------------------------ phase

    private void verifierPhase(Player p, Data d, PlayerMoveEvent event, Location from, Location to) {
        if (!plugin.conf().enabled("phase")) return;
        if (!Util.insideSolid(to)) {
            d.phaseTicks = 0;
            if (!Util.insideSolid(from)) d.lastSafe = from.clone();
            return;
        }
        if (Util.insideSolid(from)) return;   // deja encastre avant le mouvement : pas notre affaire

        d.phaseTicks++;
        if (d.phaseTicks < 2) return;
        d.phaseTicks = 0;
        boolean annuler = plugin.flag(p, "phase", "dans un bloc plein", 1);
        if (annuler && d.lastSafe != null && d.lastSafe.getWorld() == to.getWorld()) {
            event.setTo(d.lastSafe.clone());
        }
    }
}
