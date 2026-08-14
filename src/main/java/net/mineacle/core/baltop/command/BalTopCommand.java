package net.mineacle.core.baltop.command;

import net.mineacle.core.Core;
import net.mineacle.core.baltop.gui.BalTopGui;
import net.mineacle.core.baltop.service.BalTopLeaderboardCache;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class BalTopCommand
        implements CommandExecutor, TabCompleter {

    private static final String SECONDARY = "&#B078FF";
    private static final String BODY = "&#bbbbbb";
    private static final String MONEY = "&#11fc7b";

    private final Core core;
    private final EconomyService economyService;
    private final BalTopLeaderboardCache leaderboardCache;

    public BalTopCommand(
            Core core,
            EconomyService economyService,
            BalTopLeaderboardCache leaderboardCache
    ) {
        this.core = core;
        this.economyService = economyService;
        this.leaderboardCache = leaderboardCache;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!sender.hasPermission(
                "mineaclebaltop.use"
        )) {
            sender.sendMessage(
                    core.getMessage(
                            "general.no-permission"
                    )
            );

            if (sender instanceof Player player) {
                SoundService.guiError(
                        player,
                        core
                );
            }

            return true;
        }

        if (!economyService.enabled()) {
            sender.sendMessage(
                    TextColor.color(
                            "&cEconomy is currently disabled"
                    )
            );
            return true;
        }

        if (args.length > 0) {
            sender.sendMessage(
                    TextColor.color(
                            "&cUsage: /baltop"
                    )
            );

            if (sender instanceof Player player) {
                SoundService.guiError(
                        player,
                        core
                );
            }

            return true;
        }

        if (sender instanceof Player player) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> BalTopGui.open(
                            player,
                            economyService,
                            leaderboardCache,
                            0
                    )
            );
            return true;
        }

        List<BalTopLeaderboardCache.Entry> entries =
                leaderboardCache.current()
                        .entries();

        sender.sendMessage(
                TextColor.color(
                        SECONDARY + "Balance Top"
                )
        );

        if (entries.isEmpty()) {
            sender.sendMessage(
                    TextColor.color(
                            BODY + "No balances recorded"
                    )
            );
            return true;
        }

        int limit = Math.min(
                10,
                entries.size()
        );

        for (int index = 0;
             index < limit;
             index++) {
            BalTopLeaderboardCache.Entry entry =
                    entries.get(index);

            sender.sendMessage(
                    TextColor.color(
                            SECONDARY
                                    + "#"
                                    + entry.placement()
                                    + " "
                                    + BODY
                                    + entry.displayName()
                                    + " "
                                    + MONEY
                                    + economyService.format(
                                    entry.balanceCents()
                            )
                    )
            );
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        return List.of();
    }
}
