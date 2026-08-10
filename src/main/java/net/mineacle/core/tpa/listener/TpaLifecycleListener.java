package net.mineacle.core.tpa.listener;

import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/** Clears session-only TPA request and auto-accept state immediately on quit. */
public final class TpaLifecycleListener implements Listener {

    private final TpaService tpaService;

    public TpaLifecycleListener(TpaService tpaService) {
        this.tpaService = tpaService;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        tpaService.clear(event.getPlayer().getUniqueId());
    }
}
