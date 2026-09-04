package kguard;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * KamoofGuard : anticheat maison base uniquement sur l'API Bukkit/Paper (pas de ProtocolLib).
 * Un check leve une violation via {@link #flag}, ce qui incremente un compteur (VL) qui decroit
 * avec le temps ; passe un seuil on alerte le staff, passe un second seuil (optionnel) on sanctionne.
 */
public final class KamoofGuard extends JavaPlugin implements Listener {

    private static KamoofGuard instance;

    private final Map<UUID, Data> players = new HashMap<>();
    private final Set<UUID> alertsOff = new HashSet<>();
    private final Set<UUID> verbose = new HashSet<>();
    private Conf conf;
    private File logFile;

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static KamoofGuard get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        ecrireConfigParDefaut();
        conf = new Conf(this);
        logFile = new File(getDataFolder(), "violations.log");

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(new MovementChecks(this), this);
        getServer().getPluginManager().registerEvents(new CombatChecks(this), this);
        getServer().getPluginManager().registerEvents(new BlockChecks(this), this);
        getServer().getPluginManager().registerEvents(new MiscChecks(this), this);

        GuardCommand cmd = new GuardCommand(this);
        if (getCommand("kguard") != null) {
            getCommand("kguard").setExecutor(cmd);
            getCommand("kguard").setTabCompleter(cmd);
        }

        for (Player p : Bukkit.getOnlinePlayers()) data(p);

        // Decroissance des VL, une fois par minute.
        Bukkit.getScheduler().runTaskTimer(this, this::decay, 1200L, 1200L);

        getLogger().info("[guard] actif : " + conf.all().size() + " checks configures, sanctions "
                + (conf.punish ? "ACTIVEES" : "desactivees (alertes seules)") + ".");
    }

    @Override
    public void onDisable() {
        players.clear();
    }

    public Conf conf() {
        return conf;
    }

    public Data data(Player p) {
        return players.computeIfAbsent(p.getUniqueId(), id -> new Data(id, p.getName()));
    }

    public Data dataOrNull(UUID id) {
        return players.get(id);
    }

    public Map<UUID, Data> allData() {
        return players;
    }

    // ------------------------------------------------------------------ exemptions

    /** Exemptions globales : bypass, creatif, arrivee recente, teleportation. */
    public boolean exempt(Player p, String check) {
        if (p == null || !p.isOnline()) return true;
        if (p.hasPermission("kguard.bypass")) return true;
        if (conf.exemptOps && p.isOp()) return true;
        if (conf.exemptWorlds.contains(p.getWorld().getName())) return true;

        Data d = data(p);
        if (d.exempt) return true;

        long now = System.currentTimeMillis();
        if (now - d.joinTime < conf.joinGraceMs) return true;
        if (now - d.lastTeleport < conf.teleportGraceMs) return true;
        if (now - d.lastRespawn < conf.teleportGraceMs) return true;

        GameMode gm = p.getGameMode();
        if (gm == GameMode.SPECTATOR) return true;
        if (conf.exemptCreative && (gm == GameMode.CREATIVE || p.isFlying() || p.getAllowFlight())) return true;

        return false;
    }

    /** Exemptions supplementaires propres aux checks de mouvement (lag, degats, knockback). */
    public boolean exemptMovement(Player p) {
        Data d = data(p);
        long now = System.currentTimeMillis();
        if (now - d.lastVelocity < conf.velocityGraceMs) return true;
        if (now - d.lastDamage < conf.damageGraceMs) return true;
        if (p.getPing() > conf.maxPing) return true;
        if (Bukkit.getTPS()[0] < conf.minTps) return true;
        return p.isInsideVehicle() || p.isGliding() || p.isRiptiding() || p.isDead();
    }

    // ------------------------------------------------------------------ violations

    public void flag(Player p, String check, String detail) {
        flag(p, check, detail, 1);
    }

    /**
     * Enregistre une violation. Renvoie true si l'action doit etre annulee
     * (option cancel du check active et VL au dessus du seuil d'alerte).
     */
    public boolean flag(Player p, String check, String detail, double weight) {
        Conf.Check c = conf.check(check);
        if (!c.enabled) return false;
        if (exempt(p, check)) return false;

        Data d = data(p);
        double vl = d.addVl(check, weight);
        long now = System.currentTimeMillis();

        String ligne = p.getName() + " " + check + " vl=" + Util.fmt(vl) + " ping=" + p.getPing()
                + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")");

        boolean seuil = vl >= c.alertVl;
        boolean cooldownOk = now - d.lastAlert.getOrDefault(check, 0L) >= conf.alertCooldownMs;

        if (seuil && cooldownOk) {
            d.lastAlert.put(check, now);
            diffuserAlerte(p, check, vl, detail);
            journaliser(ligne);
        }
        if (!verbose.isEmpty()) {
            for (UUID id : verbose) {
                Player v = Bukkit.getPlayer(id);
                if (v != null) v.sendMessage(Component.text("[guard-v] " + ligne, NamedTextColor.DARK_GRAY));
            }
        }

        if (seuil && conf.punish && c.punishVl > 0 && vl >= c.punishVl) {
            d.vl.put(check, 0.0);
            String cmd = conf.punishCommand
                    .replace("%player%", p.getName())
                    .replace("%check%", check)
                    .replace("%vl%", String.valueOf((int) vl));
            getLogger().warning("[guard] sanction de " + p.getName() + " (" + check + ", vl " + (int) vl + ") : " + cmd);
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        }

        return c.cancel && seuil;
    }

    private void diffuserAlerte(Player p, String check, double vl, String detail) {
        Component msg = Component.text("[Guard] ", NamedTextColor.RED)
                .append(Component.text(p.getName(), NamedTextColor.YELLOW))
                .append(Component.text(" -> ", NamedTextColor.GRAY))
                .append(Component.text(check, NamedTextColor.WHITE))
                .append(Component.text(" vl " + (int) vl, NamedTextColor.GRAY))
                .append(detail == null || detail.isEmpty()
                        ? Component.empty()
                        : Component.text(" [" + detail + "]", NamedTextColor.DARK_GRAY));
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("kguard.alerts") && !alertsOff.contains(staff.getUniqueId())) {
                staff.sendMessage(msg);
            }
        }
        if (conf.logConsole) {
            getLogger().info("[guard] " + p.getName() + " " + check + " vl=" + (int) vl
                    + (detail == null ? "" : " " + detail));
        }
    }

    private void journaliser(String ligne) {
        if (!conf.logFile) return;
        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) return;
            try (Writer w = Files.newBufferedWriter(logFile.toPath(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                w.write(LocalDateTime.now().format(STAMP) + " " + ligne + System.lineSeparator());
            }
        } catch (IOException e) {
            getLogger().warning("[guard] ecriture du journal impossible: " + e.getMessage());
        }
    }

    private void decay() {
        for (Data d : players.values()) {
            for (Map.Entry<String, Double> e : d.vl.entrySet()) {
                double v = e.getValue() - conf.check(e.getKey()).decay;
                e.setValue(Math.max(0, v));
            }
        }
    }

    // ------------------------------------------------------------------ etat staff

    /** Renvoie true si les alertes sont desormais actives pour ce joueur. */
    public boolean toggleAlerts(Player p) {
        if (alertsOff.remove(p.getUniqueId())) return true;
        alertsOff.add(p.getUniqueId());
        return false;
    }

    public boolean toggleVerbose(Player p) {
        if (verbose.remove(p.getUniqueId())) return false;
        verbose.add(p.getUniqueId());
        return true;
    }

    public void reload() {
        conf.reload();
    }

    // ------------------------------------------------------------------ suivi de session

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        data(event.getPlayer()).joinTime = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        players.remove(event.getPlayer().getUniqueId());
        alertsOff.remove(event.getPlayer().getUniqueId());
        verbose.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Data d = data(event.getPlayer());
        d.lastTeleport = System.currentTimeMillis();
        d.lastTo = null;
        d.resetSpeed();
        d.airTicks = 0;
        d.ascendTicks = 0;
        d.hoverTicks = 0;
        d.gravityTicks = 0;
        d.jesusTicks = 0;
        d.groundSpoofTicks = 0;
        d.phaseTicks = 0;
        d.speedTicks = 0;
        d.noSlowTicks = 0;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        data(event.getPlayer()).lastRespawn = System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ config par defaut

    private void ecrireConfigParDefaut() {
        File f = new File(getDataFolder(), "config.yml");
        if (f.exists()) return;
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) return;
        try (Writer w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8)) {
            w.write(CONFIG_DEFAUT);
        } catch (IOException e) {
            getLogger().warning("[guard] config.yml par defaut non ecrite: " + e.getMessage());
        }
    }

    private static final String CONFIG_DEFAUT = String.join("\n",
            "# KamoofGuard - anticheat maison (API Bukkit/Paper uniquement).",
            "#",
            "# Chaque check possede :",
            "#   enabled           : actif ou non",
            "#   alert-vl          : VL a partir de laquelle le staff est alerte",
            "#   punish-vl         : VL a partir de laquelle on sanctionne (0 = jamais)",
            "#   decay-per-minute  : VL retirees chaque minute",
            "#   cancel            : annule l'action fautive quand c'est possible",
            "# Certains checks ont des reglages supplementaires, voir ci-dessous.",
            "",
            "# Interrupteur maitre : tant qu'il est a false, AUCUNE sanction n'est appliquee.",
            "punish: false",
            "punish-command: \"kick %player% Comportement suspect detecte (%check%)\"",
            "",
            "log:",
            "  file: true      # plugins/KamoofGuard/violations.log",
            "  console: true",
            "",
            "alert-cooldown-ms: 3000",
            "",
            "grace:",
            "  join-ms: 6000",
            "  teleport-ms: 3000",
            "  velocity-ms: 2000",
            "  damage-ms: 1500",
            "",
            "lag:",
            "  max-ping: 300   # au dela, les checks de mouvement sont ignores",
            "  min-tps: 18.0   # en dessous, les checks de mouvement sont ignores",
            "",
            "exempt:",
            "  creative: true  # creatif / vol autorise",
            "  ops: false",
            "  worlds: []",
            "",
            "checks:",
            "",
            "  # --- Mouvement ---",
            "  fly:",
            "    enabled: true",
            "    alert-vl: 6",
            "    punish-vl: 0",
            "    decay-per-minute: 10",
            "    ascend-ticks: 12    # montees consecutives sans support",
            "    hover-ticks: 14     # ticks immobiles en l'air",
            "  gravity:",
            "    enabled: true",
            "    alert-vl: 8",
            "    decay-per-minute: 12",
            "  speed:",
            "    enabled: true",
            "    alert-vl: 8",
            "    decay-per-minute: 12",
            "    tolerance: 1.35     # marge multiplicative sur la vitesse vanilla",
            "  nofall:",
            "    enabled: true",
            "    alert-vl: 4",
            "    cancel: true        # remet la distance de chute pour que les degats sappliquent",
            "    min-fall: 2.5",
            "  step:",
            "    enabled: true",
            "    alert-vl: 6",
            "    max-step: 0.68",
            "  jesus:",
            "    enabled: true",
            "    alert-vl: 5",
            "  timer:",
            "    enabled: true",
            "    alert-vl: 6",
            "    max-moves-per-second: 25",
            "  phase:",
            "    enabled: true",
            "    alert-vl: 3",
            "    cancel: true        # renvoie le joueur a sa derniere position valide",
            "  fastclimb:",
            "    enabled: true",
            "    alert-vl: 5",
            "    max-speed: 0.24",
            "  noslow:",
            "    enabled: true",
            "    alert-vl: 8",
            "    max-speed: 0.13",
            "",
            "  # --- Combat ---",
            "  reach:",
            "    enabled: true",
            "    alert-vl: 4",
            "    cancel: false       # true = le coup trop long est annule",
            "    max-distance: 3.5",
            "    average-distance: 3.25",
            "  killaura:",
            "    enabled: true",
            "    alert-vl: 4",
            "    max-angle: 85.0     # angle regard/cible au moment du coup",
            "  multiaura:",
            "    enabled: true",
            "    alert-vl: 3",
            "    window-ms: 250",
            "  noswing:",
            "    enabled: true",
            "    alert-vl: 6",
            "  autoclicker:",
            "    enabled: true",
            "    alert-vl: 5",
            "    max-cps: 16",
            "    min-deviation-ms: 8.0   # regularite inhumaine des intervalles",
            "  criticals:",
            "    enabled: true",
            "    alert-vl: 6",
            "  velocity:",
            "    enabled: true",
            "    alert-vl: 5",
            "    min-ratio: 0.30     # part du knockback reellement subie",
            "",
            "  # --- Blocs et objets ---",
            "  fastbreak:",
            "    enabled: true",
            "    alert-vl: 5",
            "    max-per-second: 15   # efficacite V + celerite cassent deja ~10 blocs/s",
            "  nuker:",
            "    enabled: true",
            "    alert-vl: 3",
            "  fastplace:",
            "    enabled: true",
            "    alert-vl: 5",
            "    max-per-second: 9",
            "  scaffold:",
            "    enabled: true",
            "    alert-vl: 6",
            "    min-pitch: 30.0     # sous cet angle de visee, poser sous soi est suspect",
            "  blockreach:",
            "    enabled: true",
            "    alert-vl: 4",
            "    cancel: true",
            "    max-distance: 5.3",
            "  xray:",
            "    enabled: true       # heuristique statistique : ALERTE SEULEMENT, jamais de sanction",
            "    alert-vl: 1",
            "    decay-per-minute: 0",
            "    max-y: 16",
            "    min-blocks: 250",
            "    max-ratio: 0.045",
            "  illegalitems:",
            "    enabled: true",
            "    alert-vl: 1",
            "    decay-per-minute: 0",
            "    remove: true",
            "  fastuse:",
            "    enabled: true",
            "    alert-vl: 6",
            "    max-per-second: 15",
            "  inventorymove:",
            "    enabled: false      # faux positifs avec les clients Bedrock (Geyser)",
            "    alert-vl: 6",
            "  chatspam:",
            "    enabled: true",
            "    alert-vl: 4",
            "    min-delay-ms: 500",
            "");
}
