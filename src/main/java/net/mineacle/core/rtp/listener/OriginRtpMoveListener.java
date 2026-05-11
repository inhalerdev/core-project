package net.mineacle.core.rtp.listener;

import net.mineacle.core.rtp.service.OriginRtpQueueService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class OriginRtpMoveListener implements Listener {

    private final OriginRtpQueueService queueService;

    public OriginRtpMoveListener(OriginRtpQueueService queueService) {
        this.queueService = queueService;
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getTo() == null) {
            return;
        }

        Player player = event.getPlayer();
        queueService.handleMove(player);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        queueService.cancel(event.getPlayer(), false);
    }
}