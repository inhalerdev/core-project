package net.mineacle.core.collision;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PlayerCollisionService {

    private static final String TEAM_PREFIX = "mn_";

    private final Core core;
    private final Set<UUID> warnedForeignTeams =
            new HashSet<>();

    private final Settings settings;

    public PlayerCollisionService(Core core) {
        this.core = core;
        this.settings = loadSettings();
    }

    public Team team(Player player) {
        if (player == null || !player.isOnline()) {
            return null;
        }

        Team team = ensureTeam(player);

        applyCollisionRule(
                team,
                player.getWorld()
        );

        return team;
    }

    public void apply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        apply(
                player,
                player.getWorld()
        );
    }

    public void apply(
            Player player,
            World world
    ) {
        if (player == null
                || !player.isOnline()
                || world == null) {
            return;
        }

        Team team = ensureTeam(player);

        applyCollisionRule(team, world);
    }

    public void scheduleApply(Player player) {
        if (player == null) {
            return;
        }

        apply(player);

        for (long delay : settings.reapplyDelays()) {
            core.getServer()
                    .getScheduler()
                    .runTaskLater(
                            core,
                            () -> {
                                if (core.isEnabled()
                                        && player.isOnline()) {
                                    apply(player);
                                }
                            },
                            delay
                    );
        }
    }

    public void applyAll() {
        for (Player player :
                core.getServer().getOnlinePlayers()) {
            scheduleApply(player);
        }
    }

    public void applyAllNow() {
        for (Player player :
                core.getServer().getOnlinePlayers()) {
            apply(player);
        }

        cleanupTeams();
    }

    public void release(Player player) {
        if (player == null) {
            return;
        }

        Scoreboard scoreboard = mainScoreboard();
        String entry = player.getName();

        for (Team team : Set.copyOf(
                scoreboard.getTeams()
        )) {
            if (!owned(team) || !team.hasEntry(entry)) {
                continue;
            }

            team.removeEntry(entry);

            if (team.getEntries().isEmpty()) {
                team.unregister();
            }
        }

        warnedForeignTeams.remove(
                player.getUniqueId()
        );
    }

    public void cleanupTeams() {
        Scoreboard scoreboard = mainScoreboard();
        Set<String> onlineNames = new HashSet<>();

        for (Player player : Bukkit.getOnlinePlayers()) {
            onlineNames.add(player.getName());
        }

        for (Team team : Set.copyOf(
                scoreboard.getTeams()
        )) {
            if (!owned(team)) {
                continue;
            }

            for (String entry : Set.copyOf(
                    team.getEntries()
            )) {
                if (!onlineNames.contains(entry)) {
                    team.removeEntry(entry);
                }
            }

            if (team.getEntries().isEmpty()) {
                team.unregister();
            }
        }
    }

    public void restoreAll() {
        Scoreboard scoreboard = mainScoreboard();

        for (Team team : Set.copyOf(
                scoreboard.getTeams()
        )) {
            if (owned(team)) {
                team.unregister();
            }
        }

        warnedForeignTeams.clear();
    }

    private Team ensureTeam(Player player) {
        Scoreboard scoreboard = mainScoreboard();
        String entry = player.getName();
        String expectedName = teamName(player);
        Team current = scoreboard.getEntryTeam(entry);

        if (current != null
                && !owned(current)
                && warnedForeignTeams.add(
                player.getUniqueId()
        )) {
            core.getLogger().warning(
                    "Mineacle moved "
                            + entry
                            + " out of scoreboard team "
                            + current.getName()
                            + " — disable external player-team "
                            + "management in TAB or other plugins"
            );
        }

        Team expected = scoreboard.getTeam(expectedName);

        if (expected == null) {
            expected = scoreboard.registerNewTeam(
                    expectedName
            );
        }

        removeFromOtherOwnedTeams(
                player,
                scoreboard,
                expectedName
        );

        for (String staleEntry : Set.copyOf(
                expected.getEntries()
        )) {
            if (!staleEntry.equals(entry)) {
                expected.removeEntry(staleEntry);
            }
        }

        if (!expected.hasEntry(entry)) {
            expected.addEntry(entry);
        }

        expected.setAllowFriendlyFire(true);
        expected.setCanSeeFriendlyInvisibles(false);

        return expected;
    }

    private void applyCollisionRule(
            Team team,
            World world
    ) {
        boolean collisionEnabled =
                collisionEnabled(world);

        Team.OptionStatus status =
                collisionEnabled
                        ? Team.OptionStatus.ALWAYS
                        : Team.OptionStatus.NEVER;

        if (team.getOption(
                Team.Option.COLLISION_RULE
        ) != status) {
            team.setOption(
                    Team.Option.COLLISION_RULE,
                    status
            );
        }
    }

    private boolean collisionEnabled(World world) {
        if (world == null) {
            return true;
        }

        return !settings.enabled()
                || !settings.disabledWorlds()
                .contains(
                        world.getName()
                                .toLowerCase(Locale.ROOT)
                );
    }

    private void removeFromOtherOwnedTeams(
            Player player,
            Scoreboard scoreboard,
            String expectedName
    ) {
        String entry = player.getName();

        for (Team team : Set.copyOf(
                scoreboard.getTeams()
        )) {
            if (!owned(team)
                    || team.getName()
                    .equals(expectedName)
                    || !team.hasEntry(entry)) {
                continue;
            }

            team.removeEntry(entry);

            if (team.getEntries().isEmpty()) {
                team.unregister();
            }
        }
    }

    private Settings loadSettings() {
        boolean enabled = core.getConfig()
                .getBoolean(
                        "player-collision.enabled",
                        true
                );

        Set<String> disabledWorlds =
                core.getConfig()
                        .getStringList(
                                "player-collision.disabled-worlds"
                        )
                        .stream()
                        .filter(value ->
                                !value.isBlank())
                        .map(value ->
                                value.trim()
                                        .toLowerCase(Locale.ROOT))
                        .collect(
                                Collectors.toUnmodifiableSet()
                        );

        List<Long> reapplyDelays =
                core.getConfig()
                        .getLongList(
                                "player-collision.reapply-delay-ticks"
                        )
                        .stream()
                        .filter(value ->
                                value >= 0L)
                        .distinct()
                        .sorted()
                        .toList();

        if (reapplyDelays.isEmpty()) {
            reapplyDelays = List.of(1L, 20L);
        }

        return new Settings(
                enabled,
                disabledWorlds,
                reapplyDelays
        );
    }

    private String teamName(Player player) {
        String compact = player.getUniqueId()
                .toString()
                .replace("-", "");

        return TEAM_PREFIX
                + compact.substring(0, 7)
                + compact.substring(
                compact.length() - 6
        );
    }

    private boolean owned(Team team) {
        return team != null
                && team.getName()
                .startsWith(TEAM_PREFIX);
    }

    private Scoreboard mainScoreboard() {
        return Bukkit.getScoreboardManager()
                .getMainScoreboard();
    }

    private record Settings(
            boolean enabled,
            Set<String> disabledWorlds,
            List<Long> reapplyDelays
    ) {
    }
}
