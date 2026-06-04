package net.mineacle.core.worldmaintenance.command;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.worldmaintenance.service.WorldMaintenanceService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class WorldMaintenanceCommand implements CommandExecutor, TabCompleter {

    private final WorldMaintenanceService service;

    public WorldMaintenanceCommand(WorldMaintenanceService service) {
        this.service = service;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!sender.hasPermission("mineacleworldmaintenance.admin")) {
            sender.sendMessage(color("&cThis command does not exist"));
            return true;
        }

        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sendStatus(sender);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            service.reload();
            sender.sendMessage(color("&dMineacle &8» &#bbbbbbWorld maintenance reloaded"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(color("&dMineacle &8» &#bbbbbbUsage: &d/" + label + " <group> <on|off>"));
            return true;
        }

        String group = args[0].toLowerCase(Locale.ROOT);
        if (!service.groupExists(group)) {
            sender.sendMessage(color("&dMineacle &8» &cUnknown world maintenance group"));
            return true;
        }

        if (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("enable")) {
            service.setEnabled(group, true);
            service.applyGroupToOnlinePlayers(group);
            sender.sendMessage(color("&dMineacle &8» &#bbbbbbWorld maintenance enabled for &d" + group));
            return true;
        }

        if (args[1].equalsIgnoreCase("off") || args[1].equalsIgnoreCase("disable")) {
            service.setEnabled(group, false);
            sender.sendMessage(color("&dMineacle &8» &#bbbbbbWorld maintenance disabled for &d" + group));
            return true;
        }

        sender.sendMessage(color("&dMineacle &8» &#bbbbbbUsage: &d/" + label + " <group> <on|off>"));
        return true;
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(color("&dMineacle &8» &#bbbbbbWorld maintenance status"));

        List<String> groups = service.groups();
        if (groups.isEmpty()) {
            sender.sendMessage(color("&#bbbbbbNo groups configured"));
            return;
        }

        for (String group : groups) {
            sender.sendMessage(service.statusLine(group));
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (!sender.hasPermission("mineacleworldmaintenance.admin")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(service.groups());
            options.add("status");
            options.add("reload");
            return startsWith(options, args[0]);
        }

        if (args.length == 2 && service.groupExists(args[0])) {
            return startsWith(List.of("on", "off"), args[1]);
        }

        return List.of();
    }

    private List<String> startsWith(List<String> values, String token) {
        if (token == null || token.isBlank()) {
            return values;
        }

        String lower = token.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();

        for (String value : values) {
            if (value.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(value);
            }
        }

        return matches;
    }

    private String color(String input) {
        return TextColor.color(input);
    }
}
