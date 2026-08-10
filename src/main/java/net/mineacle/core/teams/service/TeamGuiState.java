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

    private final Map<UUID, Session> sessions = new HashMap<>();

    public Session session(Player player) {
        return sessions.get(player.getUniqueId());
    }

    public void selectTarget(Player player, UUID targetId) {
        Session current = sessions.getOrDefault(
                player.getUniqueId(),
                Session.EMPTY
        );
        sessions.put(
                player.getUniqueId(),
                current.withTarget(targetId)
        );
    }

    public void beginAction(Player player, String action) {
        beginAction(player, action, null);
    }

    public void beginAction(Player player, String action, UUID targetId) {
        sessions.put(
                player.getUniqueId(),
                new Session(action, targetId, null, 0L)
        );
    }

    public String action(Player player) {
        Session session = session(player);
        return session == null ? null : session.action();
    }

    public UUID target(Player player) {
        Session session = session(player);
        return session == null ? null : session.targetId();
    }

    public boolean isConfirmReady(Player player, String action) {
        Session session = session(player);
        return session != null
                && action != null
                && action.equals(session.confirmAction())
                && session.confirmExpiresAtMillis() > System.currentTimeMillis();
    }

    public long armConfirmation(
            Player player,
            String action,
            int timeoutSeconds
    ) {
        long expiresAt = System.currentTimeMillis()
                + Math.max(1, timeoutSeconds) * 1_000L;
        Session current = sessions.getOrDefault(
                player.getUniqueId(),
                Session.EMPTY
        );
        sessions.put(
                player.getUniqueId(),
                current.withConfirmation(action, expiresAt)
        );
        return expiresAt;
    }

    public boolean confirmationMatches(
            Player player,
            String action,
            long expiresAtMillis
    ) {
        Session session = session(player);
        return session != null
                && action != null
                && action.equals(session.confirmAction())
                && session.confirmExpiresAtMillis() == expiresAtMillis;
    }

    public void clearConfirmation(Player player) {
        Session current = sessions.get(player.getUniqueId());
        if (current == null) {
            return;
        }
        sessions.put(player.getUniqueId(), current.withoutConfirmation());
    }

    public void clear(Player player) {
        sessions.remove(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer());
    }

    public record Session(
            String action,
            UUID targetId,
            String confirmAction,
            long confirmExpiresAtMillis
    ) {
        private static final Session EMPTY =
                new Session(null, null, null, 0L);

        private Session withTarget(UUID targetId) {
            return new Session(
                    action,
                    targetId,
                    confirmAction,
                    confirmExpiresAtMillis
            );
        }

        private Session withConfirmation(
                String action,
                long expiresAtMillis
        ) {
            return new Session(
                    this.action,
                    targetId,
                    action,
                    expiresAtMillis
            );
        }

        private Session withoutConfirmation() {
            return new Session(action, targetId, null, 0L);
        }
    }
}
