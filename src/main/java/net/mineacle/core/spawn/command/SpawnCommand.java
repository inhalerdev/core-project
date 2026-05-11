package net.mineacle.core.spawn.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.spawn.gui.SpawnGui;
import net.mineacle.core.spawn.model.SpawnPoint;
import net.mineacle.core.spawn.service.SpawnService;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpawnCommand implements CommandExecutor, TabCompleter {

    private final SpawnService spawnService;

    public SpawnCommand(SpawnService spawnService) {
        this.spawnService = spawnService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!spawnService.enabled()) {
            send(player, "§cSpawn menu is disabled");
            return true;
        }

        if (args.length == 0) {
            SpawnGui.open(player, spawnService);
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!player.hasPermission("mineaclespawn.admin")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            spawnService.load();
            send(player, "§aSpawn menu reloaded");
            return true;
        }

        if (sub.equals("list")) {
            if (!player.hasPermission("mineaclespawn.admin")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            list(player);
            return true;
        }

        if (sub.equals("enable")) {
            if (!player.hasPermission("mineaclespawn.admin")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            if (args.length < 2) {
                send(player, "§cUsage: /spawn enable <id>");
                return true;
            }

            if (!spawnService.setSpawnPointEnabled(args[1], true)) {
                send(player, "§cThat spawn does not exist");
                return true;
            }

            send(player, "§aSpawn enabled");
            return true;
        }

        if (sub.equals("disable")) {
            if (!player.hasPermission("mineaclespawn.admin")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            if (args.length < 2) {
                send(player, "§cUsage: /spawn disable <id>");
                return true;
            }

            if (!spawnService.setSpawnPointEnabled(args[1], false)) {
                send(player, "§cThat spawn does not exist");
                return true;
            }

            send(player, "§cSpawn disabled");
            return true;
        }

        if (sub.equals("remove")) {
            if (!player.hasPermission("mineaclespawn.admin")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            if (args.length < 2) {
                send(player, "§cUsage: /spawn remove <id>");
                return true;
            }

            if (!spawnService.removeSpawnPoint(args[1])) {
                send(player, "§cThat spawn does not exist");
                return true;
            }

            send(player, "§cSpawn removed");
            return true;
        }

        if (sub.equals("add")) {
            if (!player.hasPermission("mineaclespawn.admin")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            if (args.length < 5) {
                send(player, "§cUsage: /spawn add <id> <world> <slot> <display name>");
                return true;
            }

            String id = args[1];
            String worldName = args[2];

            World world = Bukkit.getWorld(worldName);

            if (world == null) {
                send(player, "§cThat world is not loaded");
                return true;
            }

            Integer slot = parseSlot(args[3]);

            if (slot == null) {
                send(player, "§cSlot must be a number");
                return true;
            }

            String displayName = joinArgs(args, 4);

            if (!spawnService.addSpawnPoint(id, worldName, slot, displayName)) {
                send(player, "§cCould not add spawn");
                return true;
            }

            send(player, "§aSpawn added");
            return true;
        }

        if (sub.equals("addhere")) {
            if (!player.hasPermission("mineaclespawn.admin")) {
                send(player, "§cYou do not have permission");
                return true;
            }

            if (args.length < 4) {
                send(player, "§cUsage: /spawn addhere <id> <slot> <display name>");
                return true;
            }

            String id = args[1];
            String worldName = player.getWorld().getName();

            Integer slot = parseSlot(args[2]);

            if (slot == null) {
                send(player, "§cSlot must be a number");
                return true;
            }

            String displayName = joinArgs(args, 3);

            if (!spawnService.addSpawnPoint(id, worldName, slot, displayName)) {
                send(player, "§cCould not add spawn");
                return true;
            }

            send(player, "§aSpawn added");
            return true;
        }

        SpawnGui.open(player, spawnService);
        return true;
    }

    private void list(Player player) {
        if (spawnService.spawnPoints().isEmpty()) {
            send(player, "§cNo spawns configured");
            return;
        }

        player.sendMessage(TextColor.color("&dSpawn worlds"));

        for (SpawnPoint point : spawnService.spawnPoints()) {
            String status = point.enabled() ? "&aEnabled" : "&cDisabled";
            String loaded = Bukkit.getWorld(point.worldName()) == null ? "&cNot loaded" : "&aLoaded";

            player.sendMessage(TextColor.color(
                    "&7- &d" + point.id()
                            + " &8| &7world: &d" + point.worldName()
                            + " &8| &7slot: &d" + point.slot()
                            + " &8| " + status
                            + " &8| " + loaded
            ));
        }
    }

    private Integer parseSlot(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder builder = new StringBuilder();

        for (int i = start; i < args.length; i++) {
            if (!builder.isEmpty()) {
                builder.append(" ");
            }

            builder.append(args[i]);
        }

        return builder.toString();
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> completions = new ArrayList<>();

        if (!(sender instanceof Player player)) {
            return completions;
        }

        if (!player.hasPermission("mineaclespawn.admin")) {
            return completions;
        }

        if (args.length == 1) {
            List<String> options = List.of("reload", "list", "enable", "disable", "remove", "add", "addhere");
            String partial = args[0].toLowerCase(Locale.ROOT);

            for (String option : options) {
                if (option.startsWith(partial)) {
                    completions.add(option);
                }
            }

            return completions;
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("enable")
                || args[0].equalsIgnoreCase("disable")
                || args[0].equalsIgnoreCase("remove"))) {
            String partial = args[1].toLowerCase(Locale.ROOT);

            for (String id : spawnService.spawnIds()) {
                if (id.toLowerCase(Locale.ROOT).startsWith(partial)) {
                    completions.add(id);
                }
            }

            return completions;
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
            String partial = args[2].toLowerCase(Locale.ROOT);

            for (World world : Bukkit.getWorlds()) {
                if (world.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    completions.add(world.getName());
                }
            }
        }

        return completions;
    }
}