package net.mineacle.core.links.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class GuideRulesGui {

    public static final String GUIDE_KEY = "guide";
    public static final String RULES_KEY = "rules";
    public static final String GUIDE_TITLE = "Guide";
    public static final String RULES_TITLE = "Rules";

    private GuideRulesGui() {
    }

    public static void openGuide(Player player) {
        open(player, GUIDE_KEY);
    }

    public static void openRules(Player player) {
        open(player, RULES_KEY);
    }

    public static void open(Player player, String menuKey) {
        Core core = Core.instance();
        FileConfiguration config = menuConfig(core, menuKey);

        String title = config.getString("title", menuKey.equalsIgnoreCase(RULES_KEY) ? RULES_TITLE : GUIDE_TITLE);
        int size = normalizeSize(config.getInt("size", 36));

        Inventory inventory = Bukkit.createInventory(null, size, legacy(title));

        ConfigurationSection items = config.getConfigurationSection("items");

        if (items != null) {
            List<String> keys = new ArrayList<>(items.getKeys(false));
            keys.sort(Comparator.naturalOrder());

            for (String key : keys) {
                ConfigurationSection section = items.getConfigurationSection(key);

                if (section == null) {
                    continue;
                }

                int slot = section.getInt("slot", -1);

                if (slot < 0 || slot >= size) {
                    continue;
                }

                inventory.setItem(slot, item(section));
            }
        }

        player.openInventory(inventory);
    }

    public static String action(String menuKey, int slot) {
        Core core = Core.instance();
        FileConfiguration config = menuConfig(core, menuKey);
        ConfigurationSection items = config.getConfigurationSection("items");

        if (items == null) {
            return "";
        }

        for (String key : items.getKeys(false)) {
            ConfigurationSection section = items.getConfigurationSection(key);

            if (section == null) {
                continue;
            }

            if (section.getInt("slot", -1) == slot) {
                return section.getString("action", "");
            }
        }

        return "";
    }

    public static String configuredTitle(String menuKey) {
        Core core = Core.instance();
        FileConfiguration config = menuConfig(core, menuKey);
        return config.getString("title", menuKey.equalsIgnoreCase(RULES_KEY) ? RULES_TITLE : GUIDE_TITLE);
    }

    private static FileConfiguration menuConfig(Core core, String menuKey) {
        String fileName = menuKey.equalsIgnoreCase(RULES_KEY) ? "rules.yml" : "guide.yml";
        File file = new File(core.getDataFolder(), fileName);

        if (!file.exists()) {
            core.saveResource(fileName, false);
        }

        return YamlConfiguration.loadConfiguration(file);
    }

    private static int normalizeSize(int size) {
        if (size < 9) {
            return 9;
        }

        if (size > 54) {
            return 54;
        }

        if (size % 9 != 0) {
            return ((size / 9) + 1) * 9;
        }

        return size;
    }

    private static ItemStack item(ConfigurationSection section) {
        Material material = material(section.getString("material", "BOOK"));
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.displayName(legacy(section.getString("name", "&dMenu Item")));

        List<Component> lore = new ArrayList<>();

        for (String line : section.getStringList("lore")) {
            lore.add(legacy(line));
        }

        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_DYE);
        item.setItemMeta(meta);
        return item;
    }

    private static Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return Material.BOOK;
        }

        try {
            return Material.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            return Material.BOOK;
        }
    }

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(text == null ? "" : text));
    }
}
