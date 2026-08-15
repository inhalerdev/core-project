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

    /** Public Mineacle identity: nickname when set, otherwise username. */
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
                + coloredDisplayName(player);
    }

    public static String commandDisplayName(
            OfflinePlayer player
    ) {
        return displayName(player);
    }

    /**
     * Resolves an online player by public Mineacle identity only.
     * A nickname replaces the username while it is active. The hidden raw
     * username is therefore never accepted as a public command target. If a
     * legacy data collision ever causes more than one player to share the
     * same normalized public identity, resolution fails closed.
     */
    public static Player resolveOnline(
            String input
    ) {
        if (input == null
                || input.isBlank()) {
            return null;
        }

        String normalized = normalize(input);
        Player match = null;

        for (Player online
                : Bukkit.getOnlinePlayers()) {
            if (!normalize(
                    commandDisplayName(online)
            ).equals(normalized)) {
                continue;
            }

            if (match != null
                    && !match.getUniqueId()
                    .equals(online.getUniqueId())) {
                return null;
            }

            match = online;
        }

        return match;
    }

    /**
     * Resolves an offline-capable public identity without exposing a hidden
     * username. Nicknames resolve by the nickname registry. Raw usernames are
     * accepted only for known players who do not currently have a nickname.
     * Cross-identity collisions fail closed.
     */
    public static OfflinePlayer resolveOffline(
            String input
    ) {
        if (input == null
                || input.isBlank()) {
            return null;
        }

        Player online = resolveOnline(input);

        if (online != null) {
            return online;
        }

        String raw = input.trim();
        String normalized = normalize(raw);
        NicknameService service =
                ChatModule.nicknameService();
        OfflinePlayer nicknameMatch = null;

        if (service != null) {
            OfflinePlayer candidate =
                    service.findByNickname(raw);

            if (candidate != null
                    && normalize(
                    commandDisplayName(candidate)
            ).equals(normalized)) {
                nicknameMatch = candidate;
            }
        }

        OfflinePlayer usernameMatch = null;
        OfflinePlayer candidate =
                Bukkit.getOfflinePlayer(raw);

        if (knownPlayer(candidate)
                && nickname(candidate).isBlank()
                && normalize(
                username(candidate)
        ).equals(normalized)) {
            usernameMatch = candidate;
        }

        if (nicknameMatch != null
                && usernameMatch != null
                && !nicknameMatch.getUniqueId()
                .equals(
                        usernameMatch.getUniqueId()
                )) {
            return null;
        }

        return nicknameMatch != null
                ? nicknameMatch
                : usernameMatch;
    }

    /**
     * Public completion/search predicate. A nickname replaces the username as
     * the player's public identity, so a hidden username must never influence
     * whether that player's nickname appears in tab completion.
     */
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
                commandDisplayName(player)
        ).startsWith(normalized);
    }

    /** Effective prefix from LuckPerms' current context/prefix stack. */
    public static String luckPermsPrefix(
            OfflinePlayer player
    ) {
        String prefix =
                RankDisplayResolver.prefix(player)
                        .stripTrailing();

        return prefix.isBlank()
                ? ""
                : prefix + " ";
    }

    static String normalizePublicName(
            String input
    ) {
        return normalize(input);
    }

    private static boolean knownPlayer(
            OfflinePlayer player
    ) {
        return player != null
                && (player.isOnline()
                || player.hasPlayedBefore());
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
            cleaned = cleaned.substring(1);
        }

        return cleaned.toLowerCase(Locale.ROOT);
    }
}
