package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.AdminInspectService;
import net.mineacle.core.admininspect.service.AdminInspectService.InspectType;
import net.mineacle.core.admininspect.service.AdminInspectService.OpenResult;
import net.mineacle.core.common.player.DisplayNames;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;

abstract class AbstractInspectCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final AdminInspectService service;
    private final InspectType type;

    protected AbstractInspectCommand(
            Core core,
            AdminInspectService service,
            InspectType type
    ) {
        this.core = core;
        this.service = service;
        this.type = type;
    }

    @Override
    public final boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender
                instanceof Player viewer)) {
            sender.sendMessage(
                    core.getMessage(
                            "general.players-only"
                    )
            );
            return true;
        }

        if (args.length != 1) {
            service.fail(
                    viewer,
                    OpenResult.USAGE,
                    type
            );
            return true;
        }

        Player target =
                DisplayNames.resolveOnline(
                        args[0]
                );

        OpenResult result =
                service.open(
                        viewer,
                        target,
                        type
                );

        if (result != OpenResult.SUCCESS) {
            service.fail(
                    viewer,
                    result,
                    type
            );
        }

        return true;
    }

    @Override
    public final List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        if (!(sender
                instanceof Player viewer)
                || args.length != 1) {
            return List.of();
        }

        return service.completions(
                viewer,
                type,
                args[0]
        );
    }
}
