package net.mineacle.core.security.command;

import net.mineacle.core.security.service.SecurityService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public final class SecurityCommand
        implements CommandExecutor, TabCompleter {

    private final SecurityService service;

    public SecurityCommand(SecurityService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String @NotNull [] args
    ) {
        if (service.canManage(sender)) {
            if (args.length != 1) {
                sender.sendMessage(service.usageMessage());
                return true;
            }

            if (args[0].equalsIgnoreCase("reload")) {
                service.reload();
                service.refreshAllCommandTrees();
                sender.sendMessage(service.reloadMessage());
                return true;
            }

            if (args[0].equalsIgnoreCase("groups")) {
                sender.sendMessage(service.groupsMessage(sender));
                return true;
            }

            sender.sendMessage(service.usageMessage());
            return true;
        }

        sender.sendMessage(service.unknownMessage());
        return true;
    }

    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String @NotNull [] args
    ) {
        if (args.length != 1) {
            return List.of();
        }

        return service.commandTabs(sender, args[0]);
    }
}
