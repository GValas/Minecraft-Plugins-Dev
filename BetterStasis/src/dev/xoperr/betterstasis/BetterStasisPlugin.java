package dev.xoperr.betterstasis;

import dev.xoperr.betterstasis.command.PearlShareCommand;
import dev.xoperr.betterstasis.command.PearlToggleCommand;
import dev.xoperr.betterstasis.handler.EconomyHandler;
import dev.xoperr.betterstasis.listener.PlayerInteractListener;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Main plugin class for Better Stasis Chamber system.
 * Allows players to bind ender pearls to fishing rods for later teleportation.
 */
public class BetterStasisPlugin extends JavaPlugin {

    private static BetterStasisPlugin instance;

    @Override
    public void onEnable() {
        instance = this;

        // Save default configuration
        saveDefaultConfig();

        // Setup economy integration
        EconomyHandler.setupEconomy();

        // Register event listeners
        getServer().getPluginManager().registerEvents(
            new PlayerInteractListener(this), this
        );

        // Register commands
        PearlShareCommand shareCommand = new PearlShareCommand();
        getCommand("pearlshare").setExecutor(shareCommand);
        getCommand("pearlshare").setTabCompleter(shareCommand);
        getCommand("pearlunshare").setExecutor(shareCommand);
        getCommand("pearlunshare").setTabCompleter(shareCommand);
        getCommand("pearlshared").setExecutor(shareCommand);
        getCommand("pearlshared").setTabCompleter(shareCommand);
        getCommand("pearltoggle").setExecutor(new PearlToggleCommand());

        getLogger().info("Better Stasis plugin enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Better Stasis plugin disabled!");
    }

    /**
     * Get the plugin instance.
     *
     * @return The plugin instance
     */
    public static BetterStasisPlugin getInstance() {
        return instance;
    }
}
