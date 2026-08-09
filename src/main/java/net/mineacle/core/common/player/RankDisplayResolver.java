package net.mineacle.core.common.player;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class RankDisplayResolver {

    private static final DisplayRank DEFAULT =
            new DisplayRank(
                    "default",
                    "Member",
                    "",
                    "",
                    "#bbbbbb",
                    0
            );

    private static final ConcurrentMap<UUID, DisplayRank>
            CACHE = new ConcurrentHashMap<>();

    private static volatile LuckPerms cachedLuckPerms;

    private RankDisplayResolver() {
    }

    public static DisplayRank defaultRank() {
        return DEFAULT;
    }

    public static DisplayRank resolve(
            OfflinePlayer player
    ) {
        if (player == null) {
            return DEFAULT;
        }

        UUID playerId = player.getUniqueId();
        DisplayRank cached = CACHE.get(playerId);

        if (cached != null) {
            return cached;
        }

        LuckPerms luckPerms = luckPerms();

        if (luckPerms == null) {
            return DEFAULT;
        }

        User user;

        if (player instanceof Player online
                && online.isOnline()) {
            user = luckPerms
                    .getPlayerAdapter(Player.class)
                    .getUser(online);
        } else {
            user = luckPerms
                    .getUserManager()
                    .getUser(player.getUniqueId());
        }

        return resolveUser(user);
    }

    public static DisplayRank resolveUser(User user) {
        if (user == null) {
            return DEFAULT;
        }

        Group admin = null;
        Group media = null;
        Group plus = null;
        QueryOptions queryOptions =
                QueryOptions.nonContextual();

        for (Group group :
                user.getInheritedGroups(queryOptions)) {
            String groupName = group.getName()
                    .trim()
                    .toLowerCase(Locale.ROOT);

            switch (groupName) {
                case "admin" -> admin = group;
                case "media" -> media = group;
                case "plus" -> plus = group;
                default -> {
                }
            }
        }

        DisplayRank best = DEFAULT;

        best = higher(
                best,
                candidate(
                        admin,
                        "admin",
                        "Admin",
                        "&#ff5555Admin",
                        "Admin",
                        "#ff5555"
                )
        );

        if (plus != null) {
            best = higher(
                    best,
                    candidate(
                            media,
                            "media",
                            "Media +",
                            "&#B078FF📹&#8436FE+",
                            "Media +",
                            "#B078FF"
                    )
            );

            best = higher(
                    best,
                    candidate(
                            plus,
                            "plus",
                            "Mineacle +",
                            "&#8436FE+",
                            "+",
                            "#8436FE"
                    )
            );
        }

        CACHE.put(
                user.getUniqueId(),
                best
        );
        return best;
    }

    public static void clearCache() {
        CACHE.clear();
        cachedLuckPerms = null;
    }

    public static String prefix(
            OfflinePlayer player
    ) {
        return resolve(player).prefix();
    }

    private static DisplayRank candidate(
            Group group,
            String key,
            String name,
            String prefix,
            String webPrefix,
            String color
    ) {
        if (group == null) {
            return null;
        }

        return new DisplayRank(
                key,
                name,
                prefix,
                webPrefix,
                color,
                group.getWeight().orElse(0)
        );
    }

    private static DisplayRank higher(
            DisplayRank current,
            DisplayRank candidate
    ) {
        if (candidate == null
                || candidate.weight()
                <= current.weight()) {
            return current;
        }

        return candidate;
    }

    private static LuckPerms luckPerms() {
        LuckPerms current = cachedLuckPerms;

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

        current = registration.getProvider();
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
