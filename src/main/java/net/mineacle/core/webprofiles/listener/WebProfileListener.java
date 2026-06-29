package net.mineacle.core.webprofiles.listener;

import net.mineacle.core.Core;
import net.mineacle.core.webprofiles.service.WebProfileSyncService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class WebProfileListener implements Listener {

    private final Core core;
    private final WebProfileSyncService syncService;

    public WebProfileListener(Core core, WebProfileSyncService syncService) {
        this.core = core;
        this.syncService = syncService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        core.getServer().getScheduler().runTaskLater(core, () -> syncService.syncPlayer(event.getPlayer(), true), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        syncService.syncPlayer(event.getPlayer(), false);
    }
}
