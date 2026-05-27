package net.mineacle.core.security.command;

import net.mineacle.core.security.service.SecurityService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
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

        if (args.length > 0 && args[0].equalsIgnoreCase("groups") && sender instanceof Player player) {
            sender.sendMessage("§7Active security groups: §d" + String.join("§7, §d", service.activeGroupNames(player)));
            return true;
        }

        sender.sendMessage("§7Usage: §d/mineaclesecurity reload");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!service.bypass(sender)) {
            return List.of();
        }

        if (args.length == 1) {
            String input = args[0].toLowerCase(Locale.ROOT);
            List<String> options = new ArrayList<>();
            if ("reload".startsWith(input)) {
                options.add("reload");
            }
            if (sender instanceof Player && "groups".startsWith(input)) {
                options.add("groups");
            }
            return options;
        }

        return List.of();
    }
}
