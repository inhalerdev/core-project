package net.mineacle.core.stats.service;

import net.mineacle.core.Core;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
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

    private String profilePath(UUID uuid) {
        return "players." + uuid;
    }
}
