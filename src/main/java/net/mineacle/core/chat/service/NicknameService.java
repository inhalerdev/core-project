package net.mineacle.core.chat.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public final class NicknameService {

    private final Core core;
    private final File file;
    private final FileConfiguration config;
    private final Map<UUID, String> nicknames = new HashMap<>();

    public NicknameService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "nicknames.yml");

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException exception) {
                core.getLogger().severe("Could not create nicknames.yml");
                exception.printStackTrace();
            }
        }

        this.config = YamlConfiguration.loadConfiguration(file);
        load();
    }

    public String username(OfflinePlayer player) {
        if (player == null) {
            return "";
        }

        String name = player.getName();
        return name == null || name.isBlank() ? player.getUniqueId().toString() : name;
    }

    public String nickname(OfflinePlayer player) {
        if (player == null) {
            return "";
        }

        return nicknames.getOrDefault(player.getUniqueId(), "");
    }

    public String displayName(OfflinePlayer player) {
        String nickname = nickname(player);

        if (!nickname.isBlank()) {
            return prefix() + nickname;
        }

        return username(player);
    }

    public String rawChatDisplayName(OfflinePlayer player) {
        String color = defaultNameColor();

        if (player != null && player.isOp()) {
            color = opNameColor();
        }

        return color + displayName(player);
    }

    public String coloredChatDisplayName(OfflinePlayer player) {
        return TextColor.color(rawChatDisplayName(player));
    }

    public boolean setNickname(Player player, String nickname) {
        if (player == null || nickname == null) {
            return false;
        }

        String cleaned = nickname.trim();

        if (cleaned.startsWith(prefix())) {
            cleaned = cleaned.substring(prefix().length());
        }

        if (cleaned.isBlank()) {
            return false;
        }

        if (cleaned.length() > maxLength()) {
            return false;
        }

        if (!allowedPattern().matcher(cleaned).matches()) {
            return false;
        }

        nicknames.put(player.getUniqueId(), cleaned);
        save();
        updatePlayerDisplay(player);
        return true;
    }

    public void clearNickname(Player player) {
        if (player == null) {
            return;
        }

        nicknames.remove(player.getUniqueId());
        save();
        updatePlayerDisplay(player);
    }

    public OfflinePlayer findByNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return null;
        }

        String cleaned = nickname.trim();

        if (cleaned.startsWith(prefix())) {
            cleaned = cleaned.substring(prefix().length());
        }

        String target = cleaned.toLowerCase(Locale.ROOT);

        for (Map.Entry<UUID, String> entry : nicknames.entrySet()) {
            if (entry.getValue().toLowerCase(Locale.ROOT).equals(target)) {
                return Bukkit.getOfflinePlayer(entry.getKey());
            }
        }

        return null;
    }

    public void updatePlayerDisplay(Player player) {
        if (player == null) {
            return;
        }

        applyPlayerDisplay(player);

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (player.isOnline()) {
                applyPlayerDisplay(player);
            }
        }, 2L);

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (player.isOnline()) {
                applyPlayerDisplay(player);
            }
        }, 20L);
    }

    private void applyPlayerDisplay(Player player) {
        String displayName = TextColor.color(displayName(player));
        String tabName = coloredChatDisplayName(player);

        player.setDisplayName(displayName);
        player.setPlayerListName(tabName);
        player.setCustomName(displayName);
        player.setCustomNameVisible(false);
    }

    public String prefix() {
        return core.getConfig().getString("nickname.prefix", ".");
    }

    public int maxLength() {
        return Math.max(1, core.getConfig().getInt("nickname.max-length", 15));
    }

    public String opNameColor() {
        return core.getConfig().getString("nickname.op-name-color", "#FF80FF");
    }

    public String defaultNameColor() {
        return core.getConfig().getString("nickname.default-name-color", "#bbbbbb");
    }

    private Pattern allowedPattern() {
        String regex = core.getConfig().getString("nickname.allowed-regex", "^[a-zA-Z_0-9&§]+$");
        return Pattern.compile(regex);
    }

    private void load() {
        nicknames.clear();

        ConfigurationSection section = config.getConfigurationSection("nicknames");

        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                String nickname = config.getString("nicknames." + key, "");

                if (nickname == null || nickname.isBlank()) {
                    continue;
                }

                if (nickname.startsWith(prefix())) {
                    nickname = nickname.substring(prefix().length());
                }

                if (!nickname.isBlank()) {
                    nicknames.put(uuid, nickname);
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        config.set("nicknames", null);

        for (Map.Entry<UUID, String> entry : nicknames.entrySet()) {
            config.set("nicknames." + entry.getKey(), entry.getValue());
        }

        try {
            config.save(file);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save nicknames.yml");
            exception.printStackTrace();
        }
    }
}