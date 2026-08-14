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

    private static final String DEFAULT_MANAGE_PERMISSION =
            "mineaclesecurity.admin";
    private static final String DEFAULT_BYPASS_PERMISSION =
            "mineaclesecurity.bypass";
    private static final String DEFAULT_PERMISSION_AWARE_ROOT_PERMISSION =
            "mineaclesecurity.permission-aware-roots";

    private final Core core;
    private final File file;
    private final Map<String, CommandGroup> groups =
            new LinkedHashMap<>();

    private FileConfiguration config;

    public SecurityService(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "security.yml"
        );
        reload();
    }

    public void reload() {
        File dataFolder = core.getDataFolder();

        if (!dataFolder.exists()
                && !dataFolder.mkdirs()
                && !dataFolder.isDirectory()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data directory: "
                            + dataFolder.getAbsolutePath()
            );
        }

        if (!file.exists()) {
            core.saveResource(
                    "security.yml",
                    false
            );
        }

        config = YamlConfiguration.loadConfiguration(file);
        loadGroups();
    }

    public boolean enabled() {
        return config.getBoolean(
                "enabled",
                true
        );
    }

    public boolean canManage(CommandSender sender) {
        return hasPermission(
                sender,
                config.getString(
                        "manage-permission",
                        DEFAULT_MANAGE_PERMISSION
                )
        );
    }

    public boolean bypass(CommandSender sender) {
        return hasPermission(
                sender,
                config.getString(
                        "bypass-permission",
                        DEFAULT_BYPASS_PERMISSION
                )
        );
    }

    public boolean permissionAwareRoots(Player player) {
        return hasPermission(
                player,
                config.getString(
                        "permission-aware-root-permission",
                        DEFAULT_PERMISSION_AWARE_ROOT_PERMISSION
                )
        );
    }

    public boolean shouldBlock(
            Player player,
            String rawCommandMessage
    ) {
        if (!enabled()) {
            return false;
        }

        ParsedCommand parsed =
                parse(rawCommandMessage);

        if (parsed.command().isBlank()) {
            return false;
        }

        /*
         * These protections are never bypassed by a player-side capability.
         * Namespaced commands can otherwise route around the normal root
         * command path, while console-only commands stay console-only for
         * every in-game group.
         */
        if (isNamespacedCommand(parsed.command())
                || isConsoleOnly(parsed.command())) {
            return true;
        }

        if (bypass(player)) {
            return false;
        }

        if (isBlockedCommand(parsed.command())) {
            return true;
        }

        if (!parsed.subCommand().isBlank()
                && hasSubcommandRules(
                parsed.command()
        )) {
            Set<String> allowed =
                    allowedSubCommands(
                            player,
                            parsed.command()
                    );

            if (!allowed.isEmpty()) {
                return !allowed.contains(
                        parsed.subCommand()
                );
            }
        }

        return false;
    }

    public boolean shouldHideFromTab(
            Player player,
            String rawCommand
    ) {
        if (!enabled()) {
            return false;
        }

        String command =
                normalize(rawCommand);

        if (command.isBlank()) {
            return false;
        }

        if (isNamespacedCommand(command)
                || isConsoleOnly(command)) {
            return true;
        }

        if (bypass(player)) {
            return false;
        }

        if (isBlockedCommand(command)
                || hiddenCommands(player)
                .contains(command)) {
            return true;
        }

        /*
         * LuckPerms and the command-owning plugin are the authority for staff
         * and build-tool roots. PlayerCommandSendEvent only gives Security the
         * roots Paper is preparing to send, so Security does not duplicate
         * every WorldEdit, WorldGuard, LiteBans, Multiverse, TAB, etc. root.
         */
        if (permissionAwareRoots(player)) {
            return false;
        }

        return !visibleCommands(player)
                .contains(command);
    }

    public List<String> filterTabCompletions(
            Player player,
            String buffer,
            List<String> completions
    ) {
        if (!enabled()
                || completions == null
                || completions.isEmpty()) {
            return completions == null
                    ? List.of()
                    : completions;
        }

        String raw =
                buffer == null
                        ? ""
                        : buffer;

        /*
         * Security filters root command visibility only.
         *
         * Never touch completions after a space. The command that owns the
         * root remains fully responsible for argument/subcommand completion.
         * This is the mandatory /warp <space> regression guard.
         */
        if (raw.contains(" ")) {
            return completions;
        }

        String trimmed = raw.trim();

        if (trimmed.isBlank()
                || !trimmed.startsWith("/")) {
            return completions;
        }

        List<String> filtered =
                new ArrayList<>();

        for (String completion : completions) {
            if (!shouldHideFromTab(
                    player,
                    completion
            )) {
                filtered.add(completion);
            }
        }

        return List.copyOf(filtered);
    }

    public Set<String> visibleCommands(
            Player player
    ) {
        Set<String> commands =
                new LinkedHashSet<>();

        for (CommandGroup group
                : activeGroups(player)) {
            commands.addAll(
                    resolveVisibleCommands(
                            group.name(),
                            new HashSet<>()
                    )
            );
        }

        return Set.copyOf(commands);
    }

    public Set<String> hiddenCommands(
            Player player
    ) {
        Set<String> commands =
                new LinkedHashSet<>();

        for (CommandGroup group
                : activeGroups(player)) {
            commands.addAll(
                    resolveHiddenCommands(
                            group.name(),
                            new HashSet<>()
                    )
            );
        }

        return Set.copyOf(commands);
    }

    public Set<String> allowedSubCommands(
            Player player,
            String command
    ) {
        String normalizedCommand =
                normalize(command);
        Set<String> subcommands =
                new LinkedHashSet<>();

        for (CommandGroup group
                : activeGroups(player)) {
            subcommands.addAll(
                    resolveSubCommands(
                            group.name(),
                            normalizedCommand,
                            new HashSet<>()
                    )
            );
        }

        return Set.copyOf(subcommands);
    }

    public List<String> activeGroupNames(
            Player player
    ) {
        List<String> names =
                new ArrayList<>();

        for (CommandGroup group
                : activeGroups(player)) {
            names.add(group.name());
        }

        return List.copyOf(names);
    }

    public String unknownMessage() {
        return color(
                config.getString(
                        "unknown-command-message",
                        "&cThis command does not exist"
                )
        );
    }

    public String reloadMessage() {
        return color(
                config.getString(
                        "reload-message",
                        "&#bbbbbbSecurity reloaded"
                )
        );
    }

    public String usageMessage() {
        return color(
                config.getString(
                        "usage-message",
                        "&#bbbbbbUsage: "
                                + "&#8436FE/mineaclesecurity reload"
                )
        );
    }

    public String groupsMessage(
            CommandSender sender
    ) {
        if (!(sender instanceof Player player)) {
            return color(
                    "&#bbbbbbConsole uses command security directly"
            );
        }

        String groupsText =
                String.join(
                        "&#bbbbbb, &#B078FF",
                        activeGroupNames(player)
                );

        return color(
                config.getString(
                                "groups-message",
                                "&#bbbbbbActive command groups: "
                                        + "&#B078FF%groups%"
                        )
                        .replace(
                                "%groups%",
                                groupsText
                        )
        );
    }

    public List<String> commandTabs(
            CommandSender sender,
            String input
    ) {
        if (!canManage(sender)) {
            return List.of();
        }

        String lowered =
                input == null
                        ? ""
                        : input.toLowerCase(
                                Locale.ROOT
                        );
        List<String> matches =
                new ArrayList<>();

        for (String option
                : List.of(
                "reload",
                "groups"
        )) {
            if (option.startsWith(lowered)) {
                matches.add(option);
            }
        }

        return List.copyOf(matches);
    }

    private boolean hasSubcommandRules(
            String command
    ) {
        String normalized =
                normalize(command);

        for (CommandGroup group
                : groups.values()) {
            if (group.subcommands()
                    .containsKey(normalized)) {
                return true;
            }
        }

        return false;
    }

    private void loadGroups() {
        groups.clear();

        ConfigurationSection section =
                config.getConfigurationSection(
                        "groups"
                );

        if (section == null) {
            loadLegacyGroups();
            return;
        }

        for (String key
                : section.getKeys(false)) {
            String path =
                    "groups." + key;
            String groupName =
                    normalizeGroup(key);

            Set<String> visible =
                    new LinkedHashSet<>();
            visible.addAll(
                    normalizeList(
                            config.getStringList(
                                    path
                                            + ".visible-commands"
                            )
                    )
            );
            visible.addAll(
                    normalizeList(
                            config.getStringList(
                                    path + ".commands"
                            )
                    )
            );

            Map<String, Set<String>> subcommands =
                    new LinkedHashMap<>();
            ConfigurationSection subSection =
                    config.getConfigurationSection(
                            path + ".subcommands"
                    );

            if (subSection != null) {
                for (String command
                        : subSection.getKeys(false)) {
                    subcommands.put(
                            normalize(command),
                            normalizeList(
                                    config.getStringList(
                                            path
                                                    + ".subcommands."
                                                    + command
                                    )
                            )
                    );
                }
            }

            groups.put(
                    groupName,
                    new CommandGroup(
                            groupName,
                            config.getString(
                                    path + ".permission",
                                    ""
                            ),
                            config.getInt(
                                    path + ".priority",
                                    0
                            ),
                            normalizeList(
                                    config.getStringList(
                                            path + ".inherits"
                                    )
                            ),
                            Set.copyOf(visible),
                            normalizeList(
                                    config.getStringList(
                                            path
                                                    + ".hidden-commands"
                                    )
                            ),
                            immutableSubcommands(
                                    subcommands
                            )
                    )
            );
        }
    }

    /**
     * Compatibility only for installations that still use the original flat
     * security.yml format. The current bundled configuration uses capability
     * groups and does not mirror the LuckPerms rank tree.
     */
    private void loadLegacyGroups() {
        groups.put(
                "default",
                new CommandGroup(
                        "default",
                        "",
                        0,
                        Set.of(),
                        normalizeList(
                                config.getStringList(
                                        "visible-commands.default"
                                )
                        ),
                        Set.of(),
                        Map.of()
                )
        );

        groups.put(
                "plus",
                new CommandGroup(
                        "plus",
                        config.getString(
                                "plus-group-permission",
                                "mineacle.plus"
                        ),
                        10,
                        Set.of("default"),
                        normalizeList(
                                config.getStringList(
                                        "visible-commands.plus"
                                )
                        ),
                        Set.of(),
                        Map.of()
                )
        );

        groups.put(
                "legacy-admin",
                new CommandGroup(
                        "legacy-admin",
                        config.getString(
                                "admin-group-permission",
                                DEFAULT_PERMISSION_AWARE_ROOT_PERMISSION
                        ),
                        100,
                        Set.of("default"),
                        normalizeList(
                                config.getStringList(
                                        "visible-commands.admin"
                                )
                        ),
                        Set.of(),
                        Map.of()
                )
        );
    }

    private List<CommandGroup> activeGroups(
            Player player
    ) {
        List<CommandGroup> active =
                new ArrayList<>();

        for (CommandGroup group
                : groups.values()) {
            if (group.permission().isBlank()
                    || player.hasPermission(
                    group.permission()
            )) {
                active.add(group);
            }
        }

        active.sort(
                Comparator.comparingInt(
                        CommandGroup::priority
                )
        );
        return List.copyOf(active);
    }

    private Set<String> resolveVisibleCommands(
            String groupName,
            Set<String> seen
    ) {
        CommandGroup group =
                groups.get(
                        normalizeGroup(groupName)
                );

        if (group == null
                || !seen.add(group.name())) {
            return Set.of();
        }

        Set<String> commands =
                new LinkedHashSet<>();

        for (String parent
                : group.inherits()) {
            commands.addAll(
                    resolveVisibleCommands(
                            parent,
                            seen
                    )
            );
        }

        commands.addAll(
                group.visibleCommands()
        );
        return commands;
    }

    private Set<String> resolveHiddenCommands(
            String groupName,
            Set<String> seen
    ) {
        CommandGroup group =
                groups.get(
                        normalizeGroup(groupName)
                );

        if (group == null
                || !seen.add(group.name())) {
            return Set.of();
        }

        Set<String> commands =
                new LinkedHashSet<>();

        for (String parent
                : group.inherits()) {
            commands.addAll(
                    resolveHiddenCommands(
                            parent,
                            seen
                    )
            );
        }

        commands.addAll(
                group.hiddenCommands()
        );
        return commands;
    }

    private Set<String> resolveSubCommands(
            String groupName,
            String command,
            Set<String> seen
    ) {
        CommandGroup group =
                groups.get(
                        normalizeGroup(groupName)
                );

        String seenKey =
                group == null
                        ? ""
                        : group.name()
                        + ":"
                        + command;

        if (group == null
                || !seen.add(seenKey)) {
            return Set.of();
        }

        Set<String> subcommands =
                new LinkedHashSet<>();

        for (String parent
                : group.inherits()) {
            subcommands.addAll(
                    resolveSubCommands(
                            parent,
                            command,
                            seen
                    )
            );
        }

        subcommands.addAll(
                group.subcommands()
                        .getOrDefault(
                                normalize(command),
                                Set.of()
                        )
        );
        return subcommands;
    }

    private boolean isNamespacedCommand(
            String command
    ) {
        return config.getBoolean(
                "block-namespaced-commands",
                true
        ) && command.contains(":");
    }

    private boolean isBlockedCommand(
            String command
    ) {
        return normalizeList(
                config.getStringList(
                        "blocked-commands"
                )
        ).contains(command);
    }

    private boolean isConsoleOnly(
            String command
    ) {
        return normalizeList(
                config.getStringList(
                        "console-only-commands"
                )
        ).contains(command);
    }

    private boolean hasPermission(
            CommandSender sender,
            String permission
    ) {
        return sender != null
                && permission != null
                && !permission.isBlank()
                && sender.hasPermission(
                permission
        );
    }

    private Set<String> normalizeList(
            List<String> rawValues
    ) {
        if (rawValues == null
                || rawValues.isEmpty()) {
            return Set.of();
        }

        Set<String> values =
                new LinkedHashSet<>();

        for (String raw : rawValues) {
            String normalized =
                    normalize(raw);

            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }

        return Set.copyOf(values);
    }

    private Map<String, Set<String>>
    immutableSubcommands(
            Map<String, Set<String>> source
    ) {
        if (source.isEmpty()) {
            return Map.of();
        }

        Map<String, Set<String>> copy =
                new LinkedHashMap<>();

        source.forEach(
                (command, values) ->
                        copy.put(
                                command,
                                Set.copyOf(values)
                        )
        );

        return Map.copyOf(copy);
    }

    private ParsedCommand parse(
            String raw
    ) {
        if (raw == null) {
            return new ParsedCommand(
                    "",
                    ""
            );
        }

        String trimmed =
                raw.trim();

        while (trimmed.startsWith("/")) {
            trimmed =
                    trimmed.substring(1);
        }

        if (trimmed.isBlank()) {
            return new ParsedCommand(
                    "",
                    ""
            );
        }

        String[] parts =
                trimmed.split(
                        "\\s+",
                        3
                );

        return new ParsedCommand(
                parts.length >= 1
                        ? normalize(parts[0])
                        : "",
                parts.length >= 2
                        ? normalize(parts[1])
                        : ""
        );
    }

    private String normalize(
            String raw
    ) {
        if (raw == null) {
            return "";
        }

        String normalized =
                raw.trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        while (normalized.startsWith("/")) {
            normalized =
                    normalized.substring(1);
        }

        return normalized;
    }

    private String normalizeGroup(
            String raw
    ) {
        return normalize(raw)
                .replace(
                        " ",
                        "-"
                );
    }

    private String color(
            String message
    ) {
        return TextColor.color(message);
    }

    private record CommandGroup(
            String name,
            String permission,
            int priority,
            Set<String> inherits,
            Set<String> visibleCommands,
            Set<String> hiddenCommands,
            Map<String, Set<String>> subcommands
    ) {
    }

    private record ParsedCommand(
            String command,
            String subCommand
    ) {
    }
}
