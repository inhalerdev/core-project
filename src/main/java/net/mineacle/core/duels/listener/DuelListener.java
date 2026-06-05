package net.mineacle.core.duels.listener;

import net.mineacle.core.Core;
import net.mineacle.core.duels.service.DuelService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

public final class DuelListener implements Listener {

    private final Core core;
    private final DuelService duelService;

    public DuelListener(Core core, DuelService duelService) {
        this.core = core;
        this.duelService = duelService;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        duelService.removeFromQueue(event.getPlayer());
    }
}
