package net.mineacle.core.webprofiles.listener;

import net.mineacle.core.Core;
import net.mineacle.core.webprofiles.service.WebProfileSyncService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public final class WebProfileListener implements Listener {

    private final Core core;
    private final WebProfileSyncService syncService;

    public WebProfileListener(Core core, WebProfileSyncService syncService) {
        this.core = core;
        this.syncService = syncService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        syncService.syncPlayer(event.getPlayer(), true);

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (event.getPlayer().isOnline()) {
                syncService.syncPlayer(event.getPlayer(), true);
            }
        }, 40L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        core.getServer().getScheduler().runTask(core, () -> {
            if (event.getPlayer().isOnline()) {
                syncService.syncPlayer(event.getPlayer(), true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        core.getServer().getScheduler().runTask(core, () -> {
            if (event.getPlayer().isOnline()) {
                syncService.syncPlayer(event.getPlayer(), true);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        syncService.syncPlayer(event.getPlayer(), false);
    }
}
