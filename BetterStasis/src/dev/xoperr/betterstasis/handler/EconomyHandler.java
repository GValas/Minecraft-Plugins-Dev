package dev.xoperr.betterstasis.handler;

import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Economie desactivee pour le serveur HORUS : l'original depend de Vault
 * (absent du serveur et du classpath). Memes signatures, toujours "gratuit".
 */
public class EconomyHandler {

    public static void setupEconomy() {
    }

    public static boolean isEconomyEnabled() {
        return false;
    }

    public static double calculateTeleportCost(Location from, Location to) {
        return 0.0;
    }

    public static boolean chargeTeleport(Player player, Location from, Location to) {
        return true;
    }

    public static void refundTeleport(Player player, double amount) {
    }

    public static boolean chargeBinding(Player player) {
        return true;
    }
}
