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

        ParsedCommand parsed = parse(rawCommandMessage);

        if (parsed.command().isBlank()) {
            return false;
        }

        if (isBlockedCommand(parsed.command()) || isConsoleOnly(parsed.command())) {
            return true;
        }

        if (!parsed.subCommand().isBlank() && hasSubcommandRules(parsed.command())) {
            return !allowedSubCommands(player, parsed.command()).contains(parsed.subCommand());
        }

        return false;
    }

    public boolean shouldHideFromRootTab(Player player, String rawCommand) {
        return shouldHideFromTab(player, rawCommand);
    }

    public boolean shouldHideFromTab(Player player, String rawCommand) {
        if (!enabled() || bypass(player)) {
            return false;
        }

        String command = normalize(rawCommand);

        if (command.isBlank()) {
            return false;
        }

        if (isBlockedCommand(command) || isConsoleOnly(command)) {
            return true;
        }

        return !visibleCommands(player).contains(command);
    }

    public List<String> filterTabCompletions(Player player, String buffer, List<String> completions) {
        if (!enabled() || bypass(player) || completions == null || completions.isEmpty()) {
            return completions == null ? List.of() : completions;
        }

        String raw = buffer == null ? "" : buffer;

        /*
         * Important:
         * Do NOT filter follow-up suggestions here.
         *
         * Minecraft shows the automatic suggestion bar for subcommands from each
         * command's TabCompleter. Filtering here after a space can stop the client from
         * showing the full suggestion list until the player types another character.
         *
         * Security still blocks forbidden subcommands on execution in shouldBlock().
         * Individual command TabCompleters should hide admin options visually.
         */
        if (raw.contains(" ")) {
            return completions;
        }

        String trimmed = raw.trim();

        if (trimmed.isBlank() || !trimmed.startsWith("/")) {
            return completions;
        }

        List<String> filtered = new ArrayList<>();

        for (String completion : completions) {
            String command = normalize(completion);

            if (!shouldHideFromTab(player, command)) {
                filtered.add(completion);
            }
        }

        return filtered;
    }

    public Set<String> visibleCommands(Player player) {
        Set<String> commands = new LinkedHashSet<>();

        for (CommandGroup group : activeGroups(player)) {
            commands.addAll(resolveVisibleCommands(group.name(), new HashSet<>()));
        }

        return commands;
    }

    public Set<String> allowedCommands(Player player) {
        Set<String> commands = new LinkedHashSet<>();

        for (CommandGroup group : activeGroups(player)) {
            commands.addAll(resolveVisibleCommands(group.name(), new HashSet<>()));
            commands.addAll(resolveHiddenCommands(group.name(), new HashSet<>()));
            commands.addAll(resolveAllowedCommands(group.name(), new HashSet<>()));
        }

        return commands;
    }

    public Set<String> allowedSubCommands(Player player, String command) {
        String normalizedCommand = normalize(command);
        Set<String> subcommands = new LinkedHashSet<>();

        for (CommandGroup group : activeGroups(player)) {
            subcommands.addAll(resolveSubCommands(group.name(), normalizedCommand, new HashSet<>()));
        }

        return subcommands;
    }

    public List<String> activeGroupNames(Player player) {
        List<String> names = new ArrayList<>();

        for (CommandGroup group : activeGroups(player)) {
            names.add(group.name());
        }

        return names;
    }

    public List<String> groupSummary(Player player) {
        return activeGroupNames(player);
    }

    public String unknownMessage() {
        return color(config.getString("unknown-command-message", "&cThis command does not exist"));
    }

    public String reloadMessage() {
        return color(config.getString("reload-message", "&#bbbbbbSecurity reloaded"));
    }

    public String usageMessage() {
        return color(config.getString("usage-message", "&#bbbbbbUsage: &d/mineaclesecurity reload"));
    }

    public String groupsMessage(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return color("&#bbbbbbConsole has security bypass");
        }

        String groupsText = String.join("&#bbbbbb, &d", activeGroupNames(player));

        return color(config.getString("groups-message", "&#bbbbbbActive command groups: &d%groups%")
                .replace("%groups%", groupsText));
    }

    public List<String> commandTabs(CommandSender sender, String input) {
        if (!bypass(sender)) {
            return List.of();
        }

        String lowered = input == null ? "" : input.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();

        for (String option : List.of("reload", "groups")) {
            if (option.startsWith(lowered)) {
                matches.add(option);
            }
        }

        return matches;
    }

    private boolean hasSubcommandRules(String command) {
        return config.isConfigurationSection("groups.default.subcommands." + normalize(command))
                || config.isConfigurationSection("groups.plus.subcommands." + normalize(command))
                || config.isConfigurationSection("groups.admin.subcommands." + normalize(command));
    }

    private void loadGroups() {
        groups.clear();
        ConfigurationSection section = config.getConfigurationSection("groups");

        if (section == null) {
            loadLegacyGroups();
            return;
        }

        for (String key : section.getKeys(false)) {
            String path = "groups." + key;
            String groupName = normalizeGroup(key);
            Set<String> visible = new LinkedHashSet<>();
            visible.addAll(normalizeList(config.getStringList(path + ".visible-commands")));
            visible.addAll(normalizeList(config.getStringList(path + ".commands")));

            Map<String, Set<String>> subcommands = new LinkedHashMap<>();
            ConfigurationSection subSection = config.getConfigurationSection(path + ".subcommands");

            if (subSection != null) {
                for (String command : subSection.getKeys(false)) {
                    subcommands.put(normalize(command), normalizeList(config.getStringList(path + ".subcommands." + command)));
                }
            }

            groups.put(groupName, new CommandGroup(
                    groupName,
                    config.getString(path + ".permission", ""),
                    config.getInt(path + ".priority", 0),
                    normalizeList(config.getStringList(path + ".inherits")),
                    visible,
                    normalizeList(config.getStringList(path + ".hidden-commands")),
                    normalizeList(config.getStringList(path + ".allowed-commands")),
                    subcommands
            ));
        }
    }

    private void loadLegacyGroups() {
        groups.put("default", new CommandGroup(
                "default",
                "",
                0,
                Set.of(),
                normalizeList(config.getStringList("visible-commands.default")),
                Set.of(),
                Set.of(),
                Map.of()
        ));

        groups.put("plus", new CommandGroup(
                "plus",
                config.getString("plus-group-permission", "mineacle.plus"),
                10,
                Set.of("default"),
                normalizeList(config.getStringList("visible-commands.plus")),
                Set.of(),
                Set.of(),
                Map.of()
        ));

        groups.put("admin", new CommandGroup(
                "admin",
                config.getString("admin-group-permission", "mineaclesecurity.group.admin"),
                100,
                Set.of("plus"),
                normalizeList(config.getStringList("visible-commands.admin")),
                Set.of(),
                Set.of(),
                Map.of()
        ));
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

    private Set<String> resolveVisibleCommands(String groupName, Set<String> seen) {
        CommandGroup group = groups.get(normalizeGroup(groupName));

        if (group == null || !seen.add(group.name())) {
            return Set.of();
        }

        Set<String> commands = new LinkedHashSet<>();

        for (String parent : group.inherits()) {
            commands.addAll(resolveVisibleCommands(parent, seen));
        }

        commands.addAll(group.visibleCommands());
        return commands;
    }

    private Set<String> resolveHiddenCommands(String groupName, Set<String> seen) {
        CommandGroup group = groups.get(normalizeGroup(groupName));

        if (group == null || !seen.add(group.name())) {
            return Set.of();
        }

        Set<String> commands = new LinkedHashSet<>();

        for (String parent : group.inherits()) {
            commands.addAll(resolveHiddenCommands(parent, seen));
        }

        commands.addAll(group.hiddenCommands());
        return commands;
    }

    private Set<String> resolveAllowedCommands(String groupName, Set<String> seen) {
        CommandGroup group = groups.get(normalizeGroup(groupName));

        if (group == null || !seen.add(group.name())) {
            return Set.of();
        }

        Set<String> commands = new LinkedHashSet<>();

        for (String parent : group.inherits()) {
            commands.addAll(resolveAllowedCommands(parent, seen));
        }

        commands.addAll(group.allowedCommands());
        return commands;
    }

    private Set<String> resolveSubCommands(String groupName, String command, Set<String> seen) {
        CommandGroup group = groups.get(normalizeGroup(groupName));

        if (group == null || !seen.add(group.name() + ":" + command)) {
            return Set.of();
        }

        Set<String> subcommands = new LinkedHashSet<>();

        for (String parent : group.inherits()) {
            subcommands.addAll(resolveSubCommands(parent, command, seen));
        }

        subcommands.addAll(group.subcommands().getOrDefault(normalize(command), Set.of()));
        return subcommands;
    }

    private boolean isBlockedCommand(String command) {
        if (config.getBoolean("block-namespaced-commands", true) && command.contains(":")) {
            for (String prefix : normalizeList(config.getStringList("blocked-prefixes"))) {
                if (command.startsWith(prefix)) {
                    return true;
                }
            }
        }

        return normalizeList(config.getStringList("blocked-commands")).contains(command);
    }

    private boolean isConsoleOnly(String command) {
        return normalizeList(config.getStringList("console-only-commands")).contains(command);
    }

    private Set<String> normalizeList(List<String> rawValues) {
        Set<String> values = new LinkedHashSet<>();

        for (String raw : rawValues) {
            String normalized = normalize(raw);

            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }

        return values;
    }

    private ParsedCommand parse(String raw) {
        if (raw == null) {
            return new ParsedCommand("", "");
        }

        String trimmed = raw.trim();

        while (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }

        if (trimmed.isBlank()) {
            return new ParsedCommand("", "");
        }

        String[] parts = trimmed.split("\\s+", 3);
        String command = parts.length >= 1 ? normalize(parts[0]) : "";
        String sub = parts.length >= 2 ? normalize(parts[1]) : "";
        return new ParsedCommand(command, sub);
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
        return normalize(raw).replace(" ", "-");
    }

    private String color(String message) {
        return TextColor.color(message);
    }

    private record CommandGroup(
            String name,
            String permission,
            int priority,
            Set<String> inherits,
            Set<String> visibleCommands,
            Set<String> hiddenCommands,
            Set<String> allowedCommands,
            Map<String, Set<String>> subcommands
    ) {
    }

    private record ParsedCommand(String command, String subCommand) {
    }
}
