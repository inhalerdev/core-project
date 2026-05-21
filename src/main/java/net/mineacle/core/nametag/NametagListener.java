package net.mineacle.core.nametag;

import net.mineacle.core.Core;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

public final class NametagListener implements Listener {

    private final Core core;
    private final NametagService service;

    public NametagListener(Core core, NametagService service) {
        this.core = core;
        this.service = service;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (player.isOnline()) {
                service.refreshAll();
            }
        }, 10L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (player.isOnline()) {
                service.refreshAll();
            }
        }, 2L);
    }
}
