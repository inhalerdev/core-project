package net.mineacle.core.security.listener;

import net.mineacle.core.security.service.SecurityService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;

public final class SecurityListener implements Listener {

    private final SecurityService service;

    public SecurityListener(SecurityService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!service.shouldBlock(player, event.getMessage())) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(service.unknownMessage());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        event.getCommands().removeIf(command -> service.shouldHideFromTab(player, command));
    }
}
