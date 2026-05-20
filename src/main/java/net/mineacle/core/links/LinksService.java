package net.mineacle.core.links;

import net.mineacle.core.Core;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
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

    public String title() {
        return config.getString("title", "Mineacle Links");
    }

    public int size() {
        int size = config.getInt("size", 27);

        if (size < 9) {
            return 9;
        }

        if (size > 54) {
            return 54;
        }

        return (int) Math.ceil(size / 9.0D) * 9;
    }

    public String noPermission() {
        return config.getString("messages.no-permission", "&cYou do not have permission");
    }

    public boolean blankLines() {
        return config.getBoolean("messages.blank-lines", true);
    }

    public boolean fallbackUrl() {
        return config.getBoolean("messages.fallback-url", true);
    }

    public List<LinkItem> items() {
        List<LinkItem> items = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("items");

        if (section == null) {
            return items;
        }

        for (String key : section.getKeys(false)) {
            if (!config.getBoolean("items." + key + ".enabled", true)) {
                continue;
            }

            items.add(item(key));
        }

        items.sort(Comparator.comparingInt(LinkItem::slot));
        return items;
    }

    public LinkItem itemAt(int slot) {
        for (LinkItem item : items()) {
            if (item.slot() == slot) {
                return item;
            }
        }

        return null;
    }

    private LinkItem item(String key) {
        String base = "items." + key;
        String promptBase = base + ".prompt";

        return new LinkItem(
                key,
                config.getInt(base + ".slot", 13),
                config.getString(base + ".material", "PLAYER_HEAD"),
                config.getString(base + ".texture", ""),
                config.getString(base + ".name", "&dLink"),
                config.getStringList(base + ".lore"),
                config.getString(base + ".url", ""),
                config.getString(promptBase + ".header", ""),
                config.getStringList(promptBase + ".lines"),
                config.getString(promptBase + ".click-text", "&#ff88ff&l&n(Click Here)"),
                config.getString(promptBase + ".hover", "&#bbbbbbOpen Link"),
                config.getString(promptBase + ".fallback-color", "&#bbbbbb")
        );
    }
}
