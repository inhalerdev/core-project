package net.mineacle.core.links;

import net.mineacle.core.Core;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class LinksService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    public LinksService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "links.yml");
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            core.saveResource("links.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public String noPermission() {
        return config.getString("messages.no-permission", "&cYou do not have permission");
    }

    public String unknownLink() {
        return config.getString("messages.unknown-link", "&cThat link is not configured");
    }

    public String reloaded() {
        return config.getString("messages.reloaded", "&#bbbbbbQuick links reloaded");
    }

    public boolean blankLines() {
        return config.getBoolean("messages.blank-lines", true);
    }

    public boolean fallbackUrl() {
        return config.getBoolean("messages.fallback-url", true);
    }

    public LinkEntry find(String key) {
        for (LinkEntry entry : entries()) {
            if (entry.matches(key)) {
                return entry;
            }
        }

        return null;
    }

    public List<LinkEntry> entries() {
        List<LinkEntry> entries = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("links");

        if (section == null) {
            return entries;
        }

        for (String key : section.getKeys(false)) {
            String base = "links." + key;

            entries.add(new LinkEntry(
                    key,
                    config.getStringList(base + ".aliases"),
                    config.getString(base + ".url", ""),
                    config.getString(base + ".header", "&dLink"),
                    config.getStringList(base + ".lines"),
                    config.getString(base + ".button", "&#ff88ff➜ Open Link"),
                    config.getString(base + ".hover", "&#bbbbbbOpen Link"),
                    config.getString(base + ".fallback-color", "&#bbbbbb")
            ));
        }

        return entries;
    }
}
