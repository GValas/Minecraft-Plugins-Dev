package statusexport;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.stream.Collectors;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Ecrit plugins/StatusExport/status.json toutes les 30 s, pour le dashboard web
 * (meme pattern que disguises.json de KamoofLite : le conteneur mc-dashboard
 * monte /minecraft en lecture seule et lit ce fichier).
 */
public final class StatusExport extends JavaPlugin {

    private static final long PERIODE_TICKS = 30L * 20L;

    @Override
    public void onEnable() {
        getDataFolder().mkdirs();
        // Tache synchrone : Bukkit.getTPS()/getOnlinePlayers() se lisent depuis le main thread.
        Bukkit.getScheduler().runTaskTimer(this, this::ecrireStatus, 20L, PERIODE_TICKS);
        getLogger().info("Export de l'etat serveur vers status.json toutes les 30 s.");
    }

    @Override
    public void onDisable() {
        // Dernier etat : le dashboard verra un fichier "arrete proprement".
        ecrireStatus();
    }

    private void ecrireStatus() {
        double[] tps = Bukkit.getTPS();
        double mspt = Bukkit.getAverageTickTime();
        String joueurs = Bukkit.getOnlinePlayers().stream()
                .map(p -> '"' + echapper(p.getName()) + '"')
                .collect(Collectors.joining(","));
        String json = String.format(Locale.ROOT,
                "{\"updated\":%d,\"started\":%d,\"version\":\"%s\","
                        + "\"tps\":[%.1f,%.1f,%.1f],\"mspt\":%.2f,"
                        + "\"online\":[%s],\"max\":%d}%n",
                System.currentTimeMillis(),
                ManagementFactory.getRuntimeMXBean().getStartTime(),
                echapper(Bukkit.getMinecraftVersion()),
                Math.min(tps[0], 20.0), Math.min(tps[1], 20.0), Math.min(tps[2], 20.0),
                mspt, joueurs, Bukkit.getMaxPlayers());
        Path cible = getDataFolder().toPath().resolve("status.json");
        Path tmp = getDataFolder().toPath().resolve("status.json.tmp");
        try {
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, cible, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            getLogger().warning("Ecriture status.json impossible : " + e.getMessage());
        }
    }

    private static String echapper(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
