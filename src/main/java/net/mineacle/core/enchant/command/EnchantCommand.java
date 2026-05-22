package net.mineacle.core.enchant.command;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.NamespacedKey;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class EnchantCommand implements CommandExecutor, TabCompleter {

    private final Core core;

    public EnchantCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!player.hasPermission("mineacleenchant.admin")) {
            error(player, "&cYou do not have permission");
            return true;
        }

        ItemStack item = player.getInventory().getItemInMainHand();

        if (item == null || item.getType() == Material.AIR) {
            error(player, "&cHold an item to enchant");
            return true;
        }

        if (args.length == 0) {
            help(player);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("clear") || sub.equals("removeall")) {
            clear(player, item);
            return true;
        }

        if (sub.equals("remove")) {
            if (args.length < 2) {
                error(player, "&cUsage: /enchant remove <enchant>");
                return true;
            }

            Enchantment enchantment = enchantment(args[1]);

            if (enchantment == null) {
                error(player, "&cUnknown enchantment");
                return true;
            }

            item.removeEnchantment(enchantment);
            success(player, "&#bbbbbbRemoved &#ff88ff" + pretty(enchantment) + " &#bbbbbbfrom held item");
            return true;
        }

        if (sub.equals("max")) {
            boolean unsafe = args.length >= 2 && args[1].equalsIgnoreCase("unsafe");
            max(player, item, unsafe);
            return true;
        }

        if (sub.equals("list")) {
            list(player, item);
            return true;
        }

        Enchantment enchantment = enchantment(args[0]);

        if (enchantment == null) {
            error(player, "&cUnknown enchantment");
            return true;
        }

        int level = enchantment.getMaxLevel();

        if (args.length >= 2) {
            try {
                level = Integer.parseInt(args[1]);
            } catch (NumberFormatException exception) {
                error(player, "&cLevel must be a number");
                return true;
            }
        }

        if (level <= 0) {
            item.removeEnchantment(enchantment);
            success(player, "&#bbbbbbRemoved &#ff88ff" + pretty(enchantment) + " &#bbbbbbfrom held item");
            return true;
        }

        boolean unsafe = args.length >= 3 && args[2].equalsIgnoreCase("unsafe");

        if (!unsafe && !enchantment.canEnchantItem(item)) {
            error(player, "&cThat enchantment cannot be applied to this item");
            player.sendMessage(TextColor.color("&#bbbbbbUse &#ff88ff/enchant " + key(enchantment) + " " + level + " unsafe &#bbbbbbto force it"));
            return true;
        }

        if (!unsafe && level > enchantment.getMaxLevel()) {
            error(player, "&cThat level is too high");
            player.sendMessage(TextColor.color("&#bbbbbbUse &#ff88ff/enchant " + key(enchantment) + " " + level + " unsafe &#bbbbbbto force it"));
            return true;
        }

        if (unsafe) {
            item.addUnsafeEnchantment(enchantment, level);
        } else {
            item.addEnchantment(enchantment, level);
        }

        success(player, "&#bbbbbbAdded &#ff88ff" + pretty(enchantment) + " " + level + " &#bbbbbbto held item");
        return true;
    }

    private void clear(Player player, ItemStack item) {
        if (item.getEnchantments().isEmpty()) {
            error(player, "&cHeld item has no enchantments");
            return;
        }

        for (Enchantment enchantment : new ArrayList<>(item.getEnchantments().keySet())) {
            item.removeEnchantment(enchantment);
        }

        success(player, "&#bbbbbbRemoved all enchantments from held item");
    }

    private void max(Player player, ItemStack item, boolean unsafe) {
        int applied = 0;

        for (Enchantment enchantment : enchantments()) {
            if (!unsafe && !enchantment.canEnchantItem(item)) {
                continue;
            }

            if (unsafe) {
                item.addUnsafeEnchantment(enchantment, Math.max(1, enchantment.getMaxLevel()));
            } else {
                item.addEnchantment(enchantment, Math.max(1, enchantment.getMaxLevel()));
            }

            applied++;
        }

        if (applied <= 0) {
            error(player, "&cNo compatible enchantments found");
            return;
        }

        success(player, "&#bbbbbbApplied &#ff88ff" + applied + " &#bbbbbbmax enchantments to held item");
    }

    private void list(Player player, ItemStack item) {
        Map<Enchantment, Integer> enchantments = item.getEnchantments();

        if (enchantments.isEmpty()) {
            error(player, "&cHeld item has no enchantments");
            return;
        }

        player.sendMessage(TextColor.color("&#ff88ffHeld Item Enchants"));

        enchantments.entrySet().stream()
                .sorted(Comparator.comparing(entry -> pretty(entry.getKey())))
                .forEach(entry -> player.sendMessage(TextColor.color(
                        "&#bbbbbb- &#ff88ff" + pretty(entry.getKey()) + " &#bbbbbb" + entry.getValue()
                )));

        SoundService.economyBalance(player, core);
    }

    private void help(Player player) {
        player.sendMessage(TextColor.color("&#ff88ffEnchant Commands"));
        player.sendMessage(TextColor.color("&#bbbbbb/enchant <enchant> [level]"));
        player.sendMessage(TextColor.color("&#bbbbbb/enchant <enchant> <level> unsafe"));
        player.sendMessage(TextColor.color("&#bbbbbb/enchant remove <enchant>"));
        player.sendMessage(TextColor.color("&#bbbbbb/enchant clear"));
        player.sendMessage(TextColor.color("&#bbbbbb/enchant max"));
        player.sendMessage(TextColor.color("&#bbbbbb/enchant max unsafe"));
        player.sendMessage(TextColor.color("&#bbbbbb/enchant list"));
        SoundService.economyBalance(player, core);
    }

    private Enchantment enchantment(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String normalized = input.toLowerCase(Locale.ROOT)
                .replace("minecraft:", "")
                .replace(" ", "_")
                .replace("-", "_");

        Enchantment exact = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(normalized));

        if (exact != null) {
            return exact;
        }

        for (Enchantment enchantment : enchantments()) {
            if (key(enchantment).equalsIgnoreCase(normalized)) {
                return enchantment;
            }

            if (pretty(enchantment).replace(" ", "_").equalsIgnoreCase(normalized)) {
                return enchantment;
            }
        }

        return null;
    }

    private List<Enchantment> enchantments() {
        List<Enchantment> list = new ArrayList<>();

        Registry.ENCHANTMENT.forEach(list::add);
        list.sort(Comparator.comparing(this::key));
        return list;
    }

    private String key(Enchantment enchantment) {
        NamespacedKey key = enchantment.getKey();
        return key == null ? enchantment.getName().toLowerCase(Locale.ROOT) : key.getKey();
    }

    private String pretty(Enchantment enchantment) {
        String raw = key(enchantment).replace("_", " ");
        StringBuilder builder = new StringBuilder();

        for (String part : raw.split(" ")) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return builder.toString();
    }

    private void success(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message)));
        SoundService.guiConfirm(player, core);
    }

    private void error(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message)));
        SoundService.guiError(player, core);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player) || !player.hasPermission("mineacleenchant.admin")) {
            return completions;
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);

            for (String option : List.of("clear", "list", "max", "remove")) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }

            for (Enchantment enchantment : enchantments()) {
                String key = key(enchantment);

                if (key.startsWith(partial)) {
                    completions.add(key);
                }
            }

            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            String partial = args[1].toLowerCase(Locale.ROOT);

            for (Enchantment enchantment : player.getInventory().getItemInMainHand().getEnchantments().keySet()) {
                String key = key(enchantment);

                if (key.startsWith(partial)) {
                    completions.add(key);
                }
            }

            return completions;
        }

        if (args.length == 2 && !args[0].equalsIgnoreCase("clear") && !args[0].equalsIgnoreCase("list")) {
            Enchantment enchantment = enchantment(args[0]);

            if (enchantment != null) {
                completions.add(String.valueOf(Math.max(1, enchantment.getMaxLevel())));

                for (String level : List.of("1", "2", "3", "4", "5", "10", "100", "255")) {
                    if (!completions.contains(level)) {
                        completions.add(level);
                    }
                }
            }

            return completions;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("max")) {
            if ("unsafe".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                completions.add("unsafe");
            }

            return completions;
        }

        if (args.length == 3 && enchantment(args[0]) != null) {
            if ("unsafe".startsWith(args[2].toLowerCase(Locale.ROOT))) {
                completions.add("unsafe");
            }
        }

        return completions;
    }
}
