package net.mineacle.core.nametag;

import net.mineacle.core.Core;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class NametagListener implements Listener {

    private final Core core;
    private final NametagService service;

    public NametagListener(
            Core core,
            NametagService service
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
                    if (!player.isOnline()) {
                        return;
                    }

                    service.refresh(player);
                    service.refreshViewer(player);
                },
                2L
        );

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (!player.isOnline()) {
                        return;
                    }

                    service.refresh(player);
                    service.refreshViewer(player);
                },
                20L
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.removeDisplay(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        refreshAfterTransition(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        refreshAfterTransition(event.getPlayer());
    }

    private void refreshAfterTransition(Player player) {
        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (player.isOnline()) {
                        service.refresh(player);
                        service.refreshViewer(player);
                    }
                },
                2L
        );
    }
}
