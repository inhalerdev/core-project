package net.mineacle.core.security.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class SecurityService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    private final Map<String, CommandGroup> groups = new LinkedHashMap<>();

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
        loadGroups();
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public boolean bypass(CommandSender sender) {
        return sender.hasPermission(config.getString("bypass-permission", "mineaclesecurity.bypass"));
    }

    public boolean shouldBlock(Player player, String rawCommandMessage) {
        if (!enabled() || bypass(player)) {
            return false;
        }

        String command = firstCommand(rawCommandMessage);
        if (command.isBlank()) {
            return false;
        }

        if (isBlocked(command)) {
            return true;
        }

        if (consoleOnlyCommands().contains(command)) {
            return true;
        }

        return blockUnlistedPlayerCommands() && !visibleCommands(player).contains(command);
    }

    public boolean shouldHideFromTab(Player player, String rawCommand) {
        if (!enabled() || bypass(player)) {
            return false;
        }

        String command = normalize(rawCommand);
        if (command.isBlank()) {
            return false;
        }

        if (isBlocked(command)) {
            return true;
        }

        if (consoleOnlyCommands().contains(command)) {
            return true;
        }

        return blockUnlistedPlayerCommands() && !visibleCommands(player).contains(command);
    }

    public List<String> filterTabCompletions(Player player, String buffer, List<String> completions) {
        if (!enabled() || bypass(player) || completions == null || completions.isEmpty()) {
            return completions;
        }

        String normalizedBuffer = buffer == null ? "" : buffer.trim();
        if (!normalizedBuffer.startsWith("/")) {
            return completions;
        }

        String withoutSlash = normalizedBuffer.substring(1);
        if (withoutSlash.contains(" ")) {
            return completions;
        }

        List<String> filtered = new ArrayList<>();
        for (String completion : completions) {
            if (!shouldHideFromTab(player, completion)) {
                filtered.add(completion);
            }
        }
        return filtered;
    }

    public String unknownMessage() {
        return TextColor.color(config.getString("unknown-command-message", "&cThis command does not exist"));
    }

    public String reloadMessage() {
        return TextColor.color(config.getString("reload-message", "&#bbbbbbSecurity reloaded"));
    }

    public Set<String> visibleCommands(Player player) {
        Set<String> commands = new LinkedHashSet<>();

        for (CommandGroup group : activeGroups(player)) {
            addGroupCommands(group.id(), commands, new HashSet<>());
        }

        return commands;
    }

    public List<String> activeGroupNames(Player player) {
        List<String> names = new ArrayList<>();
        for (CommandGroup group : activeGroups(player)) {
            names.add(group.id());
        }
        return names;
    }

    private List<CommandGroup> activeGroups(Player player) {
        List<CommandGroup> active = new ArrayList<>();

        for (CommandGroup group : groups.values()) {
            if (group.permission().isBlank() || player.hasPermission(group.permission())) {
                active.add(group);
            }
        }

        active.sort(Comparator.comparingInt(CommandGroup::priority));
        return active;
    }

    private void addGroupCommands(String groupId, Set<String> commands, Set<String> visited) {
        String normalizedGroup = normalizeGroup(groupId);
        if (normalizedGroup.isBlank() || visited.contains(normalizedGroup)) {
            return;
        }

        visited.add(normalizedGroup);
        CommandGroup group = groups.get(normalizedGroup);
        if (group == null) {
            return;
        }

        for (String inherited : group.inherits()) {
            addGroupCommands(inherited, commands, visited);
        }

        commands.addAll(group.commands());
    }

    private void loadGroups() {
        groups.clear();

        ConfigurationSection section = config.getConfigurationSection("groups");
        if (section == null) {
            loadLegacyGroups();
            return;
        }

        for (String id : section.getKeys(false)) {
            String path = "groups." + id;
            String normalizedId = normalizeGroup(id);
            if (normalizedId.isBlank()) {
                continue;
            }

            String permission = config.getString(path + ".permission", "");
            int priority = config.getInt(path + ".priority", 0);
            Set<String> inherits = normalizeGroups(config.getStringList(path + ".inherits"));
            Set<String> commands = normalizeCommands(config.getStringList(path + ".commands"));

            groups.put(normalizedId, new CommandGroup(normalizedId, permission == null ? "" : permission.trim(), priority, inherits, commands));
        }

        if (!groups.containsKey("default")) {
            groups.put("default", new CommandGroup("default", "", 0, Set.of(), Set.of()));
        }
    }

    private void loadLegacyGroups() {
        groups.put("default", new CommandGroup("default", "", 0, Set.of(), normalizeCommands(config.getStringList("visible-commands.default"))));
        groups.put("plus", new CommandGroup("plus", config.getString("plus-group-permission", "mineacle.plus"), 10, Set.of("default"), normalizeCommands(config.getStringList("visible-commands.plus"))));
        groups.put("admin", new CommandGroup("admin", config.getString("admin-group-permission", "mineaclesecurity.group.admin"), 100, Set.of("plus"), normalizeCommands(config.getStringList("visible-commands.admin"))));
    }

    private boolean isBlocked(String command) {
        if (blockNamespacedCommands() && command.contains(":")) {
            for (String prefix : blockedPrefixes()) {
                if (command.startsWith(prefix)) {
                    return true;
                }
            }
        }

        return blockedCommands().contains(command);
    }

    private boolean blockUnlistedPlayerCommands() {
        return config.getBoolean("block-unlisted-player-commands", true);
    }

    private boolean blockNamespacedCommands() {
        return config.getBoolean("block-namespaced-commands", true);
    }

    private Set<String> blockedPrefixes() {
        return normalizePrefixes(config.getStringList("blocked-prefixes"));
    }

    private Set<String> blockedCommands() {
        return normalizeCommands(config.getStringList("blocked-commands"));
    }

    private Set<String> consoleOnlyCommands() {
        return normalizeCommands(config.getStringList("console-only-commands"));
    }

    private Set<String> normalizeCommands(List<String> rawValues) {
        Set<String> values = new LinkedHashSet<>();
        for (String raw : rawValues) {
            String normalized = normalize(raw);
            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private Set<String> normalizePrefixes(List<String> rawValues) {
        Set<String> values = new LinkedHashSet<>();
        for (String raw : rawValues) {
            String normalized = normalize(raw);
            if (!normalized.isBlank()) {
                values.add(normalized.endsWith(":") ? normalized : normalized + ":");
            }
        }
        return values;
    }

    private Set<String> normalizeGroups(List<String> rawValues) {
        Set<String> values = new LinkedHashSet<>();
        for (String raw : rawValues) {
            String normalized = normalizeGroup(raw);
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

    private String normalizeGroup(String raw) {
        if (raw == null) {
            return "";
        }

        return raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-');
    }

    private record CommandGroup(String id, String permission, int priority, Set<String> inherits, Set<String> commands) {
    }
}
