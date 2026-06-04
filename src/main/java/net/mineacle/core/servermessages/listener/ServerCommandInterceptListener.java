package net.mineacle.core.servermessages.listener;

import net.mineacle.core.servermessages.service.ServerMessageService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.util.Locale;

public final class ServerCommandInterceptListener implements Listener {

    private final ServerMessageService service;

    public ServerCommandInterceptListener(ServerMessageService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (!service.enabled()) {
            return;
        }

        String key = controlKey(firstToken(event.getMessage()));
        if (key == null) {
            return;
        }

        if (!event.getPlayer().hasPermission("mineacleservermessages.admin")) {
            return;
        }

        service.beginServerControl(key);
        event.getPlayer().sendMessage(service.chat(key.equals("restart") ? "restart-started" : "shutdown-started"));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (!service.enabled()) {
            return;
        }

        String key = controlKey(firstToken(event.getCommand()));
        if (key == null) {
            return;
        }

        service.beginServerControl(key);
        event.getSender().sendMessage(service.chat(key.equals("restart") ? "restart-started" : "shutdown-started"));
    }

    private String firstToken(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }

        String cleaned = raw.trim();

        if (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }

        int space = cleaned.indexOf(' ');
        if (space >= 0) {
            cleaned = cleaned.substring(0, space);
        }

        return cleaned.toLowerCase(Locale.ROOT);
    }

    private String controlKey(String command) {
        return switch (command) {
            case "stop", "minecraft:stop", "bukkit:stop", "paper:stop", "spigot:stop" -> "shutdown";
            case "restart", "bukkit:restart", "spigot:restart", "paper:restart" -> "restart";
            default -> null;
        };
    }
}
