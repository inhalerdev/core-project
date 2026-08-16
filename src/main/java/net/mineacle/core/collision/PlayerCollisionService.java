package net.mineacle.core.collision;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.VanishRegistry;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PlayerCollisionService {

    private final Core core;
    private final Settings settings;
    private final Map<TeamState, String> teamNames =
            new EnumMap<>(TeamState.class);
    private final Map<UUID, Boolean> nativeTagHidden =
            new HashMap<>();
    private final Set<UUID> warnedForeignTeams =
            new java.util.HashSet<>();

    public PlayerCollisionService(Core core) {
        this.core = core;
        this.settings = loadSettings();

        teamNames.put(
                TeamState.COLLIDE_VISIBLE,
                "mn_cv"
        );
        teamNames.put(
                TeamState.COLLIDE_HIDDEN,
                "mn_ch"
        );
        teamNames.put(
                TeamState.NO_COLLIDE_VISIBLE,
                "mn_nv"
        );
        teamNames.put(
                TeamState.NO_COLLIDE_HIDDEN,
                "mn_nh"
        );

        ensureAllTeams();
    }

    public void setNativeTagHidden(
            Player player,
            boolean hidden
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID playerId = player.getUniqueId();
        boolean changed;

        if (hidden) {
            changed = !Boolean.TRUE.equals(
                    nativeTagHidden.put(
                            playerId,
                            Boolean.TRUE
                    )
            );
        } else {
            changed = nativeTagHidden.remove(playerId)
                    != null;
        }

        if (changed) {
            apply(player);
        } else {
            ensureMembership(player);
        }
    }

    public void apply(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        ensureMembership(player);
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
        ensureAllTeams();

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

        nativeTagHidden.remove(
                player.getUniqueId()
        );
        warnedForeignTeams.remove(
                player.getUniqueId()
        );

        Scoreboard scoreboard = mainScoreboard();
        Team current = scoreboard.getEntryTeam(
                player.getName()
        );

        if (current != null && owned(current)) {
            current.removeEntry(player.getName());
        }
    }

    public void cleanupTeams() {
        Scoreboard scoreboard = mainScoreboard();
        Set<String> onlineNames =
                core.getServer()
                        .getOnlinePlayers()
                        .stream()
                        .map(Player::getName)
                        .collect(Collectors.toUnmodifiableSet());

        for (TeamState state : TeamState.values()) {
            Team team = ensureTeam(
                    scoreboard,
                    state
            );

            for (String entry :
                    Set.copyOf(team.getEntries())) {
                if (!onlineNames.contains(entry)) {
                    team.removeEntry(entry);
                }
            }
        }
    }

    public void restoreAll() {
        nativeTagHidden.clear();
        warnedForeignTeams.clear();

        Scoreboard scoreboard = mainScoreboard();

        for (String teamName : teamNames.values()) {
            Team team = scoreboard.getTeam(teamName);

            if (team != null) {
                team.unregister();
            }
        }
    }

    private void ensureMembership(Player player) {
        Scoreboard scoreboard = mainScoreboard();
        TeamState state = stateFor(player);
        Team expected = ensureTeam(
                scoreboard,
                state
        );
        String entry = player.getName();
        Team current = scoreboard.getEntryTeam(entry);

        if (current == expected
                && expected.hasEntry(entry)) {
            return;
        }

        if (current != null) {
            if (!owned(current)
                    && warnedForeignTeams.add(
                    player.getUniqueId()
            )) {
                core.getLogger().warning(
                        "Mineacle moved "
                                + entry
                                + " out of scoreboard team "
                                + current.getName()
                                + " — disable external "
                                + "player-team management in "
                                + "TAB or other plugins"
                );
            }

            current.removeEntry(entry);
        }

        expected.addEntry(entry);
    }

    private TeamState stateFor(Player player) {
        boolean collisionEnabled =
                !VanishRegistry.isVanished(
                        player.getUniqueId()
                )
                        && collisionEnabled(
                        player.getWorld()
                );
        boolean hidden =
                Boolean.TRUE.equals(
                        nativeTagHidden.get(
                                player.getUniqueId()
                        )
                );

        if (collisionEnabled) {
            return hidden
                    ? TeamState.COLLIDE_HIDDEN
                    : TeamState.COLLIDE_VISIBLE;
        }

        return hidden
                ? TeamState.NO_COLLIDE_HIDDEN
                : TeamState.NO_COLLIDE_VISIBLE;
    }

    private boolean collisionEnabled(World world) {
        return !settings.enabled()
                || !settings.disabledWorlds()
                .contains(
                        world.getName()
                                .toLowerCase(Locale.ROOT)
                );
    }

    private void ensureAllTeams() {
        Scoreboard scoreboard = mainScoreboard();

        for (TeamState state : TeamState.values()) {
            ensureTeam(scoreboard, state);
        }
    }

    private Team ensureTeam(
            Scoreboard scoreboard,
            TeamState state
    ) {
        String teamName = teamNames.get(state);
        Team team = scoreboard.getTeam(teamName);

        if (team == null) {
            team = scoreboard.registerNewTeam(
                    teamName
            );
        }

        Team.OptionStatus collisionStatus =
                state.collisionEnabled()
                        ? Team.OptionStatus.ALWAYS
                        : Team.OptionStatus.NEVER;
        Team.OptionStatus nametagStatus =
                state.nativeTagHidden()
                        ? Team.OptionStatus.NEVER
                        : Team.OptionStatus.ALWAYS;

        if (team.getOption(
                Team.Option.COLLISION_RULE
        ) != collisionStatus) {
            team.setOption(
                    Team.Option.COLLISION_RULE,
                    collisionStatus
            );
        }

        if (team.getOption(
                Team.Option.NAME_TAG_VISIBILITY
        ) != nametagStatus) {
            team.setOption(
                    Team.Option.NAME_TAG_VISIBILITY,
                    nametagStatus
            );
        }

        team.prefix(
                net.kyori.adventure.text.Component.empty()
        );
        team.suffix(
                net.kyori.adventure.text.Component.empty()
        );
        team.setAllowFriendlyFire(true);
        team.setCanSeeFriendlyInvisibles(false);

        return team;
    }

    private boolean owned(Team team) {
        return teamNames.containsValue(
                team.getName()
        );
    }

    private Scoreboard mainScoreboard() {
        return Bukkit.getScoreboardManager()
                .getMainScoreboard();
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
                        .filter(value -> value >= 0L)
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

    private enum TeamState {
        COLLIDE_VISIBLE(true, false),
        COLLIDE_HIDDEN(true, true),
        NO_COLLIDE_VISIBLE(false, false),
        NO_COLLIDE_HIDDEN(false, true);

        private final boolean collisionEnabled;
        private final boolean nativeTagHidden;

        TeamState(
                boolean collisionEnabled,
                boolean nativeTagHidden
        ) {
            this.collisionEnabled =
                    collisionEnabled;
            this.nativeTagHidden =
                    nativeTagHidden;
        }

        private boolean collisionEnabled() {
            return collisionEnabled;
        }

        private boolean nativeTagHidden() {
            return nativeTagHidden;
        }
    }

    private record Settings(
            boolean enabled,
            Set<String> disabledWorlds,
            List<Long> reapplyDelays
    ) {
    }
}
