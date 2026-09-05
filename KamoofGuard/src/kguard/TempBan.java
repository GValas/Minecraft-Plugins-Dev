package kguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.BanEntry;
import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.RemoteServerCommandEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duree facultative en dernier argument de /ban : "/ban Toto triche 3d".
 *
 * <p>La commande n'est pas reimplementee : on se contente de RETIRER le dernier argument
 * quand c'est une duree, de laisser le /ban vanilla s'executer normalement (c'est donc lui
 * qui verifie la permission, resout la cible, expulse le joueur et ecrit banned-players.json),
 * puis de poser l'expiration sur la ou les entrees qui viennent d'apparaitre dans la liste.
 * Sans duree, rien n'est touche : le ban reste definitif, exactement comme avant.
 *
 * <p>Le format d'expiration fait partie du vanilla depuis toujours (champ "expires" de
 * banned-players.json) : le serveur purge tout seul les bans perimes a la connexion.
 */
public final class TempBan implements Listener {

    /** Un groupe "nombre + unite" ; plusieurs peuvent s'enchainer ("1d12h"). */
    private static final Pattern GROUPE = Pattern.compile("(\\d{1,9})([smhdwy])", Pattern.CASE_INSENSITIVE);
    /** Le token entier doit n'etre QUE des groupes, sinon ce n'est pas une duree. */
    private static final Pattern DUREE = Pattern.compile("(?:\\d{1,9}[smhdwy])+", Pattern.CASE_INSENSITIVE);

    private static final long MAX_MS = 100L * 365 * 86_400_000L;   // garde-fou : 100 ans
    private static final DateTimeFormatter QUAND = DateTimeFormatter.ofPattern("dd/MM/yyyy 'a' HH:mm");

    private final KamoofGuard plugin;

    public TempBan(KamoofGuard plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ analyse de la duree

    /**
     * Duree en millisecondes decrite par "10s", "3m", "2h", "1d", "1w", "1y" ou une suite
     * de ces groupes ("1d12h"). Insensible a la casse. Renvoie -1 si ce n'est pas une duree.
     */
    public static long parseDuree(String token) {
        if (token == null || token.isEmpty() || !DUREE.matcher(token).matches()) return -1;
        long total = 0;
        Matcher m = GROUPE.matcher(token);
        while (m.find()) {
            long n = Long.parseLong(m.group(1));
            long unite = switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 's' -> 1000L;
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default  -> 31_536_000_000L;          // 'y' : 365 jours
            };
            total += n * unite;
            if (total >= MAX_MS) return MAX_MS;       // aucun risque de debordement : n <= 999999999
        }
        return total > 0 ? total : -1;
    }

    /** Rend une duree lisible en francais ("10 s", "1 min 30 s", "1 j 12 h", "2 ans"). */
    public static String humain(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + " s";
        long min = s / 60;
        if (min < 60) return min + " min" + (s % 60 != 0 ? " " + (s % 60) + " s" : "");
        long h = min / 60;
        if (h < 24) return h + " h" + (min % 60 != 0 ? " " + (min % 60) + " min" : "");
        long j = h / 24;
        if (j < 365) return j + " j" + (h % 24 != 0 ? " " + (h % 24) + " h" : "");
        long ans = j / 365;
        return ans + (ans > 1 ? " ans" : " an") + (j % 365 != 0 ? " " + (j % 365) + " j" : "");
    }

    // ------------------------------------------------------------------ interception

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        String reecrit = reecrire(event.getPlayer(), event.getMessage().substring(1));
        if (reecrit != null) event.setMessage("/" + reecrit);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onServerCommand(ServerCommandEvent event) {
        String reecrit = reecrire(event.getSender(), event.getCommand());
        if (reecrit != null) event.setCommand(reecrit);
    }

    /** RCON : sous-classe de ServerCommandEvent, mais avec sa PROPRE liste de handlers. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRemoteCommand(RemoteServerCommandEvent event) {
        String reecrit = reecrire(event.getSender(), event.getCommand());
        if (reecrit != null) event.setCommand(reecrit);
    }

    /**
     * Console : ni PlayerCommandPreprocessEvent ni ServerCommandEvent ne sont declenches par
     * Bukkit.dispatchCommand, d'ou ce point d'entree pour la sanction de l'anticheat
     * (punish-command peut donc valoir "ban %player% triche 7d").
     */
    public void dispatchConsole(String commande) {
        CommandSender console = Bukkit.getConsoleSender();
        String reecrit = reecrire(console, commande);
        Bukkit.dispatchCommand(console, reecrit != null ? reecrit : commande);
    }

