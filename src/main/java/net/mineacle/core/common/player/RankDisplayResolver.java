package net.mineacle.core.common.player;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.cacheddata.CachedMetaData;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Read-only LuckPerms identity view.
 * <p>
 * MineacleCore never maps a group key to gameplay behavior here. LuckPerms
 * owns membership, inheritance, contexts, prefix resolution, display names
 * and group weights.
 */
public final class RankDisplayResolver {

    private static final String DEFAULT_COLOR =
            "#bbbbbb";

    private static final DisplayRank DEFAULT_RANK =
            new DisplayRank(
                    "default",
                    "Default",
                    "",
                    "",
                    DEFAULT_COLOR,
                    0
            );

    private static final Pattern DIRECT_HEX =
            Pattern.compile(
                    "(?i)&?#([0-9a-f]{6})"
            );
    private static final Pattern SECTION_HEX =
            Pattern.compile(
                    "(?i)§x§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])§([0-9a-f])"
            );
    private static final Pattern LEGACY_COLOR =
            Pattern.compile(
                    "(?i)[&§]([0-9a-f])"
            );

    private static volatile LuckPerms cachedLuckPerms;

    private RankDisplayResolver() {
    }

    public static DisplayRank defaultRank() {
        return DEFAULT_RANK;
    }

    public static DisplayRank resolve(
            OfflinePlayer player
    ) {
        if (player == null) {
            return DEFAULT_RANK;
        }

        LuckPerms luckPerms = luckPerms();

        if (luckPerms == null) {
            return DEFAULT_RANK;
        }

        return resolveUser(
                user(luckPerms, player)
        );
    }

    public static DisplayRank resolveUser(
            User user
    ) {
        if (user == null) {
            return DEFAULT_RANK;
        }

        LuckPerms luckPerms = luckPerms();
        CachedMetaData meta =
                user.getCachedData()
                        .getMetaData();

        String primaryGroup =
                normalizeGroup(
                        firstNonBlank(
                                meta.getPrimaryGroup(),
                                user.getPrimaryGroup()
                        )
                );

        String prefix =
                safe(meta.getPrefix());

        String publicName =
                firstNonBlank(
                        groupDisplayName(
                                luckPerms,
                                primaryGroup
                        ),
                        friendlyName(
                                primaryGroup
                        )
                );

        String webPrefix =
                TextColor.strip(prefix)
                        .trim();

        String color =
                firstNonBlank(
                        extractColor(prefix),
                        DEFAULT_COLOR
                );

        int weight = meta.getWeight();

        if (weight == 0
                && luckPerms != null) {
            Group group =
                    luckPerms
                            .getGroupManager()
                            .getGroup(
                                    primaryGroup
                            );

            if (group != null) {
                weight = group
                        .getWeight()
                        .orElse(0);
            }
        }

        return new DisplayRank(
                primaryGroup,
                publicName,
                prefix,
                webPrefix,
                color,
                weight
        );
    }

    /**
     * Hot-path prefix lookup for chat and nametags.
     * <p>
     * This intentionally avoids constructing a complete DisplayRank or
     * querying group display metadata when the caller only needs LuckPerms'
     * already-cached effective prefix.
     */
    public static String prefix(
            OfflinePlayer player
    ) {
        if (player == null) {
            return "";
        }

        LuckPerms luckPerms = luckPerms();

        if (luckPerms == null) {
            return "";
        }

        User user = user(
                luckPerms,
                player
        );

        if (user == null) {
            return "";
        }

        return safe(
                user.getCachedData()
                        .getMetaData()
                        .getPrefix()
        );
    }

    private static User user(
            LuckPerms luckPerms,
            OfflinePlayer player
    ) {
        if (player instanceof Player online
                && online.isOnline()) {
            return luckPerms
                    .getPlayerAdapter(
                            Player.class
                    )
                    .getUser(online);
        }

        return luckPerms
                .getUserManager()
                .getUser(
                        player.getUniqueId()
                );
    }

