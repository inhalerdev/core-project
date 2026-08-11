package net.mineacle.core.nametag;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import net.mineacle.core.Core;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerHideEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShowEntityEvent;

@SuppressWarnings("unused")
public final class NametagListener
        implements Listener {

    private final Core core;
    private final NametagService service;

    public NametagListener(
            Core core,
            NametagService service
    ) {
        this.core = core;
        this.service = service;
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onJoin(
            PlayerJoinEvent event
    ) {
        scheduleRefresh(
                event.getPlayer(),
                2L,
                false
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onQuit(
            PlayerQuitEvent event
    ) {
        service.removePlayer(
                event.getPlayer()
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onWorldChange(
            PlayerChangedWorldEvent event
    ) {
        scheduleRefresh(
                event.getPlayer(),
                1L,
                true
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onRespawn(
            PlayerRespawnEvent event
    ) {
        scheduleRefresh(
                event.getPlayer(),
                1L,
                true
        );
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onTrack(
            PlayerTrackEntityEvent event
    ) {
        if (!service.shouldTrack(
                event.getPlayer(),
                event.getEntity()
        )) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onHideEntity(
            PlayerHideEntityEvent event
    ) {
        if (event.getEntity()
                instanceof Player owner) {
            service.hideFrom(
                    event.getPlayer(),
                    owner
            );
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onShowEntity(
            PlayerShowEntityEvent event
    ) {
        if (event.getEntity()
                instanceof Player owner) {
            service.showTo(
                    event.getPlayer(),
                    owner
            );
        }
    }

    private void scheduleRefresh(
            Player player,
            long delayTicks,
            boolean rebuild
    ) {
        core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> {
                            if (!player.isOnline()) {
                                return;
                            }

                            if (rebuild) {
                                service.rebuild(
                                        player
                                );
                            } else {
                                service.refresh(
                                        player
                                );
                            }
                        },
                        delayTicks
                );
    }
}
