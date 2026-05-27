package net.mineacle.core.security.command;

import net.mineacle.core.security.service.SecurityService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;

public final class SecurityCommand implements CommandExecutor, TabCompleter {

    private final SecurityService service;

    public SecurityCommand(SecurityService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!service.bypass(sender)) {
            sender.sendMessage(service.unknownMessage());
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(service.usageMessage());
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            service.reload();
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

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return service.commandTabs(sender, args[0]);
        }

        return List.of();
    }
}
