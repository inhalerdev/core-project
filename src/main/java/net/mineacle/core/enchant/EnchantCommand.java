package net.mineacle.core.enchant;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("NullableProblems")
public final class EnchantCommand implements CommandExecutor, TabCompleter {

    private final Core core;

    public EnchantCommand(Core core) {
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

        if (args.length < 2) {
            error(player, "&#bbbbbbUsage: &d/enchant <enchantment> <level>");
            return;
        }

        Enchantment enchantment = EnchantmentNames.find(args[0]);
        if (enchantment == null) {
            error(player, "&cUnknown enchantment");
            return;
        }

        Integer level = parseLevel(args[1]);
        if (level == null) {
            error(player, "&cLevel must be a number from 1 to " + enchantment.getMaxLevel());
            return;
        }

        if (level < enchantment.getStartLevel() || level > enchantment.getMaxLevel()) {
            error(player, "&c" + EnchantmentNames.displayName(enchantment) + " max level is " + enchantment.getMaxLevel());
            return;
        }

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType() == Material.AIR) {
            error(player, "&cHold an item first");
            return;
        }

        try {
            item.addEnchantment(enchantment, level);
        } catch (IllegalArgumentException exception) {
            error(player, "&cThat enchantment cannot be applied to this item");
            return;
        }

        player.getInventory().setItemInMainHand(item);
        success(player, "&#bbbbbbApplied &d" + EnchantmentNames.displayName(enchantment) + " " + level + " &#bbbbbbto your held item");
    }

    private Integer parseLevel(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            return Integer.parseInt(input);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private void success(Player player, String message) {
        send(player, message);
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
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player) || !player.hasPermission("mineacleenchant.admin")) {
            return completions;
        }

        if (args.length == 1) {
            return EnchantmentNames.completions(args[0]);
        }

        if (args.length == 2) {
            Enchantment enchantment = EnchantmentNames.find(args[0]);
            if (enchantment == null) {
                return completions;
            }

            String partial = args[1].toLowerCase(Locale.ROOT);
            for (int level = enchantment.getStartLevel(); level <= enchantment.getMaxLevel(); level++) {
                String option = String.valueOf(level);
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }
        }

        return completions;
    }
}
