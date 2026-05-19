package net.mineacle.core.baltop.command;

import net.mineacle.core.Core;
import net.mineacle.core.baltop.gui.BalTopGui;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BalTopCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final EconomyService economyService;

    public BalTopCommand(Core core, EconomyService economyService) {
        this.core = core;
        this.economyService = economyService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mineaclebaltop.use")) {
            sender.sendMessage(core.getMessage("general.no-permission"));

            if (sender instanceof Player player) {
                SoundService.guiError(player, core);
            }

            return true;
        }

        if (sender instanceof Player player) {
            MenuHistory.openRoot(core, player, () -> BalTopGui.open(core, player, economyService, 0));
            return true;
        }

        sender.sendMessage(core.getMessage("baltop.header"));

        List<Map.Entry<UUID, Long>> top = economyService.topBalances(10);

        if (top.isEmpty()) {
            sender.sendMessage(core.getMessage("baltop.empty"));
            return true;
        }

        int position = 1;

        for (Map.Entry<UUID, Long> entry : top) {
            OfflinePlayer target = Bukkit.getOfflinePlayer(entry.getKey());
            String name = target.getName() == null ? entry.getKey().toString() : target.getName();

            sender.sendMessage(core.getMessage("baltop.line")
                    .replace("%position%", String.valueOf(position))
                    .replace("%player%", name)
                    .replace("%balance%", economyService.format(entry.getValue())));

            position++;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
