package net.mineacle.core.security.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class SecurityService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    public SecurityService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "security.yml");
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            core.saveResource("security.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean bypass(CommandSender sender) {
        return sender.hasPermission(config.getString("bypass-permission", "mineaclesecurity.bypass"));
    }

    public boolean admin(Player player) {
        return player.hasPermission(config.getString("admin-group-permission", "mineaclesecurity.group.admin"));
    }

    public boolean plus(Player player) {
        return player.hasPermission(config.getString("plus-group-permission", "mineacle.plus"));
    }

    public boolean shouldBlock(Player player, String rawCommandMessage) {
        if (!enabled() || bypass(player)) {
            return false;
        }

        String command = firstCommand(rawCommandMessage);

        if (command.isBlank()) {
            return false;
        }

        if (blocked(command)) {
            return true;
        }

        if (consoleOnly().contains(command)) {
            return true;
        }

        return config.getBoolean("block-unlisted-player-commands", true) && !visibleCommands(player).contains(command);
    }

    public boolean shouldHideFromTab(Player player, String command) {
        if (!enabled() || bypass(player)) {
            return false;
        }

        command = normalize(command);

        if (command.isBlank()) {
            return false;
        }

        if (blocked(command)) {
            return true;
        }

        if (consoleOnly().contains(command)) {
            return true;
        }

        return config.getBoolean("block-unlisted-player-commands", true) && !visibleCommands(player).contains(command);
    }

    public String unknownMessage() {
        return TextColor.color(config.getString("unknown-command-message", "&cThis command does not exist"));
    }

    public String reloadMessage() {
        return TextColor.color(config.getString("reload-message", "&#bbbbbbSecurity reloaded"));
    }

    public Set<String> visibleCommands(Player player) {
        Set<String> commands = new LinkedHashSet<>();
        commands.addAll(list("visible-commands.default"));

        if (plus(player)) {
            commands.addAll(list("visible-commands.plus"));
        }

        if (admin(player)) {
            commands.addAll(list("visible-commands.admin"));
        }

        return commands;
    }

    private boolean blocked(String command) {
        if (config.getBoolean("block-namespaced-commands", true) && command.contains(":")) {
            for (String prefix : list("blocked-prefixes")) {
                if (command.startsWith(prefix)) {
                    return true;
                }
            }
        }

        return list("blocked-commands").contains(command);
    }

    private Set<String> consoleOnly() {
        return list("console-only-commands");
    }

    private Set<String> list(String path) {
        Set<String> values = new LinkedHashSet<>();

        for (String raw : config.getStringList(path)) {
            String normalized = normalize(raw);

            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }

        return values;
    }

    private String firstCommand(String raw) {
        if (raw == null) {
            return "";
        }

        String trimmed = raw.trim();

        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }

        int space = trimmed.indexOf(' ');

        if (space >= 0) {
            trimmed = trimmed.substring(0, space);
        }

        return normalize(trimmed);
    }

    private String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return normalized;
    }
}
