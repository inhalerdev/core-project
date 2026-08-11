package net.mineacle.core.common.player;

import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class DisplayNames {

    private static final String BODY =
            "&#bbbbbb";
    private static final String SECONDARY =
            "&#B078FF";

    private static final int BODY_RGB =
            0xBBBBBB;
    private static final int SECONDARY_RGB =
            0xB078FF;

    private DisplayNames() {
    }

    public static String username(
            OfflinePlayer player
    ) {
        if (player == null) {
            return "";
        }

        String name = player.getName();

        return name == null
                || name.isBlank()
                ? player.getUniqueId()
                .toString()
                : name;
    }

    /**
     * Public Mineacle identity: nickname when set, otherwise username.
     */
    public static String displayName(
            OfflinePlayer player
    ) {
        NicknameService service =
                ChatModule.nicknameService();

        if (service == null) {
            return username(player);
        }

        String nickname =
                service.nickname(player);

        return nickname.isBlank()
                ? username(player)
                : nickname;
    }

    public static String nickname(
            OfflinePlayer player
    ) {
        NicknameService service =
                ChatModule.nicknameService();

        if (service == null) {
            return "";
        }

        return service.nickname(player);
    }

    /**
     * Player-name color only. LuckPerms exclusively owns rank/prefix color.
     * OP is intentionally a Mineacle display-style exception, not a rank.
     */
    public static String nameColor(
            OfflinePlayer player
    ) {
        return player != null
                && player.isOp()
                ? SECONDARY
                : BODY;
    }

    public static int nameColorRgb(
            OfflinePlayer player
    ) {
        return player != null
                && player.isOp()
                ? SECONDARY_RGB
                : BODY_RGB;
    }

    public static String coloredDisplayName(
            OfflinePlayer player
    ) {
        return nameColor(player)
                + displayName(player);
    }

    public static String prefixedDisplayName(
            OfflinePlayer player
    ) {
        return luckPermsPrefix(player)
                + coloredDisplayName(
                        player
                );
    }

    public static String commandDisplayName(
            OfflinePlayer player
    ) {
        return displayName(player);
    }

    public static Player resolveOnline(
            String input
    ) {
        if (input == null
                || input.isBlank()) {
            return null;
        }

        String raw = input.trim();
        Player exactUsername =
                Bukkit.getPlayerExact(raw);

        if (exactUsername != null) {
            return exactUsername;
        }

        String normalized =
                normalize(raw);
        NicknameService service =
                ChatModule.nicknameService();

        for (Player online
                : Bukkit.getOnlinePlayers()) {
            if (normalize(
                    username(online)
            ).equals(normalized)) {
                return online;
            }

            if (service != null
                    && normalize(
                    service.nickname(
                            online
                    )
            ).equals(normalized)) {
                return online;
            }
        }

        return null;
    }

    public static OfflinePlayer resolveOffline(
            String input
    ) {
        Player online =
                resolveOnline(input);

        if (online != null) {
            return online;
        }

        if (input == null
                || input.isBlank()) {
            return null;
        }

        NicknameService service =
                ChatModule.nicknameService();

        if (service != null) {
            OfflinePlayer byNickname =
                    service.findByNickname(
                            input
                    );

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

        String normalized =
                normalize(partial);

        if (normalized.isEmpty()
                || normalize(
                username(player)
        ).startsWith(normalized)) {
            return true;
        }

        NicknameService service =
                ChatModule.nicknameService();

        return service != null
                && normalize(
                service.nickname(player)
        ).startsWith(normalized);
    }

    /**
     * Effective prefix from LuckPerms' current context/prefix stack.
     */
    public static String luckPermsPrefix(
            OfflinePlayer player
    ) {
        String prefix =
                RankDisplayResolver.prefix(
                        player
                ).stripTrailing();

        return prefix.isBlank()
                ? ""
                : prefix + " ";
    }

    private static String normalize(
            String input
    ) {
        if (input == null) {
            return "";
        }

        String cleaned =
                TextColor.strip(input)
                        .trim();

        NicknameService service =
                ChatModule.nicknameService();

        /*
         * Keep accepting the old nickname marker as command/search input for
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
            cleaned =
                    cleaned.substring(1);
        }

        return cleaned.toLowerCase(
                Locale.ROOT
        );
    }
}
