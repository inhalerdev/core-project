package net.mineacle.core.common.teleport;

import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/** One lifecycle listener for every Mineacle delayed teleport. */
@SuppressWarnings("unused")
public final class TeleportLifecycleListener implements Listener {

    private final TeleportService teleports;

    public TeleportLifecycleListener(TeleportService teleports) {
        this.teleports = teleports;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!teleports.isActive(event.getPlayer())) {
            return;
        }

        Location to = event.getTo();

        if (samePosition(event.getFrom(), to)) {
            return;
        }

        teleports.handleMove(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!teleports.isActive(event.getPlayer())) {
            return;
        }

        teleports.handleExternalTeleport(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        teleports.cancel(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        teleports.cancel(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        teleports.cancel(event.getPlayer().getUniqueId());
    }

    private boolean samePosition(Location from, Location to) {
        return from.getWorld() == to.getWorld()
                && Double.compare(from.getX(), to.getX()) == 0
                && Double.compare(from.getY(), to.getY()) == 0
                && Double.compare(from.getZ(), to.getZ()) == 0;
    }
}
