package net.mineacle.core.common.player;

import me.clip.placeholderapi.PlaceholderAPI;
import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.chat.service.NicknameService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class DisplayNames {

    private DisplayNames() {
    }

    public static String username(OfflinePlayer player) {
        if (player == null) {
            return "";
        }

        String name = player.getName();

        return name == null || name.isBlank()
                ? player.getUniqueId().toString()
                : name;
    }

    public static String displayName(OfflinePlayer player) {
        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            String displayName = service.displayName(player);

            if (displayName != null && !displayName.isBlank()) {
                return displayName;
            }
        }

        return username(player);
    }

    public static String nickname(OfflinePlayer player) {
        NicknameService service = ChatModule.nicknameService();

        if (service == null) {
            return "";
        }

        String nickname = service.nickname(player);
        return nickname == null ? "" : nickname;
    }

    /**
     * Public Mineacle player names always use the neutral name color.
     */
    public static String nameColor(OfflinePlayer player) {
        return "&#bbbbbb";
    }

    public static String coloredDisplayName(
            OfflinePlayer player
    ) {
        return "&#bbbbbb" + displayName(player);
    }

    /**
     * Rank prefixes retain their configured styling. Exactly one visible
     * space is inserted before the Mineacle display name.
     */
    public static String prefixedDisplayName(
            OfflinePlayer player
    ) {
        return luckPermsPrefixWithSpace(player)
                + "&#bbbbbb"
                + displayName(player);
    }

    public static String commandDisplayName(
            OfflinePlayer player
    ) {
        return displayName(player);
    }

    public static Player resolveOnline(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String raw = input.trim();
        Player exactUsername = Bukkit.getPlayerExact(raw);

        if (exactUsername != null) {
            return exactUsername;
        }

        String normalized = normalize(raw);

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (normalize(username(online)).equals(normalized)
                    || normalize(displayName(online))
                    .equals(normalized)
                    || normalize(nickname(online))
                    .equals(normalized)) {
                return online;
            }
        }

        return null;
    }

    public static OfflinePlayer resolveOffline(String input) {
        Player online = resolveOnline(input);

        if (online != null) {
            return online;
        }

        if (input == null || input.isBlank()) {
            return null;
        }

        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            OfflinePlayer byNickname =
                    service.findByNickname(input);

            if (byNickname != null) {
                return byNickname;
            }
        }

        return Bukkit.getOfflinePlayer(input.trim());
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
                || normalize(username(player)).startsWith(normalized)
                || normalize(displayName(player))
                .startsWith(normalized)
                || normalize(nickname(player))
                .startsWith(normalized);
    }

    public static String luckPermsPrefix(
            OfflinePlayer player
    ) {
        if (player == null
                || Bukkit.getPluginManager()
                .getPlugin("PlaceholderAPI") == null) {
            return "";
        }

        try {
            String placeholder = "%luckperms_prefix%";
            String parsed = PlaceholderAPI.setPlaceholders(
                    player,
                    placeholder
            );

            if (parsed == null
                    || parsed.isBlank()
                    || parsed.equalsIgnoreCase(placeholder)
                    || parsed.contains(placeholder)) {
                return "";
            }

            /*
             * Mineacle rank prefixes always end with exactly one visible
             * separator before the display name. This also repairs console
             * formatting paths that call luckPermsPrefix(...) directly.
             */
            return parsed.replaceFirst("\\s+$", "") + " ";
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static String luckPermsPrefixWithSpace(
            OfflinePlayer player
    ) {
        return luckPermsPrefix(player);
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }

        String cleaned = TextColor.strip(input).trim();
        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            String prefix = service.prefix();

            if (prefix != null
                    && !prefix.isBlank()
                    && cleaned.startsWith(prefix)) {
                cleaned = cleaned.substring(prefix.length());
            }
        } else if (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }

        return cleaned.toLowerCase(Locale.ROOT);
    }
}
