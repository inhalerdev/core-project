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

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";

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
            for (String menuKey :
                    menuSection.getKeys(false)) {
                MenuDefinition definition = loadMenu(
                        configuration,
                        menuKey
                );

                loaded.put(
                        menuKey.toLowerCase(
                                Locale.ROOT
                        ),
                        definition
                );
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
                normalizePalette(
                        input == null ? "" : input
                )
        );

        if (Bukkit.getPluginManager()
                .getPlugin("PlaceholderAPI") != null) {
            try {
                parsed = PlaceholderAPI.setPlaceholders(
                        player,
                        parsed
                );
            } catch (RuntimeException ignored) {
                // Placeholder failure must never break a Mineacle menu.
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
        OriginRtpSearchSettings settings =
                OriginRtpSearchSettings.fromConfig(
                        core,
                        item.destination()
                );
        String minimumDistance = String.format(
                Locale.US,
                "%,d",
                settings.minimumDistanceFromWorldSpawn()
        );

        for (String line : item.lore()) {
            String resolved = line;

            /* Compatibility with an older live rtp.yml. */
            if (resolved.contains(
                    "At least 1,000 blocks from world spawn"
            )) {
                resolved = BODY
                        + "Explores at least "
                        + "%minimum_distance% blocks from spawn";
            }

            parsed.add(
                    parse(player, resolved)
                            .replace(
                                    "%online%",
                                    online
                            )
                            .replace(
                                    "%minimum_distance%",
                                    minimumDistance
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
                                        PRIMARY + key
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
                                PRIMARY + "Overworld",
                                List.of(
                                        ACCENT
                                                + "Click to random teleport"
                                ),
                                "overworld"
                        ),
                        13,
                        new RtpMenuItem(
                                "nether",
                                13,
                                Material.NETHERRACK,
                                PRIMARY + "Nether",
                                List.of(
                                        ACCENT
                                                + "Click to random teleport"
                                ),
                                "nether"
                        ),
                        15,
                        new RtpMenuItem(
                                "end",
                                15,
                                Material.END_STONE,
                                PRIMARY + "The End",
                                List.of(
                                        ACCENT
                                                + "Click to random teleport"
                                ),
                                "end"
                        )
                )
        );
    }

    private String normalizePalette(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        return value
                .replace("&#ff55ff", PRIMARY)
                .replace("&#FF55FF", PRIMARY)
                .replace("&#ff88ff", SECONDARY)
                .replace("&#FF88FF", SECONDARY)
                .replace("&d", PRIMARY);
    }

    private int normalizeSize(int size) {
        int clamped = Math.clamp(
                size,
                9,
                54
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
                && !folder.isDirectory()) {
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
