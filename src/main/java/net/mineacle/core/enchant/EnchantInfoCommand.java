package net.mineacle.core.enchant;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

@SuppressWarnings("NullableProblems")
public final class EnchantInfoCommand implements CommandExecutor, TabCompleter {

    private final Core core;

    public EnchantInfoCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        run(sender, args);
        return true;
    }

    public void run(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return;
        }

        if (!player.hasPermission("mineacleenchant.admin")) {
            error(player, "&cYou do not have permission");
            return;
        }

        if (args.length < 1) {
            error(player, "&#bbbbbbUsage: &d/enchantinfo <enchantment>");
            return;
        }

        Enchantment enchantment = EnchantmentNames.find(args[0]);

        if (enchantment == null) {
            error(player, "&cUnknown enchantment");
            return;
        }

        send(player, "&#ff55ff" + EnchantmentNames.displayName(enchantment));
        send(player, "&#bbbbbbKey: &d" + EnchantmentNames.key(enchantment));
        send(player, "&#bbbbbbVanilla max level: &d" + enchantment.getMaxLevel());
        send(player, "&#bbbbbbStart level: &d" + enchantment.getStartLevel());
        SoundService.featureEnable(player, core);
    }

    private void error(Player player, String message) {
        send(player, message);
        SoundService.guiError(player, core);
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("mineacleenchant.admin")) {
            return new ArrayList<>();
        }

        if (args.length == 1) {
            return EnchantmentNames.completions(args[0]);
        }

        return new ArrayList<>();
    }
}
