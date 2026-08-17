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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

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

        if (service.blockedAction(action)
                || isBundleAction(action)) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        int topSize = event.getView()
                .getTopInventory()
                .getSize();
        int rawSlot = event.getRawSlot();

        /*
         * Offline editing is a detached target snapshot. Never permit a click
         * against the inspector's live lower inventory or outside the target
         * inventory while this transaction domain is open.
         */
        if (rawSlot < 0 || rawSlot >= topSize) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        if (service.blockedTopSlot(session, rawSlot)) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        ItemStack cursor = event.getCursor();

        /*
         * A non-empty cursor may interact with the offline snapshot only when
         * it was obtained from that snapshot during this exact session. This
         * prevents staff/playerdata items from being injected through unusual
         * client or plugin inventory sequences.
         */
        if (hasItem(cursor)
                && service.targetCursorUnowned(viewer)) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        if (startsTargetCursor(
                action,
                cursor,
                event.getCurrentItem()
        )) {
            service.markTargetCursorOwned(viewer);
        }

        if (action != InventoryAction.NOTHING) {
            service.recordModification(viewer);
        }

        reconcileCursorOwnershipNextTick(viewer);
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

        int topSize = event.getView()
                .getTopInventory()
                .getSize();
        boolean touchesTarget = false;

        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot < 0 || rawSlot >= topSize) {
                event.setCancelled(true);
                service.blockedFeedback(viewer);
                return;
            }

            if (service.blockedTopSlot(session, rawSlot)) {
                event.setCancelled(true);
                service.blockedFeedback(viewer);
                return;
            }

            touchesTarget = true;
        }

        ItemStack oldCursor = event.getOldCursor();

        if (hasItem(oldCursor)
                && service.targetCursorUnowned(viewer)) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        if (touchesTarget) {
            service.recordModification(viewer);
        }

        reconcileCursorOwnershipNextTick(viewer);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player viewer)) {
            return;
        }

        Inventory targetInventory = event.getView().getTopInventory();
        Session session = service.session(viewer, targetInventory);

        if (session == null) {
            return;
        }

        /*
         * Cursor return is simulated atomically by the service. A failed return
         * makes no partial target mutation, so reopening cannot duplicate a
         * partially merged stack.
         */
        if (!service.resolveTargetCursor(viewer, session)) {
            service.abortUnresolvedCursor(
                    viewer,
                    session,
                    "inventory-close-cursor-unresolved"
            );
            return;
        }

        service.close(viewer, targetInventory);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        /*
         * The durable online marker is established before any pending offline
         * edit is considered. An old marker means the previous JVM/session was
         * unclean and causes stale snapshots to fail closed.
         */
        service.playerJoined(player);
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

        /*
         * Session finalization runs before the inspector/player snapshot. If a
         * target-owned cursor cannot be returned in full, the service clears
         * the synthetic cursor and discards the unsaved detached session rather
         * than allowing either item loss or transfer into staff playerdata.
         */
        service.viewerQuit(player);
        service.capture(player);
    }

    private boolean isBundleAction(InventoryAction action) {
        return action == InventoryAction.PICKUP_FROM_BUNDLE
                || action == InventoryAction.PICKUP_ALL_INTO_BUNDLE
                || action == InventoryAction.PICKUP_SOME_INTO_BUNDLE
                || action == InventoryAction.PLACE_FROM_BUNDLE
                || action == InventoryAction.PLACE_ALL_INTO_BUNDLE
                || action == InventoryAction.PLACE_SOME_INTO_BUNDLE;
    }

    private boolean startsTargetCursor(
            InventoryAction action,
            ItemStack cursor,
            ItemStack current
    ) {
        if (hasItem(cursor) || !hasItem(current)) {
            return false;
        }

        return action == InventoryAction.PICKUP_ALL
                || action == InventoryAction.PICKUP_HALF
                || action == InventoryAction.PICKUP_ONE
                || action == InventoryAction.PICKUP_SOME;
    }

    private void reconcileCursorOwnershipNextTick(Player viewer) {
        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (viewer.isOnline()) {
                        service.reconcileTargetCursorOwnership(viewer);
                    }
                }
        );
    }

    private boolean hasItem(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getAmount() > 0;
    }
}
