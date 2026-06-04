package net.mineacle.core.servermessages.listener;

import net.mineacle.core.servermessages.service.ServerMessageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerLoginEvent;

public final class ServerLoginListener implements Listener {

    private final ServerMessageService service;

    public ServerLoginListener(ServerMessageService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAsyncPreLogin(AsyncPlayerPreLoginEvent event) {
        service.handleAsyncPreLogin(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLogin(PlayerLoginEvent event) {
        service.handleLogin(event);
    }
}
