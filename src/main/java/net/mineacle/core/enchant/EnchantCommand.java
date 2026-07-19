package net.mineacle.core.enchant;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.PlayerTabComplete;
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
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class EnchantCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;

    public EnchantCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        run(sender, args);
        return true;
    }

    public void run(
            CommandSender sender,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return;
        }

        if (!player.hasPermission("mineacleenchant.admin")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return;
        }

        if (args.length != 2) {
            error(
                    player,
                    "&cUsage: /enchant "
                            + "<enchantment> <level|remove>"
            );
            return;
        }

        Enchantment enchantment =
                EnchantmentNames.find(args[0]);

        if (enchantment == null) {
            error(player, "&cUnknown enchantment");
            return;
        }

        ItemStack item = player.getInventory()
                .getItemInMainHand();

        if (item == null || item.getType() == Material.AIR) {
            error(player, "&cHold an item first");
            return;
        }

        if (isRemoval(args[1])) {
            remove(player, item, enchantment);
            return;
        }

        Integer level = parseLevel(args[1]);

        if (level == null) {
            error(
                    player,
                    "&cLevel must be "
                            + enchantment.getStartLevel()
                            + " through "
                            + enchantment.getMaxLevel()
                            + " or remove"
            );
            return;
        }

        if (level < enchantment.getStartLevel()
                || level > enchantment.getMaxLevel()) {
            error(
                    player,
                    "&c"
                            + EnchantmentNames.displayName(
                            enchantment
                    )
                            + " supports levels "
                            + enchantment.getStartLevel()
                            + " through "
                            + enchantment.getMaxLevel()
            );
            return;
        }

        if (!apply(item, enchantment, level)) {
            error(
                    player,
                    "&cThat enchantment cannot be applied "
                            + "to this item or conflicts "
                            + "with an existing enchantment"
            );
            return;
        }

        player.getInventory().setItemInMainHand(item);

        success(
                player,
                "&#bbbbbbApplied &d"
                        + EnchantmentNames.displayName(
                        enchantment
                )
                        + " &#ff88ff"
                        + level
                        + " &#bbbbbbto your held item"
        );
    }

    public List<String> complete(
            CommandSender sender,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission(
                "mineacleenchant.admin"
        )) {
            return List.of();
        }

        if (args.length == 1) {
            return EnchantmentNames.completions(args[0]);
        }

        if (args.length == 2) {
            Enchantment enchantment =
                    EnchantmentNames.find(args[0]);

            if (enchantment == null) {
                return List.of();
            }

            List<String> options = new ArrayList<>();
            options.add("remove");
            options.add("0");

            for (int level = enchantment.getStartLevel();
                 level <= enchantment.getMaxLevel();
                 level++) {
                options.add(String.valueOf(level));
            }

            return PlayerTabComplete.options(
                    args[1],
                    options
            );
        }

        return List.of();
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        return complete(sender, args);
    }

    private boolean apply(
            ItemStack item,
            Enchantment enchantment,
            int level
    ) {
        ItemMeta meta = item.getItemMeta();

        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            if (storageMeta.hasConflictingStoredEnchant(
                    enchantment
            )) {
                return false;
            }

            boolean changed = storageMeta.addStoredEnchant(
                    enchantment,
                    level,
                    false
            );

            if (!changed
                    && storageMeta.getStoredEnchantLevel(
                    enchantment
            ) != level) {
                return false;
            }

            item.setItemMeta(storageMeta);
            return true;
        }

        if (meta == null
                || !enchantment.canEnchantItem(item)
                || meta.hasConflictingEnchant(enchantment)) {
            return false;
        }

        try {
            item.addEnchantment(enchantment, level);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void remove(
            Player player,
            ItemStack item,
            Enchantment enchantment
    ) {
        ItemMeta meta = item.getItemMeta();
        boolean removed;

        if (meta instanceof EnchantmentStorageMeta storageMeta) {
            removed = storageMeta.removeStoredEnchant(
                    enchantment
            );

            if (removed) {
                item.setItemMeta(storageMeta);
            }
        } else {
            removed = item.removeEnchantment(enchantment) > 0;
        }

        if (!removed) {
            error(
                    player,
                    "&cYour held item does not have &d"
                            + EnchantmentNames.displayName(
                            enchantment
                    )
            );
            return;
        }

        player.getInventory().setItemInMainHand(item);
        send(
                player,
                "&#bbbbbbRemoved &d"
                        + EnchantmentNames.displayName(
                        enchantment
                )
                        + " &#bbbbbbfrom your held item"
        );
        SoundService.featureDisable(player, core);
    }

    private boolean isRemoval(String input) {
        if (input == null) {
            return false;
        }

        String normalized = input
                .trim()
                .toLowerCase(Locale.ROOT);

        return normalized.equals("remove")
                || normalized.equals("delete")
                || normalized.equals("off")
                || normalized.equals("0");
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

    private void success(
            Player player,
            String message
    ) {
        send(player, message);
        SoundService.featureEnable(player, core);
    }

    private void error(
            Player player,
            String message
    ) {
        send(player, message);
        SoundService.guiError(player, core);
    }

    private void send(
            Player player,
            String message
    ) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }
}
