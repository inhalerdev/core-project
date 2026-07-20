package net.mineacle.core.hide;

import net.mineacle.core.Core;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class HideListener implements Listener {

    private final Core core;
    private final HideService service;

    public HideListener(
            Core core,
            HideService service
    ) {
        this.core = core;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (player.isOnline()) {
                        service.apply(player);
                    }
                },
                5L
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (player.isOnline()) {
                        service.apply(player);
                    }
                },
                2L
        );
    }
}
