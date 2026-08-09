package net.mineacle.core.common.player;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.query.QueryOptions;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class RankDisplayResolver {

    private static final DisplayRank DEFAULT =
            new DisplayRank(
                    "default",
                    "Member",
                    "",
                    0
            );

    private static volatile LuckPerms cachedLuckPerms;

    private RankDisplayResolver() {
    }

    public static DisplayRank resolve(
            OfflinePlayer player
    ) {
        if (player == null) {
            return DEFAULT;
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

        return resolve(user);
    }

    public static String prefix(
            OfflinePlayer player
    ) {
        return resolve(player).prefix();
    }

    private static DisplayRank resolve(User user) {
        if (user == null) {
            return DEFAULT;
        }

        QueryOptions queryOptions =
                QueryOptions.nonContextual();
        Map<String, Group> groups =
                new LinkedHashMap<>();

        for (Group group :
                user.getInheritedGroups(queryOptions)) {
            if (group == null || group.getName().isBlank()) {
                continue;
            }

            groups.put(
                    group.getName()
                            .trim()
                            .toLowerCase(Locale.ROOT),
                    group
            );
        }

        DisplayRank best = DEFAULT;

        best = higher(
                best,
                candidate(
                        groups,
                        "admin",
                        "admin",
                        "Admin",
                        "&#ff5555Admin"
                )
        );

        if (groups.containsKey("plus")) {
            best = higher(
                    best,
                    candidate(
                            groups,
                            "media",
                            "media",
                            "Media +",
                            "&#55ffff📹&#ff55ff+"
                    )
            );

            best = higher(
                    best,
                    candidate(
                            groups,
                            "plus",
                            "plus",
                            "Mineacle +",
                            "&#ff55ff+"
                    )
            );
        }

        return best;
    }

    private static DisplayRank candidate(
            Map<String, Group> groups,
            String groupName,
            String key,
            String name,
            String prefix
    ) {
        Group group = groups.get(groupName);

        if (group == null) {
            return null;
        }

        return new DisplayRank(
                key,
                name,
                prefix,
                group.getWeight().orElse(0)
        );
    }

    private static DisplayRank higher(
            DisplayRank current,
            DisplayRank candidate
    ) {
        if (candidate == null
                || candidate.weight() <= current.weight()) {
            return current;
        }

        return candidate;
    }

    private static LuckPerms luckPerms() {
        LuckPerms current = cachedLuckPerms;

        if (current != null) {
            return current;
        }

        RegisteredServiceProvider<LuckPerms> registration =
                Bukkit.getServicesManager()
                        .getRegistration(LuckPerms.class);

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
            int weight
    ) {
    }
}