    private static String groupDisplayName(
            LuckPerms luckPerms,
            String groupName
    ) {
        if (luckPerms == null
                || groupName.isBlank()) {
            return "";
        }

        Group group =
                luckPerms
                        .getGroupManager()
                        .getGroup(groupName);

        if (group == null) {
            return "";
        }

        String displayName =
                group.getDisplayName();

        return displayName == null
                ? ""
                : displayName.trim();
    }

    private static String normalizeGroup(
            String value
    ) {
        String normalized =
                safe(value)
                        .trim()
                        .toLowerCase(
                                Locale.ROOT
                        );

        return normalized.isBlank()
                ? "default"
                : normalized;
    }

    private static String friendlyName(
            String groupName
    ) {
        String source =
                normalizeGroup(groupName)
                        .replace('_', ' ')
                        .replace('-', ' ')
                        .trim();

        if (source.isBlank()) {
            return "Default";
        }

        StringBuilder output =
                new StringBuilder(
                        source.length()
                );
        boolean capitalize = true;

        for (char character
                : source.toCharArray()) {
            if (Character
                    .isWhitespace(
                            character
                    )) {
                output.append(character);
                capitalize = true;
                continue;
            }

            output.append(
                    capitalize
                            ? Character.toUpperCase(
                                    character
                            )
                            : character
            );
            capitalize = false;
        }

        return output.toString();
    }

    private static String extractColor(
            String prefix
    ) {
        if (prefix == null
                || prefix.isBlank()) {
            return "";
        }

        Matcher directHex =
                DIRECT_HEX.matcher(prefix);

        if (directHex.find()) {
            return "#"
                    + directHex
                    .group(1)
                    .toLowerCase(
                            Locale.ROOT
                    );
        }

        Matcher sectionHex =
                SECTION_HEX.matcher(
                        TextColor.color(
                                prefix
                        )
                );

        if (sectionHex.find()) {
            return "#"
                    + (
                    sectionHex.group(1)
                            + sectionHex.group(2)
                            + sectionHex.group(3)
                            + sectionHex.group(4)
                            + sectionHex.group(5)
                            + sectionHex.group(6)
            ).toLowerCase(
                    Locale.ROOT
            );
        }

        Matcher legacy =
                LEGACY_COLOR.matcher(
                        prefix
                );
        String lastCode = null;

        while (legacy.find()) {
            lastCode = legacy
                    .group(1)
                    .toLowerCase(
                            Locale.ROOT
                    );
        }

        return lastCode == null
                ? ""
                : legacyHex(
                        lastCode.charAt(0)
                );
    }

    private static String legacyHex(
            char code
    ) {
        return switch (
                Character.toLowerCase(code)
        ) {
            case '0' -> "#000000";
            case '1' -> "#0000aa";
            case '2' -> "#00aa00";
            case '3' -> "#00aaaa";
            case '4' -> "#aa0000";
            case '5' -> "#aa00aa";
            case '6' -> "#ffaa00";
            case '7' -> "#aaaaaa";
            case '8' -> "#555555";
            case '9' -> "#5555ff";
            case 'a' -> "#55ff55";
            case 'b' -> "#55ffff";
            case 'c' -> "#ff5555";
            case 'd' -> "#ff55ff";
            case 'e' -> "#ffff55";
            case 'f' -> "#ffffff";
            default -> "";
        };
    }

    private static String firstNonBlank(
            String... values
    ) {
        if (values == null) {
            return "";
        }

        for (String value : values) {
            if (value != null
                    && !value.isBlank()) {
                return value.trim();
            }
        }

        return "";
    }

    private static String safe(
            String value
    ) {
        return value == null
                ? ""
                : value;
    }

    private static LuckPerms luckPerms() {
        LuckPerms current =
                cachedLuckPerms;

        if (current != null) {
            return current;
        }

        RegisteredServiceProvider<LuckPerms>
                registration =
                Bukkit.getServicesManager()
                        .getRegistration(
                                LuckPerms.class
                        );

        if (registration == null) {
            return null;
        }

        current =
                registration.getProvider();
        cachedLuckPerms = current;
        return current;
    }

    public record DisplayRank(
            String key,
            String name,
            String prefix,
            String webPrefix,
            String color,
            int weight
    ) {
    }
}
