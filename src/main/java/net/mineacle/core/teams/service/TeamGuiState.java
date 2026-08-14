package net.mineacle.core.teams.service;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@SuppressWarnings("unused")
public final class TeamGuiState implements Listener {

    private final Map<UUID, Confirmation> confirmations =
            new HashMap<>();

    public boolean ready(
            Player player,
            String token
    ) {
        if (player == null
                || token == null
                || token.isBlank()) {
            return false;
        }

        Confirmation confirmation =
                confirmations.get(
                        player.getUniqueId()
                );

        if (confirmation == null) {
            return false;
        }

        if (confirmation.expiresAtMillis()
                <= System.currentTimeMillis()) {
            confirmations.remove(
                    player.getUniqueId()
            );
            return false;
        }

        return token.equals(
                confirmation.token()
        );
    }

    public long arm(
            Player player,
            String token,
            int timeoutSeconds
    ) {
        if (player == null
                || token == null
                || token.isBlank()) {
            return 0L;
        }

        long expiresAt =
                System.currentTimeMillis()
                        + Math.max(
                        1,
                        timeoutSeconds
                ) * 1000L;

        confirmations.put(
                player.getUniqueId(),
                new Confirmation(
                        token,
                        expiresAt
                )
        );
        return expiresAt;
    }

    public boolean matches(
            Player player,
            String token,
            long expiresAtMillis
    ) {
        if (player == null) {
            return false;
        }

        Confirmation confirmation =
                confirmations.get(
                        player.getUniqueId()
                );

        return confirmation != null
                && token != null
                && token.equals(
                confirmation.token()
        )
                && confirmation.expiresAtMillis()
                == expiresAtMillis;
    }

    public void clear(Player player) {
        if (player != null) {
            confirmations.remove(
                    player.getUniqueId()
            );
        }
    }

    public void clear(UUID playerId) {
        if (playerId != null) {
            confirmations.remove(playerId);
        }
    }

    public void clearAll() {
        confirmations.clear();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    private record Confirmation(
            String token,
            long expiresAtMillis
    ) {
    }
}
