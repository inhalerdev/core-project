package net.mineacle.core.links.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LinksService {

    private static final List<String> ORDER = List.of(
            "store",
            "discord",
            "x"
    );

    private final Core core;
    private final File file;

    private boolean enabled;
    private Map<String, LinkDefinition> links = Map.of();

    public LinksService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "links.yml");
        reload();
    }

    public void reload() {
        ensureDataFile();

        FileConfiguration configuration =
                YamlConfiguration.loadConfiguration(file);
        enabled = configuration.getBoolean("enabled", true);

        Map<String, LinkDefinition> loaded =
                new LinkedHashMap<>();
        ConfigurationSection section =
                configuration.getConfigurationSection("links");

        if (section != null) {
            for (String rawKey : section.getKeys(false)) {
                String key = normalize(rawKey);
                ConfigurationSection linkSection =
                        section.getConfigurationSection(rawKey);

                if (linkSection == null
                        || !ORDER.contains(key)) {
                    continue;
                }

                loaded.put(
                        key,
                        new LinkDefinition(
                                key,
                                linkSection.getString(
                                        "title",
                                        "&dMineacle"
                                ),
                                List.copyOf(
                                        linkSection.getStringList(
                                                "lines"
                                        )
                                ),
                                linkSection.getString(
                                        "button",
                                        "&d› &#bbbbbbOpen"
                                ),
                                linkSection.getString(
                                        "button-line",
                                        "&d› &#bbbbbbOpen"
                                ),
                                linkSection.getString(
                                        "hover",
                                        "&#bbbbbbClick to open"
                                ),
                                validUrl(
                                        linkSection.getString(
                                                "url",
                                                ""
                                        )
                                )
                        )
                );
            }
        }

        links = Map.copyOf(loaded);
    }

    public void sendLink(Player player, String rawKey) {
        if (player == null) {
            return;
        }

        if (!enabled) {
            error(player, "&cLinks are currently disabled");
            return;
        }

        LinkDefinition link = links.get(normalize(rawKey));

        if (link == null) {
            error(player, "&cThat link is not configured");
            return;
        }

        if (link.url() == null) {
            error(
                    player,
                    "&cThat link has an invalid URL configuration"
            );
            return;
        }

        player.sendMessage(Component.empty());
        player.sendMessage(legacy(link.title()));

        for (String line : link.lines()) {
            player.sendMessage(legacy(line));
        }

        player.sendMessage(Component.empty());
        player.sendMessage(
                clickable(
                        link.buttonLine(),
                        link.hover(),
                        link.url()
                )
        );

        SoundService.guiClick(player, core);
    }

    public void sendAllLinks(Player player) {
        if (player == null) {
            return;
        }

        if (!enabled) {
            error(player, "&cLinks are currently disabled");
            return;
        }

        player.sendMessage(Component.empty());
        player.sendMessage(legacy("&dMineacle Links"));

        int sent = 0;

        for (String key : ORDER) {
            LinkDefinition link = links.get(key);

            if (link == null || link.url() == null) {
                continue;
            }

            Component line = legacy(
                    "&#bbbbbb- "
                            + plainTitle(link.title())
                            + " "
            ).append(
                    clickable(
                            link.button(),
                            link.hover(),
                            link.url()
                    )
            );

            player.sendMessage(line);
            sent++;
        }

        if (sent == 0) {
            error(player, "&cNo links are configured");
            return;
        }

        SoundService.guiClick(player, core);
    }

    private String normalize(String rawKey) {
        return rawKey == null
                ? ""
                : rawKey.trim().toLowerCase(Locale.ROOT);
    }

    private String validUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }

        try {
            URI uri = new URI(rawUrl.trim());
            String scheme = uri.getScheme();

            if (scheme == null
                    || (!scheme.equalsIgnoreCase("https")
                    && !scheme.equalsIgnoreCase("http"))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()) {
                return null;
            }

            return uri.toASCIIString();
        } catch (URISyntaxException exception) {
            return null;
        }
    }

    private Component clickable(
            String text,
            String hover,
            String url
    ) {
        return legacy(text)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(
                        HoverEvent.showText(legacy(hover))
                );
    }

    private String plainTitle(String coloredTitle) {
        String stripped = ChatColor.stripColor(
                TextColor.color(
                        coloredTitle == null
                                ? "Mineacle"
                                : coloredTitle
                )
        );

        return stripped == null || stripped.isBlank()
                ? "Mineacle"
                : stripped;
    }

    private void error(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }

    private Component legacy(String text) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(
                        TextColor.color(text == null ? "" : text)
                );
    }

    private void ensureDataFile() {
        File dataFolder = core.getDataFolder();

        if (!dataFolder.exists()
                && !dataFolder.mkdirs()
                && !dataFolder.exists()) {
            throw new IllegalStateException(
                    "Could not create MineacleCore data folder"
            );
        }

        if (!file.exists()) {
            core.saveResource("links.yml", false);
        }

        if (!file.isFile()) {
            throw new IllegalStateException(
                    "Could not initialize links.yml"
            );
        }
    }

    public record LinkDefinition(
            String key,
            String title,
            List<String> lines,
            String button,
            String buttonLine,
            String hover,
            String url
    ) {
    }
}
