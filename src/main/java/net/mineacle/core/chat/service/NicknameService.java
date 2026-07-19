package net.mineacle.core.chat.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public final class NicknameService {

    public enum NicknameResult {
        SUCCESS,
        UNCHANGED,
        INVALID,
        TAKEN,
        STORAGE_ERROR
    }

    private static final Set<String> RESERVED =
            Set.of("reset", "clear", "off");

    private final Core core;
    private final File file;
    private final Map<UUID, String> nicknames = new HashMap<>();
    private final Map<String, UUID> nicknameOwners = new HashMap<>();
    private final Set<String> knownUsernames = new HashSet<>();
    private final Map<UUID, List<BukkitTask>> refreshTasks =
            new HashMap<>();

    public NicknameService(Core core) throws IOException {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "nicknames.yml"
        );

        ensureStorage();
        load();
    }

    public String username(OfflinePlayer player) {
        if (player == null) {
            return "";
        }

        String name = player.getName();

        return name == null || name.isBlank()
                ? player.getUniqueId().toString()
                : name;
    }

    /**
     * Returns the stored nickname body without the public dot prefix.
     */
    public String nickname(OfflinePlayer player) {
        if (player == null) {
            return "";
        }

        return nicknames.getOrDefault(
                player.getUniqueId(),
                ""
        );
    }

    public boolean hasNickname(OfflinePlayer player) {
        return !nickname(player).isBlank();
    }

    /**
     * Mineacle public identity: .nickname when set, otherwise username.
     */
    public String displayName(OfflinePlayer player) {
        String nickname = nickname(player);

        if (!nickname.isBlank()) {
            return prefix() + nickname;
        }

        return username(player);
    }

    public String rawChatDisplayName(OfflinePlayer player) {
        return "&#bbbbbb" + displayName(player);
    }

    public String coloredChatDisplayName(OfflinePlayer player) {
        return TextColor.color(rawChatDisplayName(player));
    }

    public NicknameResult setNicknameDetailed(
            Player player,
            String input
    ) {
        if (player == null || input == null) {
            return NicknameResult.INVALID;
        }

        if (TextColor.containsFormatting(input)) {
            return NicknameResult.INVALID;
        }

        String cleaned = cleanInput(input);

        if (!valid(cleaned)) {
            return NicknameResult.INVALID;
        }

        String current = nickname(player);

        if (current.equalsIgnoreCase(cleaned)) {
            return NicknameResult.UNCHANGED;
        }

        if (!available(player, cleaned)) {
            return NicknameResult.TAKEN;
        }

        UUID playerId = player.getUniqueId();

        if (!current.isBlank()) {
            nicknameOwners.remove(normalize(current));
        }

        nicknames.put(playerId, cleaned);
        nicknameOwners.put(normalize(cleaned), playerId);

        if (!saveNow()) {
            nicknames.remove(playerId);
            nicknameOwners.remove(normalize(cleaned));

            if (!current.isBlank()) {
                nicknames.put(playerId, current);
                nicknameOwners.put(normalize(current), playerId);
            }

            return NicknameResult.STORAGE_ERROR;
        }

        updatePlayerDisplay(player);
        return NicknameResult.SUCCESS;
    }

    /**
     * Compatibility method retained for existing integrations.
     */
    public boolean setNickname(
            Player player,
            String nickname
    ) {
        NicknameResult result = setNicknameDetailed(
                player,
                nickname
        );

        return result == NicknameResult.SUCCESS
                || result == NicknameResult.UNCHANGED;
    }

    public boolean clearNickname(Player player) {
        if (player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        String removed = nicknames.remove(playerId);

        if (removed == null) {
            return false;
        }

        nicknameOwners.remove(normalize(removed));

        if (!saveNow()) {
            nicknames.put(playerId, removed);
            nicknameOwners.put(normalize(removed), playerId);
            return false;
        }

        updatePlayerDisplay(player);
        return true;
    }

    public OfflinePlayer findByNickname(String input) {
        String cleaned = cleanInput(input);

        if (cleaned.isBlank()) {
            return null;
        }

        UUID ownerId = nicknameOwners.get(normalize(cleaned));
        return ownerId == null
                ? null
                : Bukkit.getOfflinePlayer(ownerId);
    }

    public List<String> nicknameSuggestions() {
        List<String> suggestions = new ArrayList<>();

        for (String nickname : nicknames.values()) {
            suggestions.add(prefix() + nickname);
        }

        suggestions.sort(String.CASE_INSENSITIVE_ORDER);
        return List.copyOf(suggestions);
    }

    public void updatePlayerDisplay(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        rememberUsername(player);
        cancelRefreshTasks(playerId);
        applyPlayerDisplay(player);

        List<BukkitTask> tasks = new ArrayList<>();

        tasks.add(
                core.getServer().getScheduler().runTask(
                        core,
                        () -> {
                            if (player.isOnline()) {
                                applyPlayerDisplay(player);
                            }
                        }
                )
        );

        tasks.add(
                core.getServer().getScheduler().runTaskLater(
                        core,
                        () -> {
                            refreshTasks.remove(playerId);

                            if (player.isOnline()) {
                                applyPlayerDisplay(player);
                            }
                        },
                        10L
                )
        );

        refreshTasks.put(playerId, tasks);
    }

    public void cleanupPlayer(UUID playerId) {
        cancelRefreshTasks(playerId);
    }

    public void shutdown() {
        for (UUID playerId
                : new ArrayList<>(refreshTasks.keySet())) {
            cancelRefreshTasks(playerId);
        }

        save();
    }

    public String prefix() {
        String configured = core.getConfig().getString(
                "nickname.prefix",
                "."
        );

        return configured == null || configured.isBlank()
                ? "."
                : configured;
    }

    public int maxLength() {
        return Math.max(
                1,
                core.getConfig().getInt(
                        "nickname.max-length",
                        15
                )
        );
    }

    /**
     * Compatibility method. Public player names are always neutral.
     */
    public String opNameColor() {
        return "#bbbbbb";
    }

    /**
     * Compatibility method. Public player names are always neutral.
     */
    public String defaultNameColor() {
        return "#bbbbbb";
    }

    public synchronized void save() {
        saveNow();
    }

    private void applyPlayerDisplay(Player player) {
        Component name = Component.text(
                        displayName(player),
                        net.kyori.adventure.text.format.TextColor.color(
                                0xBBBBBB
                        )
                )
                .decoration(TextDecoration.ITALIC, false);

        player.displayName(name);
        player.playerListName(name);
    }

    private String cleanInput(String input) {
        if (input == null) {
            return "";
        }

        String cleaned = TextColor.strip(input).trim();
        String prefix = prefix();

        if (!prefix.isBlank() && cleaned.startsWith(prefix)) {
            cleaned = cleaned.substring(prefix.length());
        }

        return cleaned.trim();
    }

    private boolean valid(String nickname) {
        if (nickname.isBlank()
                || nickname.length() > maxLength()
                || nickname.contains("&")
                || nickname.contains("§")
                || RESERVED.contains(
                nickname.toLowerCase(Locale.ROOT)
        )) {
            return false;
        }

        try {
            return allowedPattern().matcher(nickname).matches();
        } catch (PatternSyntaxException exception) {
            core.getLogger().warning(
                    "Invalid nickname.allowed-regex, using safe default"
            );
            return Pattern.compile("^[a-zA-Z0-9_]+$")
                    .matcher(nickname)
                    .matches();
        }
    }

    private boolean available(
            Player owner,
            String nickname
    ) {
        String normalized = normalize(nickname);
        UUID nicknameOwner = nicknameOwners.get(normalized);

        if (nicknameOwner != null
                && !nicknameOwner.equals(owner.getUniqueId())) {
            return false;
        }

        if (!core.getConfig().getBoolean(
                "nickname.prevent-username-impersonation",
                true
        )) {
            return true;
        }

        String ownerUsername = normalize(username(owner));
        return normalized.equals(ownerUsername)
                || !knownUsernames.contains(normalized);
    }

    private void rememberUsername(OfflinePlayer player) {
        if (player == null) {
            return;
        }

        String name = player.getName();

        if (name != null && !name.isBlank()) {
            knownUsernames.add(normalize(name));
        }
    }

    private String normalize(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }

    private Pattern allowedPattern() {
        String regex = core.getConfig().getString(
                "nickname.allowed-regex",
                "^[a-zA-Z0-9_]+$"
        );

        return Pattern.compile(regex);
    }

    private synchronized void load() {
        nicknames.clear();
        nicknameOwners.clear();
        knownUsernames.clear();

        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            rememberUsername(player);
        }

        YamlConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section =
                configuration.getConfigurationSection("nicknames");

        if (section == null) {
            return;
        }

        Set<String> claimed = new HashSet<>();
        boolean migrated = false;

        List<String> keys = new ArrayList<>(section.getKeys(false));
        keys.sort(String.CASE_INSENSITIVE_ORDER);

        for (String rawId : keys) {
            try {
                UUID playerId = UUID.fromString(rawId);
                String stored = configuration.getString(
                        "nicknames." + rawId,
                        ""
                );
                String cleaned = cleanInput(stored);

                if (!cleaned.equals(stored)) {
                    migrated = true;
                }

                if (!valid(cleaned)) {
                    migrated = true;
                    continue;
                }

                String normalized = cleaned.toLowerCase(Locale.ROOT);

                if (!claimed.add(normalized)) {
                    migrated = true;
                    continue;
                }

                nicknames.put(playerId, cleaned);
                nicknameOwners.put(normalized, playerId);
            } catch (IllegalArgumentException exception) {
                migrated = true;
            }
        }

        if (migrated) {
            saveNow();
        }
    }

    private boolean saveNow() {
        try {
            persist();
            return true;
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save nicknames.yml",
                    exception
            );
            return false;
        }
    }

    private synchronized void persist() throws IOException {
        ensureStorage();

        YamlConfiguration configuration = new YamlConfiguration();

        List<Map.Entry<UUID, String>> entries =
                new ArrayList<>(nicknames.entrySet());
        entries.sort(
                Comparator.comparing(
                        entry -> entry.getKey().toString()
                )
        );

        for (Map.Entry<UUID, String> entry : entries) {
            configuration.set(
                    "nicknames." + entry.getKey(),
                    entry.getValue()
            );
        }

        File temporary = new File(
                file.getParentFile(),
                file.getName() + ".tmp"
        );
        configuration.save(temporary);

        try {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
            );
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private void ensureStorage() throws IOException {
        File folder = core.getDataFolder();

        if (!folder.exists()
                && !folder.mkdirs()
                && !folder.exists()) {
            throw new IOException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()
                && !file.createNewFile()
                && !file.exists()) {
            throw new IOException(
                    "Could not create nicknames.yml"
            );
        }
    }

    private void cancelRefreshTasks(UUID playerId) {
        List<BukkitTask> tasks = refreshTasks.remove(playerId);

        if (tasks == null) {
            return;
        }

        for (BukkitTask task : tasks) {
            if (task != null) {
                task.cancel();
            }
        }
    }
}
