package kguard;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Helpers geometriques : sol reel, blocs autour, distances. Aucun etat. */
public final class Util {

    private Util() { }

    private static final double HALF_WIDTH = 0.31;

    /** Sol "serveur" : un bloc plein sous les pieds dans les `depth` blocs en dessous. */
    public static boolean onGround(Location loc, double depth) {
        World w = loc.getWorld();
        if (w == null) return true;
        double[] xs = { loc.getX() - HALF_WIDTH, loc.getX() + HALF_WIDTH };
        double[] zs = { loc.getZ() - HALF_WIDTH, loc.getZ() + HALF_WIDTH };
        for (double dy = 0.02; dy <= depth; dy += 0.25) {
            double y = loc.getY() - dy;
            for (double x : xs) {
                for (double z : zs) {
                    Block b = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                    if (isSupport(b)) return true;
                }
            }
        }
        return false;
    }

    public static boolean isSupport(Block b) {
        Material m = b.getType();
        if (m.isAir()) return false;
        if (b.isLiquid()) return false;
        return !b.isPassable() || Tag.CLIMBABLE.isTagged(m) || m == Material.SCAFFOLDING
                || m == Material.COBWEB || m == Material.POWDER_SNOW;
    }

    /** Bloc aux pieds du joueur. */
    public static Block feet(Location loc) {
        return loc.getBlock();
    }

    /** Un des blocs traverses par le joueur correspond-il a `m` ? (pieds, tete, sous les pieds) */
    public static boolean touching(Location loc, Material... mats) {
        Block[] blocks = {
                loc.getBlock(),
                loc.clone().add(0, 1, 0).getBlock(),
                loc.clone().add(0, -0.2, 0).getBlock()
        };
        for (Block b : blocks) {
            for (Material m : mats) {
                if (b.getType() == m) return true;
            }
        }
        return false;
    }

    /** Glace / neige poudreuse / slime / miel sous les pieds : la physique n'est plus standard. */
    public static boolean specialGround(Location loc) {
        Block below = loc.clone().add(0, -0.3, 0).getBlock();
        Material m = below.getType();
        return Tag.ICE.isTagged(m) || m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK
                || m == Material.SOUL_SAND || m == Material.POWDER_SNOW || m == Material.BUBBLE_COLUMN;
    }

    /** Environnement qui perturbe la chute libre (toile, echelle, eau, echafaudage, bulles...). */
    public static boolean weirdEnvironment(Location loc) {
        World w = loc.getWorld();
        if (w == null) return true;
        int minX = (int) Math.floor(loc.getX() - 0.4), maxX = (int) Math.floor(loc.getX() + 0.4);
        int minZ = (int) Math.floor(loc.getZ() - 0.4), maxZ = (int) Math.floor(loc.getZ() + 0.4);
        int minY = (int) Math.floor(loc.getY() - 0.6), maxY = (int) Math.floor(loc.getY() + 1.9);
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Block b = w.getBlockAt(x, y, z);
                    Material m = b.getType();
                    if (m.isAir()) continue;
                    if (b.isLiquid() || Tag.CLIMBABLE.isTagged(m) || Tag.TRAPDOORS.isTagged(m)
                            || m == Material.COBWEB || m == Material.SCAFFOLDING || m == Material.POWDER_SNOW
                            || m == Material.SLIME_BLOCK || m == Material.HONEY_BLOCK || m == Material.BUBBLE_COLUMN
                            || m == Material.SWEET_BERRY_BUSH || m == Material.END_PORTAL || m == Material.NETHER_PORTAL
                            || m == Material.PISTON_HEAD || m == Material.MOVING_PISTON || m == Material.BIG_DRIPLEAF) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Le joueur est-il encastre dans un bloc plein (phase / noclip) ? */
    public static boolean insideSolid(Location loc) {
        World w = loc.getWorld();
        if (w == null) return false;
        double[] xs = { loc.getX() - 0.28, loc.getX() + 0.28 };
        double[] zs = { loc.getZ() - 0.28, loc.getZ() + 0.28 };
        double[] ys = { loc.getY() + 0.1, loc.getY() + 1.6 };
        for (double x : xs) {
            for (double z : zs) {
                for (double y : ys) {
                    Block b = w.getBlockAt((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
                    Material m = b.getType();
                    if (m.isAir() || b.isPassable() || b.isLiquid()) continue;
                    // portes, trappes, portillons, pistons et compagnie bougent : trop d'aleas
                    if (Tag.DOORS.isTagged(m) || Tag.TRAPDOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)
                            || Tag.SHULKER_BOXES.isTagged(m) || Tag.BEDS.isTagged(m)
                            || m == Material.PISTON || m == Material.STICKY_PISTON || m == Material.PISTON_HEAD
                            || m == Material.MOVING_PISTON || m == Material.SCAFFOLDING) {
                        continue;
                    }
                    if (!b.getBoundingBox().contains(new Vector(x, y, z))) continue;
                    return true;
                }
            }
        }
        return false;
    }

    /** Distance entre un point (les yeux) et la boite de collision d'une entite. */
    public static double distanceToBox(Location eye, Entity target, double expand) {
        BoundingBox box = target.getBoundingBox().expand(expand);
        double dx = Math.max(Math.max(box.getMinX() - eye.getX(), 0), eye.getX() - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - eye.getY(), 0), eye.getY() - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - eye.getZ(), 0), eye.getZ() - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** Angle (degres) entre le regard du joueur et la direction vers la cible. */
    public static double angleTo(Player p, Location target) {
        Vector look = p.getEyeLocation().getDirection().normalize();
        Vector to = target.toVector().subtract(p.getEyeLocation().toVector());
        if (to.lengthSquared() < 1.0E-6) return 0;
        to.normalize();
        double dot = Math.max(-1, Math.min(1, look.dot(to)));
        return Math.toDegrees(Math.acos(dot));
    }

    public static double horizontal(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    public static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.3f", d);
    }
}
