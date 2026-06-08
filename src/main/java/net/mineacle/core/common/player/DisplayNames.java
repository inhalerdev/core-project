package net.mineacle.core.common.player;

import me.clip.placeholderapi.PlaceholderAPI;
import net.mineacle.core.Core;
import net.mineacle.core.chat.ChatModule;
import net.mineacle.core.chat.service.NicknameService;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.regex.Pattern;

public final class DisplayNames {

    private static final Pattern HEX_ONLY = Pattern.compile("(?i)^#[a-f0-9]{6}$");
    private static final Pattern AMP_HEX = Pattern.compile("(?i)^&#[a-f0-9]{6}$");

    private DisplayNames() {
    }

    public static String username(OfflinePlayer player) {
        if (player == null) {
            return "";
        }

        String name = player.getName();
        return name == null || name.isBlank() ? player.getUniqueId().toString() : name;
    }

    public static String displayName(OfflinePlayer player) {
        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            return service.displayName(player);
        }

        return username(player);
    }

    public static String nickname(OfflinePlayer player) {
        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            return service.nickname(player);
        }

        return "";
    }

    public static String nameColor(OfflinePlayer player) {
        Core core = Core.instance();

        String color;
        if (player != null && player.isOp()) {
            color = core == null ? "#ff55ff" : core.getConfig().getString("nickname.op-name-color", "#ff55ff");
        } else {
            color = core == null ? "#bbbbbb" : core.getConfig().getString("nickname.default-name-color", "#bbbbbb");
        }

        return normalizeColor(color);
    }

    public static String coloredDisplayName(OfflinePlayer player) {
        return nameColor(player) + displayName(player);
    }

    public static String prefixedDisplayName(OfflinePlayer player) {
        return luckPermsPrefix(player) + coloredDisplayName(player);
    }

    public static String commandDisplayName(OfflinePlayer player) {
        return displayName(player);
    }

    public static Player resolveOnline(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        String raw = input.trim();
        String normalized = normalize(raw);

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (normalize(username(online)).equals(normalized)) {
                return online;
            }

            if (normalize(displayName(online)).equals(normalized)) {
                return online;
            }

            String nickname = nickname(online);

            if (!nickname.isBlank() && normalize(nickname).equals(normalized)) {
                return online;
            }
        }

        return Bukkit.getPlayerExact(raw);
    }

    public static OfflinePlayer resolveOffline(String input) {
        Player online = resolveOnline(input);

        if (online != null) {
            return online;
        }

        NicknameService service = ChatModule.nicknameService();

        if (service != null) {
            OfflinePlayer byNick = service.findByNickname(input);

            if (byNick != null) {
                return byNick;
            }
        }

        return Bukkit.getOfflinePlayer(input);
    }

    public static boolean startsWithDisplay(Player player, String partial) {
        if (player == null) {
            return false;
        }

        String normalized = normalize(partial);

        return normalize(username(player)).startsWith(normalized)
                || normalize(displayName(player)).startsWith(normalized)
                || normalize(nickname(player)).startsWith(normalized);
    }

    public static String luckPermsPrefix(OfflinePlayer player) {
        if (player == null) {
            return "";
        }

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            return "";
        }

        try {
            String parsed = PlaceholderAPI.setPlaceholders(player, "%luckperms_prefix%");

            if (parsed == null || parsed.isBlank() || parsed.equalsIgnoreCase("%luckperms_prefix%")) {
                return "";
            }

            return parsed;
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String normalizeColor(String input) {
        if (input == null || input.isBlank()) {
            return "&#bbbbbb";
        }

        String cleaned = input.trim();

        if (AMP_HEX.matcher(cleaned).matches()) {
            return cleaned;
        }

        if (HEX_ONLY.matcher(cleaned).matches()) {
            return "&" + cleaned;
        }

        return cleaned;
    }

    private static String normalize(String input) {
        if (input == null) {
            return "";
        }

        String cleaned = input.trim();

        if (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }

        return cleaned.toLowerCase(Locale.ROOT);
    }
}
