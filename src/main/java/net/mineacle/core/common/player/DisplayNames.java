package net.mineacle.core.common.player;

import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class DisplayNames {

    private static final String BODY = "&#bbbbbb";
    private static final String SECONDARY = "&#B078FF";

    private DisplayNames() {
    }

    public static String username(
            OfflinePlayer player
    ) {
        if (player == null) {
            return "";
        }

        String name = player.getName();

        return name == null || name.isBlank()
                ? player.getUniqueId().toString()
                : name;
    }

    /**
     * Public Mineacle identity.
     * <p>
     * NicknameService returns the nickname body exactly as the player set it.
     * There is no Mineacle "." marker in front of nicknames.
     */
    public static String displayName(
            OfflinePlayer player
    ) {
        NicknameService service =
                ChatModule.nicknameService();

        if (service != null) {
            String displayName =
                    service.displayName(player);

            if (displayName != null
                    && !displayName.isBlank()) {
                return displayName;
            }
        }

        return username(player);
    }

    public static String nickname(
            OfflinePlayer player
    ) {
        NicknameService service =
                ChatModule.nicknameService();

        if (service == null) {
            return "";
        }

        String nickname =
                service.nickname(player);

        return nickname == null
                ? ""
                : nickname;
    }

    /**
     * Player-name color only. Rank/prefix color remains entirely LuckPerms.
     */
    public static String nameColor(
            OfflinePlayer player
    ) {
        return player != null && player.isOp()
                ? SECONDARY
                : BODY;
    }

    public static String coloredDisplayName(
            OfflinePlayer player
    ) {
        return nameColor(player)
                + displayName(player);
    }

    /**
     * LuckPerms owns prefix formatting; Mineacle only combines the resolved
     * prefix with Mineacle's public display name.
     */
    public static String prefixedDisplayName(
            OfflinePlayer player
    ) {
        return luckPermsPrefixWithSpace(player)
                + coloredDisplayName(player);
    }

    public static String commandDisplayName(
            OfflinePlayer player
    ) {
        return displayName(player);
    }

    public static Player resolveOnline(
            String input
    ) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String raw = input.trim();
        Player exactUsername =
                Bukkit.getPlayerExact(raw);

        if (exactUsername != null) {
            return exactUsername;
        }

        String normalized = normalize(raw);

        for (Player online
                : Bukkit.getOnlinePlayers()) {
            if (normalize(
                    username(online)
            ).equals(normalized)
                    || normalize(
                    displayName(online)
            ).equals(normalized)
                    || normalize(
                    nickname(online)
            ).equals(normalized)) {
                return online;
            }
        }

        return null;
    }

    public static OfflinePlayer resolveOffline(
            String input
    ) {
        Player online = resolveOnline(input);

        if (online != null) {
            return online;
        }

        if (input == null || input.isBlank()) {
            return null;
        }

        NicknameService service =
                ChatModule.nicknameService();

        if (service != null) {
            OfflinePlayer byNickname =
                    service.findByNickname(input);

            if (byNickname != null) {
                return byNickname;
            }
        }

        return Bukkit.getOfflinePlayer(
                input.trim()
        );
    }

    public static boolean startsWithDisplay(
            Player player,
            String partial
    ) {
        if (player == null) {
            return false;
        }

        String normalized = normalize(partial);

        return normalized.isEmpty()
                || normalize(
                username(player)
        ).startsWith(normalized)
                || normalize(
                displayName(player)
        ).startsWith(normalized)
                || normalize(
                nickname(player)
        ).startsWith(normalized);
    }

    /**
     * Effective prefix from LuckPerms' current context/prefix stack.
     */
    public static String luckPermsPrefix(
            OfflinePlayer player
    ) {
        String parsed =
                RankDisplayResolver.prefix(player);

        if (parsed.isBlank()) {
            return "";
        }

        return parsed.replaceFirst(
                "\\s+$",
                ""
        ) + " ";
    }

    public static String luckPermsPrefixWithSpace(
            OfflinePlayer player
    ) {
        return luckPermsPrefix(player);
    }

    private static String normalize(
            String input
    ) {
        if (input == null) {
            return "";
        }

        String cleaned =
                TextColor.strip(input).trim();
        NicknameService service =
                ChatModule.nicknameService();

        /*
         * Keep accepting the old configured nickname marker as input for
         * backwards compatibility. It is never rendered publicly.
         */
        if (service != null) {
            String legacyPrefix =
                    service.prefix();

            if (!legacyPrefix.isBlank()
                    && cleaned.startsWith(
                    legacyPrefix
            )) {
                cleaned = cleaned.substring(
                        legacyPrefix.length()
                );
            }
        } else if (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }

        return cleaned.toLowerCase(
                Locale.ROOT
        );
    }
}
