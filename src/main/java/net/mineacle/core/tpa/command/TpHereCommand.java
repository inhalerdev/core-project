package net.mineacle.core.tpa.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class TpHereCommand
        implements CommandExecutor, TabCompleter {

    private static final String SECONDARY =
            "&#B078FF";
    private static final String BODY =
            "&#bbbbbb";

    private final Core core;

    public TpHereCommand(Core core) {
        this.core = core;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (!viewer.hasPermission(TpCommand.PERMISSION)) {
            fail(viewer, "&cYou do not have permission");
            return true;
        }

        if (args.length != 1) {
            fail(viewer, "&cUsage: /tphere <player>");
            return true;
        }

        Player target = TpCommand.resolveForStaff(
                viewer,
                args[0]
        );

        if (target == null) {
            fail(viewer, "&cThat player is not online");
            return true;
        }

        if (!target.teleport(viewer.getLocation())) {
            fail(viewer, "&cTeleport failed");
            return true;
        }

        viewer.sendMessage(
                TextColor.color(
                        BODY
                                + "Teleported "
                                + SECONDARY
                                + DisplayNames.displayName(target)
                                + BODY
                                + " to you"
                )
        );
        SoundService.teleportComplete(viewer, core);

        if (!target.getUniqueId()
                .equals(viewer.getUniqueId())) {
            SoundService.teleportComplete(target, core);
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
        if (!(sender instanceof Player viewer)
                || !viewer.hasPermission(TpCommand.PERMISSION)
                || args.length != 1) {
            return List.of();
        }

        return TpCommand.staffCompletions(
                viewer,
                args[0]
        );
    }

    private void fail(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }
}
