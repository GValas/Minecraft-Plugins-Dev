package dev.xoperr.betterstasis.command;

import dev.xoperr.betterstasis.util.FeedbackUtil;
import dev.xoperr.betterstasis.util.PearlDataUtil;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Command executor for /pearltoggle.
 * Toggles visibility of binding information on fishing rods.
 */
public class PearlToggleCommand implements CommandExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Must be a player
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        // Check permission
        if (!player.hasPermission("betterstasis.toggle")) {
            FeedbackUtil.sendMessage(player,
                "You don't have permission to use this command!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return true;
        }

        // Get rod from main hand
        ItemStack rod = player.getInventory().getItemInMainHand();

        if (rod == null || rod.getType() != Material.FISHING_ROD) {
            FeedbackUtil.sendMessage(player,
                "You must be holding a fishing rod!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return true;
        }

        if (!PearlDataUtil.hasBoundPearl(rod)) {
            FeedbackUtil.sendMessage(player,
                "This rod is not bound to a pearl!",
                FeedbackUtil.MessageType.ERROR);
            FeedbackUtil.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 0.5f);
            return true;
        }

        // Toggle visibility
        boolean wasHidden = PearlDataUtil.isInfoHidden(rod);
        PearlDataUtil.toggleInfoHidden(rod);
        boolean nowHidden = PearlDataUtil.isInfoHidden(rod);

        // Provide feedback
        if (nowHidden) {
            FeedbackUtil.sendMessage(player,
                "Rod information hidden!",
                FeedbackUtil.MessageType.SUCCESS);
        } else {
            FeedbackUtil.sendMessage(player,
                "Rod information now visible!",
                FeedbackUtil.MessageType.SUCCESS);
        }

        FeedbackUtil.playSound(player, Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0f, 1.5f);

        return true;
    }
}
