package net.mineacle.core.servermessages.listener;

import net.mineacle.core.servermessages.service.ServerMessageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;

public final class ServerKickListener implements Listener {

    private final ServerMessageService service;

    public ServerKickListener(ServerMessageService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onKick(PlayerKickEvent event) {
        if (!service.enabled()) {
            return;
        }

        String message = service.activeShutdownMessage();
        if (message == null || message.isBlank()) {
            return;
        }

        event.setReason(message);
    }
}
