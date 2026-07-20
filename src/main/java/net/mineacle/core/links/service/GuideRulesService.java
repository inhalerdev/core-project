package net.mineacle.core.links.service;

import net.mineacle.core.Core;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GuideRulesService {

    public static final String GUIDE = "guide";
    public static final String RULES = "rules";

    private final Core core;
    private final File guideFile;
    private final File rulesFile;

    private MenuDefinition guide;
    private MenuDefinition rules;

    public GuideRulesService(Core core) {
        this.core = core;
        this.guideFile = new File(
                core.getDataFolder(),
                "guide.yml"
        );
        this.rulesFile = new File(
                core.getDataFolder(),
                "rules.yml"
        );
        reload();
    }

    public void reload() {
        ensureFile(guideFile, "guide.yml");
        ensureFile(rulesFile, "rules.yml");

        guide = load(GUIDE, guideFile, "Guide", 27);
        rules = load(RULES, rulesFile, "Rules", 36);
    }

    public MenuDefinition menu(String menuKey) {
        return RULES.equalsIgnoreCase(menuKey)
                ? rules
                : guide;
    }

    private MenuDefinition load(
            String key,
            File file,
            String defaultTitle,
            int defaultSize
    ) {
        FileConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        String title = configuration.getString(
                "title",
                defaultTitle
        );
        int size = normalizeSize(
                configuration.getInt(
                        "size",
                        defaultSize
                )
        );
        Map<Integer, MenuItemDefinition> items =
                new LinkedHashMap<>();
        ConfigurationSection itemSection =
                configuration.getConfigurationSection("items");

        if (itemSection != null) {
            List<String> keys =
                    new ArrayList<>(
                            itemSection.getKeys(false)
                    );
            keys.sort(Comparator.naturalOrder());

            for (String itemKey : keys) {
                ConfigurationSection section =
                        itemSection.getConfigurationSection(
                                itemKey
                        );

                if (section == null) {
                    continue;
                }

                int slot = section.getInt("slot", -1);

                if (slot < 0
                        || slot >= size
                        || items.containsKey(slot)) {
                    continue;
                }

                items.put(
                        slot,
                        new MenuItemDefinition(
                                material(
                                        section.getString(
                                                "material",
                                                "BOOK"
                                        )
                                ),
                                section.getString(
                                        "name",
                                        "&dMenu Item"
                                ),
                                List.copyOf(
                                        section.getStringList(
                                                "lore"
                                        )
                                ),
                                section.getString(
                                        "action",
                                        ""
                                ).trim()
                        )
                );
            }
        }

        return new MenuDefinition(
                key,
                title,
                size,
                Map.copyOf(items)
        );
    }

    private int normalizeSize(int size) {
        int clamped = Math.max(9, Math.min(54, size));

        if (clamped % 9 == 0) {
            return clamped;
        }

        return Math.min(
                54,
                ((clamped / 9) + 1) * 9
        );
    }

    private Material material(String rawMaterial) {
        Material material = Material.matchMaterial(
                rawMaterial == null ? "" : rawMaterial
        );

        return material == null || !material.isItem()
                ? Material.BOOK
                : material;
    }

    private void ensureFile(File file, String resourceName) {
        File dataFolder = core.getDataFolder();

        if (!dataFolder.exists()
                && !dataFolder.mkdirs()
                && !dataFolder.exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()) {
            core.saveResource(resourceName, false);
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize " + resourceName
            );
        }
    }

    public record MenuDefinition(
            String key,
            String title,
            int size,
            Map<Integer, MenuItemDefinition> items
    ) {
    }

    public record MenuItemDefinition(
            Material material,
            String name,
            List<String> lore,
            String action
    ) {
    }
}
