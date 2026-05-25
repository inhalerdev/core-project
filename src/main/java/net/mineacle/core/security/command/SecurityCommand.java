package net.mineacle.core.security.command;

import net.mineacle.core.security.service.SecurityService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.List;
import java.util.Locale;

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

        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            service.reload();
            sender.sendMessage(service.reloadMessage());
            return true;
        }

        sender.sendMessage("Usage: /mineaclesecurity reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!service.bypass(sender)) {
            return List.of();
        }

        if (args.length == 1 && "reload".startsWith(args[0].toLowerCase(Locale.ROOT))) {
            return List.of("reload");
        }

        return List.of();
    }
}
