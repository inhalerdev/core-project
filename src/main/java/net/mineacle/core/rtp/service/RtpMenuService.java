package net.mineacle.core.rtp.service;

import me.clip.placeholderapi.PlaceholderAPI;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RtpMenuService {

    private final Core core;
    private final File file;
    private FileConfiguration config;

    public RtpMenuService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "rtp.yml");
        reload();
    }

    public void reload() {
        if (!core.getDataFolder().exists()) {
            core.getDataFolder().mkdirs();
        }

        if (!file.exists()) {
            core.saveResource("rtp.yml", false);
        }

        config = YamlConfiguration.loadConfiguration(file);
    }

    public boolean enabled() {
        return config.getBoolean("enabled", true);
    }

    public String title(String menu) {
        return TextColor.color(config.getString("menus." + menu + ".title", menu));
    }

    public String rawTitle(String menu) {
        return org.bukkit.ChatColor.stripColor(title(menu));
    }

    public int size(String menu) {
        int size = config.getInt("menus." + menu + ".size", 27);

        if (size < 9) {
            return 27;
        }

        if (size > 54) {
            return 54;
        }

        return ((size + 8) / 9) * 9;
    }

    public List<RtpMenuItem> items(String menu) {
        List<RtpMenuItem> items = new ArrayList<>();
        ConfigurationSection section = config.getConfigurationSection("menus." + menu + ".items");

        if (section == null) {
            return items;
        }

        for (String key : section.getKeys(false)) {
            String base = "menus." + menu + ".items." + key;

            if (!config.getBoolean(base + ".enabled", true)) {
                continue;
            }

            items.add(new RtpMenuItem(
                    key,
                    config.getInt(base + ".slot", 0),
                    material(config.getString(base + ".material", "STONE")),
                    config.getString(base + ".name", "&d" + key),
                    config.getStringList(base + ".lore"),
                    config.getString(base + ".destination", ""),
                    config.getString(base + ".action.type", ""),
                    config.getString(base + ".action.server", ""),
                    config.getString(base + ".action.fallback-command", "")
            ));
        }

        return items;
    }

    public RtpMenuItem itemAt(String menu, int slot) {
        for (RtpMenuItem item : items(menu)) {
            if (item.slot() == slot) {
                return item;
            }
        }

        return null;
    }

    public String message(String path, String fallback) {
        return TextColor.color(config.getString("messages." + path, fallback));
    }

    public String parse(Player player, String text) {
        String parsed = TextColor.color(text == null ? "" : text);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                parsed = PlaceholderAPI.setPlaceholders(player, parsed);
            } catch (Throwable ignored) {
            }
        }

        return parsed;
    }

    public List<String> parseLore(Player player, List<String> lore) {
        List<String> parsed = new ArrayList<>();

        for (String line : lore) {
            parsed.add(parse(player, line));
        }

        return parsed;
    }

    private Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.STONE;
        }

        try {
            Material material = Material.valueOf(raw.toUpperCase(Locale.ROOT));
            return material.isItem() ? material : Material.STONE;
        } catch (IllegalArgumentException exception) {
            return Material.STONE;
        }
    }
}
