package net.mineacle.core.stats.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.stats.PlayerStatisticsGui;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;

public final class StatsCommand
        implements CommandExecutor, TabCompleter {

    private final PlayerStatisticsGui playerStatisticsGui;

    public StatsCommand(
            PlayerStatisticsGui playerStatisticsGui
    ) {
        this.playerStatisticsGui = playerStatisticsGui;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage("Players only");
            return true;
        }

        Core core = Core.instance();

        if (!viewer.hasPermission("mineaclestats.use")) {
            viewer.sendMessage(
                    TextColor.color(
                            "&cYou do not have permission"
                    )
            );
            SoundService.guiError(viewer, core);
            return true;
        }

        if (args.length == 0) {
            playerStatisticsGui.open(
                    viewer,
                    viewer.getUniqueId()
            );
            return true;
        }

        if (args.length != 1) {
            viewer.sendMessage(
                    TextColor.color(
                            "&#bbbbbbUsage: &d/stats [player]"
                    )
            );
            SoundService.guiError(viewer, core);
            return true;
        }

        OfflinePlayer target =
                DisplayNames.resolveOffline(args[0]);

        if (target == null
                || target.getName() == null
                && !target.hasPlayedBefore()) {
            viewer.sendMessage(
                    TextColor.color(
                            "&cThat player could not be found"
                    )
            );
            SoundService.guiError(viewer, core);
            return true;
        }

        playerStatisticsGui.open(
                viewer,
                target.getUniqueId()
        );
        return true;
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission(
                "mineaclestats.use"
        )
                || args.length != 1) {
            return List.of();
        }

        /*
         * Suggestions intentionally contain online, visible players only.
         * includeSelf=true keeps /stats <space> useful when the viewer is the
         * only player online and avoids the appearance of broken completion.
         */
        return PlayerTabComplete.onlinePlayers(
                player,
                args[0],
                true
        );
    }
}
