package net.mineacle.core.stats.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

public final class StatsStorageService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    public StatsStorageService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "stats.yml");
        load();
    }

    public void load() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            try {
                file.createNewFile();
            } catch (IOException exception) {
                core.getLogger().severe("Could not create stats.yml");
                exception.printStackTrace();
            }
        }

        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public void save() {
        if (config == null) {
            return;
        }

        try {
            config.save(file);
        } catch (IOException exception) {
            core.getLogger().severe("Could not save stats.yml");
            exception.printStackTrace();
        }
    }

    public void ensureProfile(OfflinePlayer player) {
        UUID uuid = player.getUniqueId();
        String path = profilePath(uuid);

        if (!config.contains(path + ".playtime-seconds")) {
            config.set(path + ".playtime-seconds", 0L);
        }

        if (!config.contains(path + ".kills")) {
            config.set(path + ".kills", 0L);
        }

        if (!config.contains(path + ".deaths")) {
            config.set(path + ".deaths", 0L);
        }

        if (player.getName() != null) {
            config.set(path + ".name", player.getName());
        }
    }

    public OfflinePlayer offline(UUID uuid) {
        return Bukkit.getOfflinePlayer(uuid);
    }

    public long playtimeSeconds(UUID uuid) {
        return config.getLong(profilePath(uuid) + ".playtime-seconds", 0L);
    }

    public void setPlaytimeSeconds(UUID uuid, long seconds) {
        config.set(profilePath(uuid) + ".playtime-seconds", Math.max(0L, seconds));
    }

    public void addPlaytimeSeconds(UUID uuid, long seconds) {
        if (seconds <= 0L) {
            return;
        }

        setPlaytimeSeconds(uuid, playtimeSeconds(uuid) + seconds);
    }

    public long kills(UUID uuid) {
        return config.getLong(profilePath(uuid) + ".kills", 0L);
    }

    public void addKill(UUID uuid) {
        config.set(profilePath(uuid) + ".kills", kills(uuid) + 1L);
    }

    public long deaths(UUID uuid) {
        return config.getLong(profilePath(uuid) + ".deaths", 0L);
    }

    public void addDeath(UUID uuid) {
        config.set(profilePath(uuid) + ".deaths", deaths(uuid) + 1L);
    }

    public List<StatProfile> profiles() {
        ConfigurationSection section = config.getConfigurationSection("players");

        if (section == null) {
            return List.of();
        }

        List<StatProfile> profiles = new ArrayList<>();

        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                profiles.add(new StatProfile(
                        uuid,
                        config.getString("players." + key + ".name", ""),
                        config.getLong("players." + key + ".kills", 0L),
                        config.getLong("players." + key + ".deaths", 0L),
                        config.getLong("players." + key + ".playtime-seconds", 0L)
                ));
            } catch (IllegalArgumentException ignored) {
            }
        }

        return profiles;
    }

    public List<StatProfile> topKills(int limit) {
        return top(limit, Comparator
                .comparingLong(StatProfile::kills)
                .thenComparingLong(StatProfile::playtimeSeconds)
                .reversed());
    }

    public List<StatProfile> topDeaths(int limit) {
        return top(limit, Comparator
                .comparingLong(StatProfile::deaths)
                .thenComparingLong(StatProfile::playtimeSeconds)
                .reversed());
    }

    public List<StatProfile> topPlaytime(int limit) {
        return top(limit, Comparator
                .comparingLong(StatProfile::playtimeSeconds)
                .thenComparingLong(StatProfile::kills)
                .reversed());
    }

    public int rankKills(UUID uuid) {
        return rank(uuid, topKills(Integer.MAX_VALUE));
    }

    public int rankDeaths(UUID uuid) {
        return rank(uuid, topDeaths(Integer.MAX_VALUE));
    }

    public int rankPlaytime(UUID uuid) {
        return rank(uuid, topPlaytime(Integer.MAX_VALUE));
    }

    private List<StatProfile> top(int limit, Comparator<StatProfile> comparator) {
        List<StatProfile> profiles = new ArrayList<>(profiles());
        profiles.removeIf(profile -> profile.kills() <= 0L && profile.deaths() <= 0L && profile.playtimeSeconds() <= 0L);
        profiles.sort(comparator);

        if (limit <= 0 || profiles.size() <= limit) {
            return profiles;
        }

        return profiles.subList(0, limit);
    }

    private int rank(UUID uuid, List<StatProfile> profiles) {
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).uuid().equals(uuid)) {
                return i + 1;
            }
        }

        return 0;
    }

    private String profilePath(UUID uuid) {
        return "players." + uuid;
    }

    public record StatProfile(UUID uuid, String name, long kills, long deaths, long playtimeSeconds) {
    }
}
