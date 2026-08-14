package net.mineacle.core.security.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SecurityService {

    private static final String DEFAULT_MANAGE_PERMISSION =
            "mineaclesecurity.admin";
    private static final String DEFAULT_BYPASS_PERMISSION =
            "mineaclesecurity.bypass";
    private static final String DEFAULT_PERMISSION_AWARE_ROOT_PERMISSION =
            "mineaclesecurity.permission-aware-roots";

    private static final Set<String> FALLBACK_BLOCKED_COMMANDS = Set.of(
            "?",
            "about",
            "icanhasbukkit",
            "paper",
            "pl",
            "plugin",
            "pluginlist",
            "plugins",
            "ver",
            "version"
    );

    private static final Set<String> FALLBACK_CONSOLE_ONLY_COMMANDS = Set.of(
            "ban-ip",
            "deop",
            "op",
            "pardon-ip",
            "reload",
            "restart",
            "rl",
            "save-all",
            "save-off",
            "save-on",
            "stop",
            "whitelist"
    );

    private final Core core;
    private final File file;

    private final Object commandRefreshLock = new Object();
    private final ArrayDeque<UUID> commandRefreshQueue = new ArrayDeque<>();
    private final Set<UUID> queuedCommandRefreshes = new LinkedHashSet<>();

    private volatile SecuritySnapshot snapshot;
    private volatile boolean shuttingDown;
    private boolean commandRefreshDrainScheduled;

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

        FileConfiguration config =
                YamlConfiguration.loadConfiguration(file);
        snapshot = compile(config);
    }

    public void shutdown() {
        shuttingDown = true;

        synchronized (commandRefreshLock) {
            commandRefreshQueue.clear();
            queuedCommandRefreshes.clear();
            commandRefreshDrainScheduled = false;
        }
    }

    public boolean canManage(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return true;
        }

        return hasPermission(
                sender,
                snapshot.managePermission()
        );
    }

    public boolean shouldBlock(
            Player player,
            String rawCommandMessage
    ) {
        SecuritySnapshot current = snapshot;

        if (!current.enabled()) {
            return false;
        }

        ParsedCommand parsed = parse(rawCommandMessage);
        String command = parsed.command();

        if (command.isBlank()) {
            return false;
        }

        if (isNamespacedCommand(current, command)
                || current.consoleOnlyCommands().contains(command)) {
            return true;
        }

        if (hasPermission(
                player,
                current.bypassPermission()
        )) {
            return false;
        }

        if (current.blockedCommands().contains(command)) {
            return true;
        }

        if (!parsed.subCommand().isBlank()
                && current.subcommandRuleCommands().contains(command)) {
            return !allowedSubCommands(
                    player,
                    current,
                    command
            ).contains(parsed.subCommand());
        }

        return false;
    }

    public CommandView commandView(Player player) {
        SecuritySnapshot current = snapshot;

        if (!current.enabled()) {
            return CommandView.disabled();
        }

        boolean bypass = hasPermission(
                player,
                current.bypassPermission()
        );
        boolean permissionAware = hasPermission(
                player,
                current.permissionAwareRootPermission()
        );

        Set<String> visible = new LinkedHashSet<>();
        Set<String> hidden = new LinkedHashSet<>(
                current.alwaysHiddenCommands()
        );

        for (CommandGroup group : current.groups()) {
            if (!group.activeFor(player)) {
                continue;
            }

            if (!permissionAware && !bypass) {
                visible.addAll(group.visibleCommands());
            }

            hidden.addAll(group.hiddenCommands());
        }

        return new CommandView(
                true,
                current.blockNamespacedCommands(),
                bypass,
                permissionAware,
                current.blockedCommands(),
                current.consoleOnlyCommands(),
                Set.copyOf(visible),
                Set.copyOf(hidden)
        );
    }

    public List<String> filterTabCompletions(
            Player player,
            String buffer,
            List<String> completions
    ) {
        if (completions == null || completions.isEmpty()) {
            return completions == null
                    ? List.of()
                    : completions;
        }

        SecuritySnapshot current = snapshot;

        if (!current.enabled()) {
            return completions;
        }

        String raw = buffer == null
                ? ""
                : buffer;

        /*
         * Root visibility only.
         *
         * Never touch suggestions after the first space. This keeps argument
         * completion entirely command-owned and permanently protects the
         * /warp <space> regression case.
         */
        if (raw.contains(" ")) {
            return completions;
        }

        String trimmed = raw.trim();

        if (trimmed.isBlank() || !trimmed.startsWith("/")) {
            return completions;
        }

        CommandView view = commandView(player);
        List<String> filtered = new ArrayList<>(completions.size());

        for (String completion : completions) {
            if (!view.shouldHide(completion)) {
                filtered.add(completion);
            }
        }

        return List.copyOf(filtered);
    }

    public List<String> activeGroupNames(Player player) {
        SecuritySnapshot current = snapshot;
        List<String> names = new ArrayList<>();

        for (CommandGroup group : current.groups()) {
            if (group.activeFor(player)) {
                names.add(group.name());
            }
        }

        return List.copyOf(names);
    }

    public String unknownMessage() {
        return color(snapshot.unknownMessage());
    }

    public String reloadMessage() {
        return color(snapshot.reloadMessage());
    }

    public String usageMessage() {
        return color(snapshot.usageMessage());
    }

    public String groupsMessage(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return color(
                    "&#bbbbbbConsole uses command security directly"
            );
        }

        String groupsText = String.join(
                "&#bbbbbb, &#B078FF",
                activeGroupNames(player)
        );

        return color(
                snapshot.groupsMessage()
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
        if (canManage(sender)) {
            String lowered = input == null
                    ? ""
                    : input.toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>(2);

            for (String option : List.of(
                    "reload",
                    "groups"
            )) {
                if (option.startsWith(lowered)) {
                    matches.add(option);
                }
            }

            return List.copyOf(matches);
        }

        return List.of();
    }

    public void refreshAllCommandTrees() {
        for (Player player : core.getServer().getOnlinePlayers()) {
            queueCommandTreeRefresh(player.getUniqueId());
        }
    }

    /**
     * Thread-safe and intentionally cheap so LuckPerms events can call this
     * directly regardless of which thread dispatched the event.
     */
    public void queueCommandTreeRefresh(UUID playerId) {
        if (playerId == null || shuttingDown) {
            return;
        }

        boolean scheduleDrain = false;

        synchronized (commandRefreshLock) {
            if (!queuedCommandRefreshes.add(playerId)) {
                return;
            }

            commandRefreshQueue.addLast(playerId);

            if (!commandRefreshDrainScheduled) {
                commandRefreshDrainScheduled = true;
                scheduleDrain = true;
            }
        }

        if (scheduleDrain) {
            core.getServer().getScheduler().runTask(
                    core,
                    this::drainCommandRefreshQueue
            );
        }
    }

    private void drainCommandRefreshQueue() {
        if (shuttingDown || !core.isEnabled()) {
            clearCommandRefreshQueue();
            return;
        }

        int limit = snapshot.commandTreeRefreshesPerTick();
        List<UUID> batch = new ArrayList<>(limit);

        synchronized (commandRefreshLock) {
            for (int processed = 0; processed < limit; processed++) {
                UUID playerId = commandRefreshQueue.pollFirst();

                if (playerId == null) {
                    break;
                }

                queuedCommandRefreshes.remove(playerId);
                batch.add(playerId);
            }
        }

        for (UUID playerId : batch) {
            Player player = core.getServer().getPlayer(playerId);

            if (player != null && player.isOnline()) {
                player.updateCommands();
            }
        }

        boolean continueNextTick;

        synchronized (commandRefreshLock) {
            continueNextTick = !commandRefreshQueue.isEmpty();

            if (!continueNextTick) {
                commandRefreshDrainScheduled = false;
            }
        }

        if (continueNextTick) {
            core.getServer().getScheduler().runTaskLater(
                    core,
                    this::drainCommandRefreshQueue,
                    1L
            );
        }
    }

    private void clearCommandRefreshQueue() {
        synchronized (commandRefreshLock) {
            commandRefreshQueue.clear();
            queuedCommandRefreshes.clear();
            commandRefreshDrainScheduled = false;
        }
    }

    private SecuritySnapshot compile(FileConfiguration config) {
        Map<String, RawCommandGroup> rawGroups = loadRawGroups(config);
        Map<String, CommandGroup> resolvedGroups = new LinkedHashMap<>();

        for (String groupName : rawGroups.keySet()) {
            resolveGroup(
                    groupName,
                    rawGroups,
                    resolvedGroups,
                    new LinkedHashSet<>()
            );
        }

        List<CommandGroup> groups = new ArrayList<>(
                resolvedGroups.values()
        );
        groups.sort(
                Comparator.comparingInt(CommandGroup::priority)
        );

        Set<String> subcommandRuleCommands = new LinkedHashSet<>();

        for (CommandGroup group : groups) {
            subcommandRuleCommands.addAll(
                    group.subcommands().keySet()
            );
        }

        Set<String> blockedCommands = normalizedListOrFallback(
                config,
                "blocked-commands",
                FALLBACK_BLOCKED_COMMANDS
        );
        Set<String> consoleOnlyCommands = normalizedListOrFallback(
                config,
                "console-only-commands",
                FALLBACK_CONSOLE_ONLY_COMMANDS
        );
        Set<String> alwaysHiddenCommands = normalizeList(
                config.getStringList("always-hidden-commands")
        );

        return new SecuritySnapshot(
                config.getBoolean("enabled", true),
                config.getBoolean("block-namespaced-commands", true),
                normalizedPermission(
                        config.getString("manage-permission"),
                        DEFAULT_MANAGE_PERMISSION
                ),
                normalizedPermission(
                        config.getString("bypass-permission"),
                        DEFAULT_BYPASS_PERMISSION
                ),
                normalizedPermission(
                        config.getString("permission-aware-root-permission"),
                        DEFAULT_PERMISSION_AWARE_ROOT_PERMISSION
                ),
                Math.clamp(
                        config.getInt(
                                "performance.command-tree-refreshes-per-tick",
                                100
                        ),
                        1,
                        1000
                ),
                blockedCommands,
                consoleOnlyCommands,
                alwaysHiddenCommands,
                Set.copyOf(subcommandRuleCommands),
                List.copyOf(groups),
                config.getString(
                        "unknown-command-message",
                        "&cThis command does not exist"
                ),
                config.getString(
                        "reload-message",
                        "&#bbbbbbSecurity reloaded"
                ),
                config.getString(
                        "usage-message",
                        "&#bbbbbbUsage: &#8436FE/mineaclesecurity reload"
                ),
                config.getString(
                        "groups-message",
                        "&#bbbbbbActive command groups: &#B078FF%groups%"
                )
        );
    }

    private Map<String, RawCommandGroup> loadRawGroups(
            FileConfiguration config
    ) {
        ConfigurationSection section =
                config.getConfigurationSection("groups");

        if (section == null) {
            return Map.of();
        }

        Map<String, RawCommandGroup> groups = new LinkedHashMap<>();

        for (String key : section.getKeys(false)) {
            String name = normalizeGroup(key);
            String path = "groups." + key;

            Map<String, Set<String>> subcommands = new LinkedHashMap<>();
            ConfigurationSection subSection =
                    config.getConfigurationSection(path + ".subcommands");

            if (subSection != null) {
                for (String command : subSection.getKeys(false)) {
                    subcommands.put(
                            normalize(command),
                            normalizeList(
                                    config.getStringList(
                                            path + ".subcommands." + command
                                    )
                            )
                    );
                }
            }

            Set<String> visible = new LinkedHashSet<>(
                    normalizeList(
                            config.getStringList(path + ".visible-commands")
                    )
            );
            visible.addAll(
                    normalizeList(
                            config.getStringList(path + ".commands")
                    )
            );

            groups.put(
                    name,
                    new RawCommandGroup(
                            name,
                            normalizedPermission(
                                    config.getString(path + ".permission"),
                                    ""
                            ),
                            config.getInt(path + ".priority", 0),
                            normalizeList(
                                    config.getStringList(path + ".inherits")
                            ),
                            Set.copyOf(visible),
                            normalizeList(
                                    config.getStringList(path + ".hidden-commands")
                            ),
                            immutableSubcommands(subcommands)
                    )
            );
        }

        return Map.copyOf(groups);
    }

    private CommandGroup resolveGroup(
            String groupName,
            Map<String, RawCommandGroup> rawGroups,
            Map<String, CommandGroup> resolvedGroups,
            Set<String> resolving
    ) {
        String normalizedName = normalizeGroup(groupName);
        CommandGroup cached = resolvedGroups.get(normalizedName);

        if (cached != null) {
            return cached;
        }

        RawCommandGroup raw = rawGroups.get(normalizedName);

        if (raw == null) {
            return CommandGroup.empty(normalizedName);
        }

        if (!resolving.add(normalizedName)) {
            throw new IllegalStateException(
                    "security.yml group inheritance cycle involving: "
                            + normalizedName
            );
        }

        Set<String> visible = new LinkedHashSet<>();
        Set<String> hidden = new LinkedHashSet<>();
        Map<String, Set<String>> subcommands = new LinkedHashMap<>();

        for (String parentName : raw.inherits()) {
            CommandGroup parent = resolveGroup(
                    parentName,
                    rawGroups,
                    resolvedGroups,
                    resolving
            );

            visible.addAll(parent.visibleCommands());
            hidden.addAll(parent.hiddenCommands());
            mergeSubcommands(subcommands, parent.subcommands());
        }

        visible.addAll(raw.visibleCommands());
        hidden.addAll(raw.hiddenCommands());
        mergeSubcommands(subcommands, raw.subcommands());

        resolving.remove(normalizedName);

        CommandGroup resolved = new CommandGroup(
                raw.name(),
                raw.permission(),
                raw.priority(),
                Set.copyOf(visible),
                Set.copyOf(hidden),
                immutableSubcommands(subcommands)
        );

        resolvedGroups.put(normalizedName, resolved);
        return resolved;
    }

    private Set<String> allowedSubCommands(
            Player player,
            SecuritySnapshot current,
            String command
    ) {
        Set<String> allowed = new LinkedHashSet<>();

        for (CommandGroup group : current.groups()) {
            if (!group.activeFor(player)) {
                continue;
            }

            allowed.addAll(
                    group.subcommands().getOrDefault(
                            command,
                            Set.of()
                    )
            );
        }

        return allowed;
    }

    private void mergeSubcommands(
            Map<String, Set<String>> target,
            Map<String, Set<String>> source
    ) {
        for (Map.Entry<String, Set<String>> entry : source.entrySet()) {
            Set<String> merged = new LinkedHashSet<>(
                    target.getOrDefault(
                            entry.getKey(),
                            Set.of()
                    )
            );
            merged.addAll(entry.getValue());
            target.put(
                    entry.getKey(),
                    Set.copyOf(merged)
            );
        }
    }

    private Map<String, Set<String>> immutableSubcommands(
            Map<String, Set<String>> source
    ) {
        if (source.isEmpty()) {
            return Map.of();
        }

        Map<String, Set<String>> copy = new LinkedHashMap<>();

        source.forEach(
                (command, values) -> copy.put(
                        command,
                        Set.copyOf(values)
                )
        );

        return Map.copyOf(copy);
    }

    private Set<String> normalizedListOrFallback(
            FileConfiguration config,
            String path,
            Set<String> fallback
    ) {
        if (!config.isList(path)) {
            return fallback;
        }

        return normalizeList(config.getStringList(path));
    }

    private Set<String> normalizeList(List<String> rawValues) {
        if (rawValues.isEmpty()) {
            return Set.of();
        }

        Set<String> values = new LinkedHashSet<>();

        for (String raw : rawValues) {
            String normalized = normalize(raw);

            if (!normalized.isBlank()) {
                values.add(normalized);
            }
        }

        return Set.copyOf(values);
    }

    private static boolean isNamespacedCommand(
            SecuritySnapshot current,
            String command
    ) {
        return current.blockNamespacedCommands()
                && command.indexOf(':') >= 0;
    }

    private static boolean hasPermission(
            CommandSender sender,
            String permission
    ) {
        return permission != null
                && !permission.isBlank()
                && sender.hasPermission(permission);
    }

    private static ParsedCommand parse(String raw) {
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

        return new ParsedCommand(
                parts.length >= 1
                        ? normalize(parts[0])
                        : "",
                parts.length >= 2
                        ? normalize(parts[1])
                        : ""
        );
    }

    private static String normalizedPermission(
            String value,
            String fallback
    ) {
        if (value == null || value.isBlank()) {
            return fallback;
        }

        return value.trim();
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return "";
        }

        String normalized = raw.trim().toLowerCase(Locale.ROOT);

        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }

        return normalized;
    }

    private static String normalizeGroup(String raw) {
        return normalize(raw).replace(' ', '-');
    }

    private String color(String message) {
        return TextColor.color(message);
    }

    public static final class CommandView {

        private static final CommandView DISABLED = new CommandView(
                false,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of()
        );

        private final boolean enabled;
        private final boolean blockNamespacedCommands;
        private final boolean bypass;
        private final boolean permissionAware;
        private final Set<String> blockedCommands;
        private final Set<String> consoleOnlyCommands;
        private final Set<String> visibleCommands;
        private final Set<String> hiddenCommands;

        private CommandView(
                boolean enabled,
                boolean blockNamespacedCommands,
                boolean bypass,
                boolean permissionAware,
                Set<String> blockedCommands,
                Set<String> consoleOnlyCommands,
                Set<String> visibleCommands,
                Set<String> hiddenCommands
        ) {
            this.enabled = enabled;
            this.blockNamespacedCommands = blockNamespacedCommands;
            this.bypass = bypass;
            this.permissionAware = permissionAware;
            this.blockedCommands = blockedCommands;
            this.consoleOnlyCommands = consoleOnlyCommands;
            this.visibleCommands = visibleCommands;
            this.hiddenCommands = hiddenCommands;
        }

        private static CommandView disabled() {
            return DISABLED;
        }

        public boolean shouldHide(String rawCommand) {
            if (!enabled) {
                return false;
            }

            String command = normalize(rawCommand);

            if (command.isBlank()) {
                return false;
            }

            if ((blockNamespacedCommands && command.indexOf(':') >= 0)
                    || consoleOnlyCommands.contains(command)) {
                return true;
            }

            if (bypass) {
                return false;
            }

            if (blockedCommands.contains(command)
                    || hiddenCommands.contains(command)) {
                return true;
            }

            return !permissionAware
                    && !visibleCommands.contains(command);
        }
    }

    private record SecuritySnapshot(
            boolean enabled,
            boolean blockNamespacedCommands,
            String managePermission,
            String bypassPermission,
            String permissionAwareRootPermission,
            int commandTreeRefreshesPerTick,
            Set<String> blockedCommands,
            Set<String> consoleOnlyCommands,
            Set<String> alwaysHiddenCommands,
            Set<String> subcommandRuleCommands,
            List<CommandGroup> groups,
            String unknownMessage,
            String reloadMessage,
            String usageMessage,
            String groupsMessage
    ) {
    }

    private record RawCommandGroup(
            String name,
            String permission,
            int priority,
            Set<String> inherits,
            Set<String> visibleCommands,
            Set<String> hiddenCommands,
            Map<String, Set<String>> subcommands
    ) {
    }

    private record CommandGroup(
            String name,
            String permission,
            int priority,
            Set<String> visibleCommands,
            Set<String> hiddenCommands,
            Map<String, Set<String>> subcommands
    ) {
        private boolean activeFor(Player player) {
            return permission.isBlank()
                    || player.hasPermission(permission);
        }

        private static CommandGroup empty(String name) {
            return new CommandGroup(
                    name,
                    "",
                    0,
                    Set.of(),
                    Set.of(),
                    Map.of()
            );
        }
    }

    private record ParsedCommand(
            String command,
            String subCommand
    ) {
    }
}
