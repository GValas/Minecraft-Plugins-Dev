package kamoof.portal;

import kamoof.ritual.RitualManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;

import java.io.InputStream;
import java.util.Random;

/**
 * Pose le grand portail du Nether decore (portal.nbt) via /portal spawn.
 * Le portail s'oriente parallelement a la face de l'autel du rituel la plus
 * proche (l'ouverture regarde vers l'autel) ; sans autel, il fait face au joueur.
 */
public final class PortalPlacer {

    // Indices cardinaux : N=0, E=1, S=2, W=3 (CLOCKWISE_90 = +1 modulo 4).
    // Dans portal.nbt le plan du portail court le long de l'axe X, ouverture vers le sud.
    private static final int TEMPLATE_FRONT = 2;
    // La cible y de /portal spawn = niveau du sol ou l'on marche ; la structure
    // commence 2 blocs plus bas (fondations + dalle).
    private static final int Y_BASE_OFFSET = -2;

    private static final String[] CARDINAL = {"nord", "est", "sud", "ouest"};

    private static Structure structure;

    private PortalPlacer() {}

    public static boolean spawn(Player player, double x, double y, double z) {
        if (structure == null) structure = loadStructure("portal.nbt");
        if (structure == null) return false;

        int front = frontCardinal(player, x, z);
        int steps = Math.floorMod(front - TEMPLATE_FRONT, 4);
        StructureRotation rotation = switch (steps) {
            case 1 -> StructureRotation.CLOCKWISE_90;
            case 2 -> StructureRotation.CLOCKWISE_180;
            case 3 -> StructureRotation.COUNTERCLOCKWISE_90;
            default -> StructureRotation.NONE;
        };

        BlockVector size = structure.getSize();
        int sx = size.getBlockX(), sz = size.getBlockZ();
        int wx = (steps % 2 == 1) ? sz : sx;
        int wz = (steps % 2 == 1) ? sx : sz;
        int tx = (int) Math.floor(x), ty = (int) Math.floor(y), tz = (int) Math.floor(z);
        int minX = tx - wx / 2, minZ = tz - wz / 2;
        int py = ty + Y_BASE_OFFSET;

        // Structure.place pivote autour du point de pose lui-meme : on choisit le
        // coin qui garde l'empreinte centree sur la cible pour les 4 rotations.
        Location corner = switch (rotation) {
            case CLOCKWISE_90 -> new Location(player.getWorld(), minX + sz - 1, py, minZ);
            case CLOCKWISE_180 -> new Location(player.getWorld(), minX + sx - 1, py, minZ + sz - 1);
            case COUNTERCLOCKWISE_90 -> new Location(player.getWorld(), minX, py, minZ + sx - 1);
            default -> new Location(player.getWorld(), minX, py, minZ);
        };
        structure.place(corner, false, rotation, Mirror.NONE, 0, 1.0f, new Random());
        player.sendMessage("§aPortail pose en §e" + tx + " " + ty + " " + tz
                + "§a, ouverture vers le " + CARDINAL[front] + ".");
        return true;
    }

    // Cardinal vers lequel regarde l'OUVERTURE du portail.
    private static int frontCardinal(Player player, double x, double z) {
        Location altar = RitualManager.location;
        if (altar != null && altar.getWorld() != null && altar.getWorld().equals(player.getWorld())) {
            double dx = x - altar.getX();
            double dz = z - altar.getZ();
            // parallele a la face de l'autel la plus proche : l'ouverture regarde l'autel
            if (Math.abs(dx) >= Math.abs(dz)) return dx > 0 ? 3 : 1;
            return dz > 0 ? 0 : 2;
        }
        // sans autel (ou autre monde) : l'ouverture fait face au joueur (yaw 0 = sud)
        int look = Math.floorMod(Math.round(player.getLocation().getYaw() / 90f) + 2, 4);
        return (look + 2) % 4;
    }

    private static Structure loadStructure(String resource) {
        try (InputStream is = RitualManager.plugin().getResource(resource)) {
            if (is == null) return null;
            return Bukkit.getStructureManager().loadStructure(is);
        } catch (Throwable t) {
            RitualManager.plugin().getLogger().warning("[portail] chargement " + resource + " echoue: " + t.getMessage());
            return null;
        }
    }
}
