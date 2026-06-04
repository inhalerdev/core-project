package net.mineacle.core.servermessages.command;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.servermessages.service.ServerMessageService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ServerControlCommand implements CommandExecutor, TabCompleter {

    private final ServerMessageService service;

    public ServerControlCommand(ServerMessageService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        return switch (name) {
            case "mineacleservermessages" -> handleReload(sender, args);
            case "mineaclemaintenance" -> handleMaintenance(sender, args);
            default -> true;
        };
    }

    private boolean handleReload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mineacleservermessages.admin")) {
            deny(sender);
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("reload")) {
            service.reload();
            sender.sendMessage(service.chat("reloaded"));
            return true;
        }

        sender.sendMessage(color("&dMineacle &8» &#bbbbbbUsage: &d/mineacleservermessages reload"));
        return true;
    }

    private boolean handleMaintenance(CommandSender sender, String[] args) {
        if (!sender.hasPermission("mineacleservermessages.admin")) {
            deny(sender);
            return true;
        }

        if (args.length == 0) {
            String status = service.maintenanceEnabled() ? "&aenabled" : "&cdisabled";
            sender.sendMessage(color("&dMineacle &8» &#bbbbbbMaintenance is " + status));
            return true;
        }

        if (args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("enable")) {
            service.setMaintenance(true);
            sender.sendMessage(service.chat("maintenance-enabled"));
            return true;
        }

        if (args[0].equalsIgnoreCase("off") || args[0].equalsIgnoreCase("disable")) {
            service.setMaintenance(false);
            sender.sendMessage(service.chat("maintenance-disabled"));
            return true;
        }

        sender.sendMessage(color("&dMineacle &8» &#bbbbbbUsage: &d/mineaclemaintenance <on|off>"));
        return true;
    }

    private void deny(CommandSender sender) {
        sender.sendMessage(color("&cThis command does not exist"));
    }

    private String color(String input) {
        return TextColor.color(input);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("mineacleservermessages.admin")) {
            return List.of();
        }

        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("mineacleservermessages")) {
            if (args.length == 1) {
                return startsWith(List.of("reload"), args[0]);
            }

            return List.of();
        }

        if (name.equals("mineaclemaintenance")) {
            if (args.length == 1) {
                return startsWith(List.of("on", "off"), args[0]);
            }

            return List.of();
        }

        return List.of();
    }

    private List<String> startsWith(List<String> options, String input) {
        if (input == null || input.isBlank()) {
            return options;
        }

        String lower = input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();

        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }

        return matches;
    }
}
