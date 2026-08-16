package net.mineacle.core.admininspect.listener;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.OfflineInspectService;
import net.mineacle.core.admininspect.service.OfflineInspectService.Access;
import net.mineacle.core.admininspect.service.OfflineInspectService.Session;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

@SuppressWarnings("unused")
public final class OfflineInspectListener
        implements Listener {

    private final Core core;
    private final OfflineInspectService service;

    public OfflineInspectListener(
            Core core,
            OfflineInspectService service
    ) {
        this.core = core;
        this.service = service;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }

        Session session = service.session(
                viewer,
                event.getView().getTopInventory()
        );

        if (session == null) {
            return;
        }

        Access access = service.access(
                viewer,
                event.getView().getTopInventory()
        );

        if (access == Access.UNAUTHORIZED) {
            event.setCancelled(true);
            viewer.closeInventory();
            return;
        }

        if (access == Access.READ_ONLY) {
            event.setCancelled(true);
            service.readOnlyFeedback(viewer);
            return;
        }

        InventoryAction action = event.getAction();

        if (service.blockedAction(action)) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        int topSize = event.getView()
                .getTopInventory()
                .getSize();
        int rawSlot = event.getRawSlot();

        if (rawSlot >= 0
                && rawSlot < topSize
                && service.blockedTopSlot(
                session,
                rawSlot
        )) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        if (rawSlot >= 0
                && rawSlot < topSize
                && action != InventoryAction.NOTHING) {
            service.recordModification(viewer);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player viewer)) {
            return;
        }

        Session session = service.session(
                viewer,
                event.getView().getTopInventory()
        );

        if (session == null) {
            return;
        }

        Access access = service.access(
                viewer,
                event.getView().getTopInventory()
        );

        if (access != Access.EDITABLE) {
            event.setCancelled(true);
            service.readOnlyFeedback(viewer);
            return;
        }

        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        boolean touchesTarget = false;

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= topSize) {
                continue;
            }

            touchesTarget = true;

            if (service.blockedTopSlot(
                    session,
                    rawSlot
            )) {
                event.setCancelled(true);
                service.blockedFeedback(viewer);
                return;
            }
        }

        if (touchesTarget) {
            service.recordModification(viewer);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player viewer) {
            service.close(
                    viewer,
                    event.getView().getTopInventory()
            );
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.targetJoining(player);

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (player.isOnline()) {
                        service.applyPending(player);
                    }
                },
                service.applyDelayTicks()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        service.viewerQuit(player);
        service.capture(player);
    }
}
