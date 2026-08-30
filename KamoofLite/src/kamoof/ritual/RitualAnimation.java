package kamoof.ritual;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static kamoof.ritual.RitualManager.OFFSETS;
import static kamoof.ritual.RitualManager.armorStands;

/**
 * Cinematique du rituel (port 1:1 de RitualAnimation de KamoofSMP S2).
 * Sequence : temps accelere -> lignes octogonales -> colonnes -> cercles ->
 * flammes + eclairs -> sphere bleue -> drop du livre au centre.
 */
public final class RitualAnimation {

    private static final Set<Runnable> particles = new HashSet<>();
    private static boolean stopped = false;

    private RitualAnimation() { }

    public static void execute(Location location) {
        var plugin = RitualManager.plugin();
        World world = location.getWorld();
        double startX = location.getBlockX() + 0.5;
        double startY = location.getBlockY() + 0.25;
        double startZ = location.getBlockZ() + 0.5;
        double endY = startY + 8.0;
        Location centeredLoc = new Location(world, startX, location.getBlockY() + 4.0, startZ);
        Particle.DustOptions dust = new Particle.DustOptions(RitualManager.ANIM_COLOR, RitualManager.ANIM_SIZE);

        stopped = false;
        particles.clear();
        int[] ref = {0};

        // Skull a texture fixe pose sur les stands pendant l'animation.
        ItemStack skull = new ItemStack(Material.PLAYER_HEAD);
        try {
            SkullMeta meta = (SkullMeta) skull.getItemMeta();
            PlayerProfile prof = Bukkit.createPlayerProfile(UUID.randomUUID(), "_");
            PlayerTextures tex = prof.getTextures();
            tex.setSkin(URI.create("http://textures.minecraft.net/texture/f8912bc1ad3ddbe39a19b734a42d8548964bb0a9ce58a52f1a6ae37121524").toURL());
            prof.setTextures(tex);
            meta.setOwnerProfile(prof);
            skull.setItemMeta(meta);
        } catch (Throwable ignored) {
            // texture indisponible -> tete par defaut, l'animation continue
        }
        for (ArmorStand entity : armorStands) {
            entity.getPersistentDataContainer().set(RitualManager.KEY, PersistentDataType.BOOLEAN, true);
            if (entity.getEquipment() != null) entity.getEquipment().setHelmet(skull, true);
        }

        // Ciel : coupe la meteo, fige le cycle jour et accelere le temps.
        world.setWeatherDuration(0);
        world.setThundering(false);
        world.setStorm(false);
        GameRule<Boolean> daylight = GameRule.DO_DAYLIGHT_CYCLE;
        boolean hasDayCycle = Boolean.TRUE.equals(world.getGameRuleValue(daylight));
        world.setGameRule(daylight, false);
        long previousWorldTime = world.getTime();
        Bukkit.getScheduler().runTaskTimer(plugin, (Consumer<BukkitTask>) task -> {
            if (stopped) { task.cancel(); return; }
            world.playSound(centeredLoc, Sound.ITEM_GOAT_HORN_SOUND_3, SoundCategory.AMBIENT, 0.1f, 0.7f);
            long t = (long) Math.round((float) (world.getTime() + RitualManager.ANIM_TIME_INCR) / RitualManager.ANIM_TIME_INCR) * RitualManager.ANIM_TIME_INCR;
            world.setTime(t);
            if (world.getTime() == RitualManager.ANIM_TIME_STOP) task.cancel();
        }, 0L, RitualManager.ANIM_TIME_SPEED);

        // --- Phase 4 : sphere bleue puis finalisation (drop du livre). ---
        Runnable part4 = () -> {
            if (stopped) return;
            double radius = RitualManager.SPHERE_RADIUS;
            spawnSphere(world, startX, startY + 4.5, startZ, radius);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                stopped = true;
                world.setTime(previousWorldTime);
                world.setGameRule(daylight, hasDayCycle);
                Bukkit.broadcast(RitualManager.mini(RitualManager.MSG_RITUALDONE));
                world.dropItemNaturally(centeredLoc, RitualBook.getBook(RitualManager.addNewToken()));
                world.strikeLightning(centeredLoc.clone().add(0.0, radius, 0.0));
                world.playSound(centeredLoc, Sound.ENTITY_WITHER_SPAWN, SoundCategory.HOSTILE, 0.2f, 1.0f);
                for (ArmorStand entity : armorStands) {
                    entity.getPersistentDataContainer().set(RitualManager.KEY, PersistentDataType.BOOLEAN, false);
                    if (entity.getEquipment() != null) entity.getEquipment().setHelmet(null, true);
                }
            }, 200L);
        };

        // --- Phase 3 : flammes, grands cercles, octogone haut, eclairs. ---
        Runnable part3 = () -> {
            if (stopped) return;
            particles.add(() -> world.spawnParticle(Particle.FLAME, startX, startY + 4.0, startZ, 2, 5.0, 5.0, 5.0, 2.0, null, true));
            world.playSound(centeredLoc, Sound.BLOCK_ANVIL_PLACE, SoundCategory.AMBIENT, 0.5f, 0.5f);
            drawLargeCircles(world, startX, startY, startZ, dust);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (stopped) return;
                drawLargeCircles(world, startX, endY, startZ, dust);
                for (int i = 0; i < OFFSETS.length; ++i) {
                    double[] a = OFFSETS[i];
                    double[] b = OFFSETS[(i + 1) % OFFSETS.length];
                    drawCircle(world, a[0] + startX, endY, a[1] + startZ, 1.5, 40, dust);
                    drawLine(world, endY, a[0] + startX, a[1] + startZ, b[0] + startX, b[1] + startZ, dust);
                }
                int[] li = {0};
                Location lightningLoc = centeredLoc.clone().add(0.0, 12.0, 0.0);
                int quantity = RitualManager.LIGHTNING_QTY;
                long interval = RitualManager.LIGHTNING_INTERVAL;
                Bukkit.getScheduler().runTaskTimer(plugin, (Consumer<BukkitTask>) task -> {
                    if (stopped || li[0] >= quantity) { task.cancel(); return; }
                    li[0]++;
                    world.strikeLightning(lightningLoc);
                }, 15L, interval);
                Bukkit.getScheduler().runTaskLater(plugin, part4, interval * quantity + 10L);
            }, 20L);
        };

        // --- Phase 2 : beacon, colonnes verticales, cercles par offset. ---
        Runnable part2 = () -> {
            if (stopped) return;
            world.playSound(centeredLoc, Sound.BLOCK_BEACON_ACTIVATE, SoundCategory.AMBIENT, 0.15f, 1.0f);
            for (double[] offset : OFFSETS) {
                double x = startX + offset[0];
                double z = startZ + offset[1];
                for (int i = 0; i <= 30; ++i) {
                    double y = interpolate(startY, endY, (float) i / 30.0f);
                    particles.add(() -> world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust, true));
                }
            }
            ref[0] = 0;
            Bukkit.getScheduler().runTaskTimer(plugin, (Consumer<BukkitTask>) task -> {
                if (stopped) { task.cancel(); return; }
                double[] offset = OFFSETS[ref[0]];
                ref[0]++;
                double x = startX + offset[0];
                double z = startZ + offset[1];
                drawCircle(world, x, startY, z, 1.5, 40, dust);
                world.playSound(new Location(world, x, startY, z), Sound.BLOCK_END_PORTAL_FRAME_FILL, 0.2f, 0.95f);
                if (ref[0] >= OFFSETS.length) {
                    task.cancel();
                    ref[0] = 0;
                    Bukkit.getScheduler().runTaskLater(plugin, part3, 10L);
                }
            }, 10L, 10L);
        };

        // --- Phase 1 : lignes de l'octogone au sol, une par tick. ---
        Consumer<BukkitTask> part1 = task -> {
            if (stopped) { task.cancel(); return; }
            double[] a = OFFSETS[ref[0]];
            double[] b = OFFSETS[(ref[0] + 1) % OFFSETS.length];
            ref[0]++;
            drawLine(world, startY, a[0] + startX, a[1] + startZ, b[0] + startX, b[1] + startZ, dust);
            if (ref[0] >= OFFSETS.length) {
                task.cancel();
                ref[0] = 0;
                world.playSound(centeredLoc, Sound.BLOCK_ANVIL_USE, SoundCategory.AMBIENT, 1.0f, 0.3f);
                Bukkit.getScheduler().runTaskLater(plugin, part2, 40L);
            }
        };
        Bukkit.getScheduler().runTaskTimer(plugin, part1, 15L, 20L);

        // Runner continu : rejoue toutes les particules enregistrees toutes les 2 ticks.
        Bukkit.getScheduler().runTaskTimer(plugin, (Consumer<BukkitTask>) task -> {
            if (stopped) { task.cancel(); return; }
            particles.forEach(Runnable::run);
        }, 0L, 2L);
    }

    private static double interpolate(double a, double b, float ratio) {
        return a + (b - a) * ratio;
    }

    private static void drawLargeCircles(World world, double x, double y, double z, Particle.DustOptions dust) {
        drawCircle(world, x, y, z, 4.25, 100, dust);
        drawCircle(world, x, y, z, 4.5, 100, dust);
        drawCircle(world, x, y, z, 7.25, 150, dust);
    }

    private static void drawLine(World world, double y, double aX, double aZ, double bX, double bZ, Particle.DustOptions dust) {
        for (int i = 0; i < 20; ++i) {
            float ratio = (float) i / 19.0f;
            double x = interpolate(aX, bX, ratio);
            double z = interpolate(aZ, bZ, ratio);
            particles.add(() -> world.spawnParticle(Particle.DUST, x, y, z, 1, 0, 0, 0, 0, dust, true));
        }
    }

    private static void drawCircle(World world, double x, double y, double z, double radius, int count, Particle.DustOptions dust) {
        for (int i = 0; i < count; ++i) {
            double angle = Math.PI * 2 * i / count;
            double posX = x + radius * Math.cos(angle);
            double posZ = z + radius * Math.sin(angle);
            particles.add(() -> world.spawnParticle(Particle.DUST, posX, y, posZ, 1, 0, 0, 0, 0, dust, true));
        }
    }

    private static void spawnSphere(World world, double x, double y, double z, double radius) {
        double lavaChance = RitualManager.SPHERE_LAVA_CHANCE / 100.0;
        Particle lavaParticle = RitualManager.SPHERE_LAVA_SOUND ? Particle.DRIPPING_DRIPSTONE_LAVA : Particle.DRIPPING_LAVA;
        Particle.DustOptions dust = new Particle.DustOptions(RitualManager.SPHERE_COLOR, RitualManager.SPHERE_SIZE);
        Bukkit.getScheduler().runTaskTimer(RitualManager.plugin(), (Consumer<BukkitTask>) task -> {
            if (stopped) { task.cancel(); return; }
            for (int i = 0; i < RitualManager.SPHERE_QTY; ++i) {
                double phi = Math.PI * 2 * Math.random();
                double posY = y + radius * Math.cos(phi);
                double phiSin = Math.sin(phi);
                double angle = Math.PI * 2 * Math.random();
                double posX = x + radius * Math.cos(angle) * phiSin;
                double posZ = z + radius * Math.sin(angle) * phiSin;
                if (Math.random() > lavaChance) {
                    world.spawnParticle(Particle.DUST, posX, posY, posZ, 1, 0, 0, 0, 0, dust, true);
                } else {
                    world.spawnParticle(lavaParticle, posX, posY, posZ, 1, 0, 0, 0, 0, null, true);
                }
            }
        }, 0L, 5L);
    }
}
