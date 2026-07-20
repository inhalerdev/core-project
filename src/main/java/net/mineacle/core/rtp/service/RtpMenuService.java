package net.mineacle.core.rtp.service;

import me.clip.placeholderapi.PlaceholderAPI;
import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RtpMenuService {

    public static final String MAIN_MENU = "main";

    private final Core core;
    private final File file;

    private volatile Map<String, MenuDefinition> menus =
            Map.of();

    public RtpMenuService(Core core) {
        this.core = core;
        this.file = new File(
                core.getDataFolder(),
                "rtp.yml"
        );
        reload();
    }

    public void reload() {
        ensureFile();

        YamlConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        Map<String, MenuDefinition> loaded =
                new LinkedHashMap<>();
        ConfigurationSection menuSection =
                configuration.getConfigurationSection(
                        "menus"
                );

        if (menuSection != null) {
            for (String menuKey
                    : menuSection.getKeys(false)) {
                MenuDefinition definition = loadMenu(
                        configuration,
                        menuKey
                );

                if (definition != null) {
                    loaded.put(
                            menuKey.toLowerCase(
                                    Locale.ROOT
                            ),
                            definition
                    );
                }
            }
        }

        if (!loaded.containsKey(MAIN_MENU)) {
            loaded.put(
                    MAIN_MENU,
                    fallbackMenu()
            );
        }

        menus = Map.copyOf(loaded);
    }

    public MenuDefinition menu(String rawKey) {
        String key = rawKey == null
                ? MAIN_MENU
                : rawKey.toLowerCase(Locale.ROOT);

        return menus.getOrDefault(
                key,
                menus.get(MAIN_MENU)
        );
    }

    public String parse(
            Player player,
            String input
    ) {
        String parsed = TextColor.color(
                input == null ? "" : input
        );

        if (Bukkit.getPluginManager()
                .getPlugin("PlaceholderAPI") != null) {
            try {
                parsed = PlaceholderAPI.setPlaceholders(
                        player,
                        parsed
                );
            } catch (Throwable ignored) {
            }
        }

        return parsed;
    }

    public List<String> parseLore(
            Player player,
            RtpMenuItem item
    ) {
        List<String> parsed =
                new ArrayList<>(item.lore().size());
        String online = String.valueOf(
                online(item.destination())
        );

        for (String line : item.lore()) {
            parsed.add(
                    parse(player, line)
                            .replace(
                                    "%online%",
                                    online
                            )
            );
        }

        return List.copyOf(parsed);
    }

    public int online(String rawDestination) {
        String destination =
                OriginRtpSearchSettings
                        .canonicalDestination(
                                rawDestination
                        );
        String worldName =
                OriginRtpSearchSettings
                        .canonicalWorld(
                                core.getConfig()
                                        .getString(
                                                "origin-rtp.destinations."
                                                        + destination
                                                        + ".world",
                                                destination
                                        )
                        );
        World world = Bukkit.getWorld(worldName);

        return world == null
                ? 0
                : world.getPlayers().size();
    }

    private MenuDefinition loadMenu(
            YamlConfiguration configuration,
            String menuKey
    ) {
        String base = "menus." + menuKey;
        int size = normalizeSize(
                configuration.getInt(
                        base + ".size",
                        27
                )
        );
        String title = configuration.getString(
                base + ".title",
                "Random Teleport"
        );
        ConfigurationSection itemSection =
                configuration.getConfigurationSection(
                        base + ".items"
                );
        Map<Integer, RtpMenuItem> items =
                new LinkedHashMap<>();

        if (itemSection != null) {
            List<String> keys =
                    new ArrayList<>(
                            itemSection.getKeys(false)
                    );
            keys.sort(Comparator.naturalOrder());

            for (String key : keys) {
                String itemBase =
                        base + ".items." + key;

                if (!configuration.getBoolean(
                        itemBase + ".enabled",
                        true
                )) {
                    continue;
                }

                int slot = configuration.getInt(
                        itemBase + ".slot",
                        -1
                );

                if (slot < 0
                        || slot >= size
                        || items.containsKey(slot)) {
                    continue;
                }

                String destination =
                        OriginRtpSearchSettings
                                .canonicalDestination(
                                        configuration.getString(
                                                itemBase
                                                        + ".destination",
                                                key
                                        )
                                );

                if (!knownDestination(destination)) {
                    core.getLogger().warning(
                            "Ignoring invalid RTP menu destination: "
                                    + destination
                    );
                    continue;
                }

                items.put(
                        slot,
                        new RtpMenuItem(
                                key,
                                slot,
                                material(
                                        configuration.getString(
                                                itemBase
                                                        + ".material",
                                                "COMPASS"
                                        )
                                ),
                                configuration.getString(
                                        itemBase + ".name",
                                        "&d" + key
                                ),
                                List.copyOf(
                                        configuration.getStringList(
                                                itemBase + ".lore"
                                        )
                                ),
                                destination
                        )
                );
            }
        }

        return new MenuDefinition(
                menuKey.toLowerCase(Locale.ROOT),
                title,
                size,
                Map.copyOf(items)
        );
    }

    private MenuDefinition fallbackMenu() {
        return new MenuDefinition(
                MAIN_MENU,
                "Random Teleport",
                27,
                Map.of(
                        11,
                        new RtpMenuItem(
                                "overworld",
                                11,
                                Material.GRASS_BLOCK,
                                "&dOverworld",
                                List.of(
                                        "&#bbbbbbClick to random teleport"
                                ),
                                "overworld"
                        ),
                        13,
                        new RtpMenuItem(
                                "nether",
                                13,
                                Material.NETHERRACK,
                                "&dNether",
                                List.of(
                                        "&#bbbbbbClick to random teleport"
                                ),
                                "nether"
                        ),
                        15,
                        new RtpMenuItem(
                                "end",
                                15,
                                Material.END_STONE,
                                "&dThe End",
                                List.of(
                                        "&#bbbbbbClick to random teleport"
                                ),
                                "end"
                        )
                )
        );
    }

    private int normalizeSize(int size) {
        int clamped = Math.max(
                9,
                Math.min(54, size)
        );

        return clamped % 9 == 0
                ? clamped
                : Math.min(
                54,
                ((clamped / 9) + 1) * 9
        );
    }

    private Material material(String raw) {
        Material material = Material.matchMaterial(
                raw == null ? "" : raw
        );

        return material == null || !material.isItem()
                ? Material.COMPASS
                : material;
    }

    private boolean knownDestination(
            String destination
    ) {
        return destination.equals("overworld")
                || destination.equals("nether")
                || destination.equals("end");
    }

    private void ensureFile() {
        File folder = core.getDataFolder();

        if (!folder.exists()
                && !folder.mkdirs()
                && !folder.exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()) {
            core.saveResource("rtp.yml", false);
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize rtp.yml"
            );
        }
    }

    public record MenuDefinition(
            String key,
            String title,
            int size,
            Map<Integer, RtpMenuItem> items
    ) {

        public RtpMenuItem itemAt(int slot) {
            return items.get(slot);
        }
    }
}
