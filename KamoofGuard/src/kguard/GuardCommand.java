package kguard;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** /kguard : info, alerts, verbose, vl, top, clear, exempt, reload. */
public final class GuardCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SOUS_COMMANDES =
            List.of("info", "alerts", "verbose", "vl", "top", "clear", "exempt", "reload");

    private final KamoofGuard plugin;

    public GuardCommand(KamoofGuard plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("kguard.command")) {
            sender.sendMessage("§cCommande reservee au staff.");
            return true;
        }
        String sous = args.length == 0 ? "info" : args[0].toLowerCase();

        switch (sous) {
            case "info" -> {
                Conf c = plugin.conf();
                long actifs = c.all().values().stream().filter(k -> k.enabled).count();
                sender.sendMessage("§7KamoofGuard §f" + plugin.getPluginMeta().getVersion()
                        + " §8| §7checks actifs : §f" + actifs + "/" + c.all().size()
                        + " §8| §7sanctions : "
                        + (c.punish ? "§cactivees" : "§aalertes seules")
                        + " §8| §7TPS §f" + String.format(java.util.Locale.ROOT, "%.1f", Bukkit.getTPS()[0]));
                sender.sendMessage("§8/kguard alerts|verbose|vl <joueur>|top|clear <joueur>|exempt <joueur>|reload");
            }
            case "alerts" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cReserve aux joueurs.");
                    return true;
                }
                boolean on = plugin.toggleAlerts(p);
                p.sendMessage(on ? "§aAlertes activees." : "§7Alertes desactivees.");
            }
            case "verbose" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§cReserve aux joueurs.");
                    return true;
                }
                boolean on = plugin.toggleVerbose(p);
                p.sendMessage(on ? "§aMode verbeux active (toutes les violations)."
                        : "§7Mode verbeux desactive.");
            }
            case "vl" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage : /kguard vl <joueur>");
                    return true;
                }
                Player cible = Bukkit.getPlayerExact(args[1]);
                if (cible == null) {
                    sender.sendMessage("§cJoueur introuvable.");
                    return true;
                }
                Data d = plugin.data(cible);
                if (d.vl.isEmpty()) {
                    sender.sendMessage("§7" + cible.getName() + " : aucune violation.");
                    return true;
                }
                sender.sendMessage("§7Violations de §f" + cible.getName() + "§7 :");
                d.vl.entrySet().stream()
                        .filter(e -> e.getValue() > 0)
                        .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                        .forEach(e -> sender.sendMessage("  §8- §f" + e.getKey()
                                + " §7vl §e" + (int) (double) e.getValue()));
            }
            case "top" -> {
                List<Data> tries = new ArrayList<>(plugin.allData().values());
                tries.sort(Comparator.comparingDouble(Data::totalVl).reversed());
                sender.sendMessage("§7Joueurs les plus signales :");
                int n = 0;
                for (Data d : tries) {
                    if (d.totalVl() <= 0 || n++ >= 10) break;
                    sender.sendMessage("  §8- §f" + d.name + " §7total vl §e" + (int) d.totalVl());
                }
                if (n == 0) sender.sendMessage("  §8(rien a signaler)");
            }
            case "clear" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage : /kguard clear <joueur>");
                    return true;
                }
                Player cible = Bukkit.getPlayerExact(args[1]);
                if (cible == null) {
                    sender.sendMessage("§cJoueur introuvable.");
                    return true;
                }
                plugin.data(cible).vl.clear();
                sender.sendMessage("§aViolations de " + cible.getName() + " remises a zero.");
            }
            case "exempt" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage : /kguard exempt <joueur>");
                    return true;
                }
                Player cible = Bukkit.getPlayerExact(args[1]);
                if (cible == null) {
                    sender.sendMessage("§cJoueur introuvable.");
                    return true;
                }
                Data d = plugin.data(cible);
                d.exempt = !d.exempt;
                sender.sendMessage("§7" + cible.getName() + " est desormais "
                        + (d.exempt ? "§eexempte" : "§asurveille") + "§7.");
            }
            case "reload" -> {
                plugin.reload();
                sender.sendMessage("§aConfiguration rechargee ("
                        + plugin.conf().all().size() + " checks).");
            }
            default -> sender.sendMessage("§cSous-commande inconnue. /kguard info");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            for (String s : SOUS_COMMANDES) {
                if (s.startsWith(args[0].toLowerCase())) out.add(s);
            }
            return out;
        }
        if (args.length == 2 && List.of("vl", "clear", "exempt").contains(args[0].toLowerCase())) {
            List<String> out = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(p.getName());
            }
            return out;
        }
        return List.of();
    }
}
