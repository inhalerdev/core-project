package net.mineacle.core.homes.service;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("unused")
public final class HomeGuiState implements Listener {

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    private final Map<UUID, PersonalDeleteState> personalDeletes =
            new HashMap<>();
    private final Map<UUID, TeamDeleteState> teamDeletes =
            new HashMap<>();

    public void startPersonal(Player player, int homeId) {
        personalDeletes.put(
                player.getUniqueId(),
                new PersonalDeleteState(homeId, 0, 0L)
        );
    }

    public PersonalDeleteState personal(Player player) {
        return personalDeletes.get(player.getUniqueId());
    }

    public long armPersonal(
            Player player,
            int homeId,
            int timeoutSeconds
    ) {
        long expiresAtNanos = expiresAt(timeoutSeconds);
        personalDeletes.put(
                player.getUniqueId(),
                new PersonalDeleteState(
                        homeId,
                        homeId,
                        expiresAtNanos
                )
        );
        return expiresAtNanos;
    }

    public boolean personalReady(Player player, int homeId) {
        PersonalDeleteState state = personal(player);
        return state != null
                && state.homeId() == homeId
                && state.confirmedHomeId() == homeId
                && state.expiresAtNanos() > System.nanoTime();
    }

    public boolean personalConfirmationMatches(
            Player player,
            int homeId,
            long expiresAtNanos
    ) {
        PersonalDeleteState state = personal(player);
        return state != null
                && state.homeId() == homeId
                && state.confirmedHomeId() == homeId
                && state.expiresAtNanos() == expiresAtNanos;
    }

    public void clearPersonal(Player player) {
        personalDeletes.remove(player.getUniqueId());
    }

    public void startTeam(Player player, String teamId) {
        teamDeletes.put(
                player.getUniqueId(),
                new TeamDeleteState(teamId, false, 0L)
        );
    }

    public TeamDeleteState team(Player player) {
        return teamDeletes.get(player.getUniqueId());
    }

    public long armTeam(
            Player player,
            String teamId,
            int timeoutSeconds
    ) {
        long expiresAtNanos = expiresAt(timeoutSeconds);
        teamDeletes.put(
                player.getUniqueId(),
                new TeamDeleteState(
                        teamId,
                        true,
                        expiresAtNanos
                )
        );
        return expiresAtNanos;
    }

    public boolean teamReady(Player player, String teamId) {
        TeamDeleteState state = team(player);
        return state != null
                && state.confirmed()
                && state.teamId().equals(teamId)
                && state.expiresAtNanos() > System.nanoTime();
    }

    public boolean teamConfirmationMatches(
            Player player,
            String teamId,
            long expiresAtNanos
    ) {
        TeamDeleteState state = team(player);
        return state != null
                && state.confirmed()
                && state.teamId().equals(teamId)
                && state.expiresAtNanos() == expiresAtNanos;
    }

    public void clearTeam(Player player) {
        teamDeletes.remove(player.getUniqueId());
    }

    public void clear(Player player) {
        clearPersonal(player);
        clearTeam(player);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    private long expiresAt(int timeoutSeconds) {
        return System.nanoTime()
                + Math.max(1, timeoutSeconds)
                * NANOS_PER_SECOND;
    }

    public record PersonalDeleteState(
            int homeId,
            int confirmedHomeId,
            long expiresAtNanos
    ) {
    }

    public record TeamDeleteState(
            String teamId,
            boolean confirmed,
            long expiresAtNanos
    ) {
    }
}
