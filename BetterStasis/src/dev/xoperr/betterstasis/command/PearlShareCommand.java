package dev.xoperr.betterstasis.command;

import dev.xoperr.betterstasis.handler.SharingHandler;
import dev.xoperr.betterstasis.util.FeedbackUtil;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Command executor for pearl sharing commands.
 * Handles /pearlshare, /pearlunshare, and /pearlshared.
 */
public class PearlShareCommand implements CommandExecutor, TabCompleter {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Must be a player
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        // Check permission
        if (!player.hasPermission("betterstasis.share")) {
            FeedbackUtil.sendMessage(player,
                "You don't have permission to use this command!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return true;
        }

        // Get rod from main hand
        ItemStack rod = player.getInventory().getItemInMainHand();

        // Route to appropriate handler
        String cmdName = command.getName().toLowerCase();

        switch (cmdName) {
            case "pearlshare":
                if (args.length != 1) {
                    FeedbackUtil.sendMessage(player,
                        "Usage: /pearlshare <player>",
                        FeedbackUtil.MessageType.ERROR);
                    return true;
                }
                SharingHandler.shareRod(player, rod, args[0]);
                break;

            case "pearlunshare":
                if (args.length != 1) {
                    FeedbackUtil.sendMessage(player,
                        "Usage: /pearlunshare <player>",
                        FeedbackUtil.MessageType.ERROR);
                    return true;
                }
                SharingHandler.unshareRod(player, rod, args[0]);
                break;

            case "pearlshared":
                if (args.length != 0) {
                    FeedbackUtil.sendMessage(player,
                        "Usage: /pearlshared",
                        FeedbackUtil.MessageType.ERROR);
                    return true;
                }
                SharingHandler.listSharedPlayers(player, rod);
                break;

            default:
                return false;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        // Only tab complete player names for share/unshare commands
        String cmdName = command.getName().toLowerCase();

        if (cmdName.equals("pearlshared")) {
            return new ArrayList<>(); // No arguments
        }

        if (args.length == 1) {
            // Return list of online player names
            String partial = args[0].toLowerCase();
            return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(partial))
                .collect(Collectors.toList());
        }

        return new ArrayList<>();
    }
}
