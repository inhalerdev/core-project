package net.mineacle.core.duels.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.duels.service.DuelService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

public final class DuelListener implements Listener {

    private final Core core;
    private final DuelService duelService;

    public DuelListener(Core core, DuelService duelService) {
        this.core = core;
        this.duelService = duelService;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        duelService.eliminate(event.getEntity());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        if (duelService.queued(event.getPlayer())) {
            duelService.removeFromQueue(event.getPlayer());
        }

        if (duelService.inDuel(event.getPlayer())) {
            duelService.forfeit(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();

        if (!duelService.inDuel(player)) {
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.ENDER_PEARL
                || event.getCause() == PlayerTeleportEvent.TeleportCause.CHORUS_FRUIT
                || event.getCause() == PlayerTeleportEvent.TeleportCause.UNKNOWN) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(TextColor.color("&cYou cannot teleport during a duel"));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!duelService.blockedCommand(player, event.getMessage().replaceFirst("^/", ""))) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(TextColor.color("&cYou cannot use that command during a duel"));
    }
}
