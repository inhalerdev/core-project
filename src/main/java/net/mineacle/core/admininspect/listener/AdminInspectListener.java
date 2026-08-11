package net.mineacle.core.admininspect.listener;

import net.mineacle.core.admininspect.service.AdminInspectService;
import net.mineacle.core.admininspect.service.AdminInspectService.InteractionAccess;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;

@SuppressWarnings("unused")
public final class AdminInspectListener
        implements Listener {

    private final AdminInspectService service;

    public AdminInspectListener(
            AdminInspectService service
    ) {
        this.service = service;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onClick(
            InventoryClickEvent event
    ) {
        if (!(event.getWhoClicked()
                instanceof Player viewer)) {
            return;
        }

        InteractionAccess access =
                service.interactionAccess(
                        viewer,
                        event.getView()
                );

        if (access == InteractionAccess.NONE) {
            return;
        }

        if (access
                == InteractionAccess.UNAUTHORIZED) {
            event.setCancelled(true);
            service.scheduleAccessClose(
                    viewer
            );
            return;
        }

        if (access
                == InteractionAccess.READ_ONLY) {
            event.setCancelled(true);
            service.readOnlyFeedback(
                    viewer
            );
            return;
        }

        if (event.getAction()
                != InventoryAction.NOTHING) {
            service.recordModification(
                    viewer,
                    clickDetail(event)
            );
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDrag(
            InventoryDragEvent event
    ) {
        if (!(event.getWhoClicked()
                instanceof Player viewer)) {
            return;
        }

        InteractionAccess access =
                service.interactionAccess(
                        viewer,
                        event.getView()
                );

        if (access == InteractionAccess.NONE) {
            return;
        }

        if (access
                == InteractionAccess.UNAUTHORIZED) {
            event.setCancelled(true);
            service.scheduleAccessClose(
                    viewer
            );
            return;
        }

        if (access
                == InteractionAccess.READ_ONLY) {
            event.setCancelled(true);
            service.readOnlyFeedback(
                    viewer
            );
            return;
        }

        service.recordModification(
                viewer,
                "DRAG raw-slots="
                        + event.getRawSlots()
                        .size()
                        + " cursor="
                        + item(event.getOldCursor())
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onClose(
            InventoryCloseEvent event
    ) {
        if (event.getPlayer()
                instanceof Player viewer) {
            service.inventoryClosed(
                    viewer,
                    event.getView(),
                    event.getReason()
            );
        }
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onQuit(
            PlayerQuitEvent event
    ) {
        service.viewerQuit(
                event.getPlayer()
        );
        service.targetUnavailable(
                event.getPlayer(),
                false
        );
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onDeath(
            PlayerDeathEvent event
    ) {
        service.targetUnavailable(
                event.getEntity(),
                true
        );
    }

    private String clickDetail(
            InventoryClickEvent event
    ) {
        return event.getAction().name()
                + " raw-slot="
                + event.getRawSlot()
                + " clicked="
                + item(
                event.getCurrentItem()
        )
                + " cursor="
                + item(
                event.getCursor()
        );
    }

    private String item(
            ItemStack stack
    ) {
        if (stack == null
                || stack.getType()
                .isAir()) {
            return "AIR";
        }

        return stack.getAmount()
                + "x"
                + stack.getType()
                .getKey()
                .asString();
    }
}
