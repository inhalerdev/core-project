package net.mineacle.core.servermessages.listener;

import net.mineacle.core.servermessages.service.ServerMessageService;
import org.bukkit.Bukkit;
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
        if (!service.enabled() || service.isInternalServerControl()) {
            return;
        }

        String key = controlKey(firstToken(event.getMessage()));
        if (key == null) {
            return;
        }

        if (!event.getPlayer().hasPermission("mineacleservermessages.admin")) {
            return;
        }

        event.setCancelled(true);
        event.getPlayer().sendMessage(service.chat(key.equals("restart") ? "restart-started" : "shutdown-started"));

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("MineacleCore"), () -> service.runBrandedServerControl(key));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCommand(ServerCommandEvent event) {
        if (!service.enabled() || service.isInternalServerControl()) {
            return;
        }

        String key = controlKey(firstToken(event.getCommand()));
        if (key == null) {
            return;
        }

        event.setCancelled(true);
        event.getSender().sendMessage(service.chat(key.equals("restart") ? "restart-started" : "shutdown-started"));

        Bukkit.getScheduler().runTask(Bukkit.getPluginManager().getPlugin("MineacleCore"), () -> service.runBrandedServerControl(key));
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
