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
import org.bukkit.inventory.meta.EnchantmentStorageMeta;

import java.util.List;

public final class EnchantInfoCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;

    public EnchantInfoCommand(Core core) {
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

        if (args.length != 1) {
            error(
                    player,
                    "&cUsage: /enchantinfo <enchantment>"
            );
            return;
        }

        Enchantment enchantment =
                EnchantmentNames.find(args[0]);

        if (enchantment == null) {
            error(player, "&cUnknown enchantment");
            return;
        }

        String displayName =
                EnchantmentNames.displayName(enchantment);
        ItemStack held = player.getInventory()
                .getItemInMainHand();
        String applicable = applicability(
                held,
                enchantment
        );

        player.sendMessage(TextColor.color(
                "&d" + displayName
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbKey: &#ff88ff"
                        + EnchantmentNames.namespacedKey(
                        enchantment
                )
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbLevels: &#ff88ff"
                        + enchantment.getStartLevel()
                        + "&#bbbbbb–&#ff88ff"
                        + enchantment.getMaxLevel()
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbHeld Item: " + applicable
        ));

        if (enchantment.isCursed()) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbType: &cCurse"
            ));
        }

        player.sendActionBar(actionBar(
                "&#bbbbbbEnchant info: &d" + displayName
        ));
        SoundService.featureEnable(player, core);
    }

    public List<String> complete(
            CommandSender sender,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission(
                "mineacleenchant.admin"
        )
                || args.length != 1) {
            return List.of();
        }

        return EnchantmentNames.completions(args[0]);
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

    private String applicability(
            ItemStack item,
            Enchantment enchantment
    ) {
        if (item == null || item.getType() == Material.AIR) {
            return "&#bbbbbbNo item held";
        }

        if (item.getItemMeta()
                instanceof EnchantmentStorageMeta) {
            return "&aApplicable to enchanted book";
        }

        if (!enchantment.canEnchantItem(item)) {
            return "&cNot applicable";
        }

        if (item.getItemMeta() != null
                && item.getItemMeta()
                .hasConflictingEnchant(enchantment)) {
            return "&cConflicts with current enchantments";
        }

        return "&aApplicable";
    }

    private void error(
            Player player,
            String message
    ) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
        SoundService.guiError(player, core);
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }
}
