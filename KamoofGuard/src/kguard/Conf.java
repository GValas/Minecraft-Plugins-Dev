package kguard;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration typee de l'anticheat. Un check = une section "checks.<nom>" avec au minimum
 * enabled / alert-vl / punish-vl / decay-per-minute / cancel, plus des reglages libres lus via
 * {@link #num(String, String, double)}.
 */
public final class Conf {

    public static final class Check {
        public final String name;
        public boolean enabled = true;
        public double alertVl = 5;      // VL a partir de laquelle on alerte le staff
        public double punishVl = 0;     // 0 = jamais de sanction
        public double decay = 10;       // VL retirees par minute
        public boolean cancel = false;  // annuler l'action fautive quand c'est possible

        Check(String name) { this.name = name; }
    }

    private final KamoofGuard plugin;
    private final Map<String, Check> checks = new HashMap<>();

    // Reglages globaux
    public boolean punish;            // interrupteur maitre des sanctions
    public String punishCommand;
    public boolean logFile;
    public boolean logConsole;
    public int alertCooldownMs;
    public int joinGraceMs;
    public int teleportGraceMs;
    public int velocityGraceMs;
    public int damageGraceMs;
    public int maxPing;               // au dela, les checks de mouvement sont relaches
    public double minTps;             // en dessous, les checks de mouvement sont suspendus
    public boolean exemptCreative;
    public boolean exemptOps;
    public List<String> exemptWorlds;

    public Conf(KamoofGuard plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        plugin.reloadConfig();
        FileConfiguration c = plugin.getConfig();

        punish = c.getBoolean("punish", false);
        punishCommand = c.getString("punish-command", "kick %player% Comportement suspect detecte (%check%)");
        logFile = c.getBoolean("log.file", true);
        logConsole = c.getBoolean("log.console", true);
        alertCooldownMs = c.getInt("alert-cooldown-ms", 3000);
        joinGraceMs = c.getInt("grace.join-ms", 6000);
        teleportGraceMs = c.getInt("grace.teleport-ms", 3000);
        velocityGraceMs = c.getInt("grace.velocity-ms", 2000);
        damageGraceMs = c.getInt("grace.damage-ms", 1500);
        maxPing = c.getInt("lag.max-ping", 300);
        minTps = c.getDouble("lag.min-tps", 18.0);
        exemptCreative = c.getBoolean("exempt.creative", true);
        exemptOps = c.getBoolean("exempt.ops", false);
        exemptWorlds = c.getStringList("exempt.worlds");

        checks.clear();
        ConfigurationSection sec = c.getConfigurationSection("checks");
        if (sec != null) {
            for (String name : sec.getKeys(false)) {
                ConfigurationSection s = sec.getConfigurationSection(name);
                if (s == null) continue;
                Check chk = new Check(name);
                chk.enabled = s.getBoolean("enabled", true);
                chk.alertVl = s.getDouble("alert-vl", 5);
                chk.punishVl = s.getDouble("punish-vl", 0);
                chk.decay = s.getDouble("decay-per-minute", 10);
                chk.cancel = s.getBoolean("cancel", false);
                checks.put(name, chk);
            }
        }
    }

    /** Un check inconnu du fichier est considere actif avec les valeurs par defaut. */
    public Check check(String name) {
        return checks.computeIfAbsent(name, Check::new);
    }

    public boolean enabled(String check) {
        return check(check).enabled;
    }

    /** Reglage numerique specifique a un check (checks.<check>.<key>). */
    public double num(String check, String key, double def) {
        return plugin.getConfig().getDouble("checks." + check + "." + key, def);
    }

    public boolean flag(String check, String key, boolean def) {
        return plugin.getConfig().getBoolean("checks." + check + "." + key, def);
    }

    public Map<String, Check> all() {
        return checks;
    }
}