    /**
     * Si `ligne` est un /ban dont le dernier argument est une duree : arme la pose de
     * l'expiration et renvoie la commande privee de cet argument. Sinon renvoie null
     * (la commande passe telle quelle).
     */
    private String reecrire(CommandSender sender, String ligne) {
        String[] mots = ligne.trim().split("\\s+");
        if (mots.length < 3) return null;                    // "ban <cible> <duree>" au minimum
        String commande = mots[0].toLowerCase(Locale.ROOT);
        if (!commande.equals("ban") && !commande.equals("minecraft:ban")) return null;

        String token = mots[mots.length - 1];
        long ms = parseDuree(token);
        if (ms <= 0) return null;                            // pas de duree -> ban definitif vanilla

        // Une cible litterale permet de ne toucher qu'elle ; un selecteur (@a, @p...) est
        // resolu par le vanilla, on prendra alors toutes les entrees nouvellement creees.
        String cible = mots[1].startsWith("@") ? null : mots[1];
        armer(sender, ms, token, cible);
        return String.join(" ", Arrays.copyOf(mots, mots.length - 1));
    }

    // ------------------------------------------------------------------ pose de l'expiration

    /**
     * Pose l'expiration au tick suivant sur les entrees que le /ban vient de creer.
     *
     * <p>On les reconnait a leur date de creation, relevee avant puis apres. Comparer seulement
     * les NOMS ne suffirait pas : un ban perime reste dans banned-players.json tant que le
     * serveur ne l'a pas purge, donc rebannir un tel joueur ne fait apparaitre aucun nom nouveau
     * alors qu'une entree fraiche a bien ete ecrite. Et si le vanilla refuse (permission
     * manquante, cible inconnue, joueur deja banni), aucune date ne bouge : on ne touche a rien,
     * ce qui laisse son message "Nothing changed" dire vrai.
     */
    private void armer(CommandSender sender, long ms, String token, String cible) {
        Map<String, Long> avant = datesDeCreation();
        Bukkit.getScheduler().runTask(plugin, () -> {
            Date expire = new Date(System.currentTimeMillis() + ms);
            List<String> poses = new ArrayList<>();
            for (BanEntry<?> entree : entrees()) {
                Date creee = entree.getCreated();
                if (creee == null) continue;
                Long ancienne = avant.get(entree.getTarget());
                if (ancienne != null && ancienne == creee.getTime()) continue;   // entree inchangee
                if (cible != null && !cible.equalsIgnoreCase(entree.getTarget())) continue;
                entree.setExpiration(expire);
                entree.save();
                poses.add(entree.getTarget());
            }
            if (poses.isEmpty()) return;

            String quand = LocalDateTime.ofInstant(expire.toInstant(), ZoneId.systemDefault()).format(QUAND);
            String qui = String.join(", ", poses);
            sender.sendMessage(Component.text("Ban temporaire : ", NamedTextColor.YELLOW)
                    .append(Component.text(qui, NamedTextColor.WHITE))
                    .append(Component.text(" pour " + humain(ms) + " (jusqu'au " + quand + ").",
                            NamedTextColor.YELLOW)));
            plugin.getLogger().info("[ban] " + qui + " banni " + humain(ms) + " (" + token
                    + "), expire le " + quand + ", par " + sender.getName() + ".");
        });
    }

    /** Entrees de la liste des bans par profil (celle qu'alimente /ban). */
    @SuppressWarnings({ "deprecation", "rawtypes" })
    private static Set<BanEntry> entrees() {
        BanList<?> liste = Bukkit.getBanList(BanList.Type.PROFILE);
        return liste.getBanEntries();
    }

    /** Cible -> date de creation de son ban, pour reperer ensuite les entrees reecrites. */
    private static Map<String, Long> datesDeCreation() {
        Map<String, Long> out = new HashMap<>();
        for (BanEntry<?> e : entrees()) {
            if (e.getTarget() != null && e.getCreated() != null) {
                out.put(e.getTarget(), e.getCreated().getTime());
            }
        }
        return out;
    }
}
