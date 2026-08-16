package net.mineacle.core.admininspect.listener;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.service.OfflineInspectService;
import net.mineacle.core.admininspect.service.OfflineInspectService.Access;
import net.mineacle.core.admininspect.service.OfflineInspectService.Session;
import org.bukkit.Material;
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

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("unused")
public final class OfflineInspectListener
        implements Listener {

    private final Core core;
    private final OfflineInspectService service;
    private final Set<UUID> targetOwnedCursors =
            new HashSet<>();

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
         * Offline inspection edits a detached Mineacle snapshot. The lower
         * inventory belongs to the live inspector and is a different
         * persistence domain, so cross-inventory transfer is intentionally
         * denied. This removes the item-loss/duplication window that existed
         * when an offline target item could be moved into staff playerdata.
         */
        if (rawSlot < 0 || rawSlot >= topSize) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        if (service.blockedTopSlot(
                session,
                rawSlot
        )) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        UUID viewerId = viewer.getUniqueId();
        ItemStack cursor = event.getCursor();

        /*
         * Every non-empty cursor used in an offline edit must have originated
         * from this target snapshot. A foreign cursor is rejected instead of
         * allowing a plugin/client edge case to inject an inspector item into
         * the offline player's pending data.
         */
        if (hasItem(cursor)
                && !targetOwnedCursors.contains(viewerId)) {
            event.setCancelled(true);
            service.blockedFeedback(viewer);
            return;
        }

        if (startsTargetCursor(
                action,
                cursor,
                event.getCurrentItem()
        )) {
            targetOwnedCursors.add(viewerId);
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
            if (rawSlot < 0 || rawSlot >= topSize) {
                event.setCancelled(true);
                service.blockedFeedback(viewer);
                return;
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

        UUID viewerId = viewer.getUniqueId();
        ItemStack oldCursor = event.getOldCursor();

        if (hasItem(oldCursor)
                && !targetOwnedCursors.contains(viewerId)) {
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

        Inventory targetInventory =
                event.getView().getTopInventory();
        Session session = service.session(
                viewer,
                targetInventory
        );

        if (session == null) {
            targetOwnedCursors.remove(
                    viewer.getUniqueId()
            );
            return;
        }

        if (!returnTargetCursor(
                viewer,
                session
        )) {
            scheduleRecoveryReopen(
                    viewer,
                    session
            );
            return;
        }

        service.close(
                viewer,
                targetInventory
        );
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
        Session session = service.session(
                player,
                player.getOpenInventory()
                        .getTopInventory()
        );

        if (session != null) {
            returnTargetCursor(
                    player,
                    session
            );
        } else {
            targetOwnedCursors.remove(
                    player.getUniqueId()
            );
        }

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

    private void reconcileCursorOwnershipNextTick(
            Player viewer
    ) {
        UUID viewerId = viewer.getUniqueId();

        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (!viewer.isOnline()) {
                        targetOwnedCursors.remove(viewerId);
                        return;
                    }

                    if (!hasItem(viewer.getItemOnCursor())) {
                        targetOwnedCursors.remove(viewerId);
                    }
                }
        );
    }

    private boolean returnTargetCursor(
            Player viewer,
            Session session
    ) {
        UUID viewerId = viewer.getUniqueId();

        if (!targetOwnedCursors.contains(viewerId)) {
            return true;
        }

        ItemStack cursor = viewer.getItemOnCursor();

        if (!hasItem(cursor)) {
            targetOwnedCursors.remove(viewerId);
            return true;
        }

        ItemStack remaining = cursor.clone();
        Inventory target = session.inventory();

        mergeIntoExistingStacks(
                session,
                target,
                remaining
        );
        placeIntoEmptySlots(
                session,
                target,
                remaining
        );

        if (remaining.getAmount() > 0) {
            core.getLogger().severe(
                    "[AdminInspect] Could not return target-owned cursor item "
                            + "to offline inspection session for viewer="
                            + viewerId
            );
            service.blockedFeedback(viewer);
            return false;
        }

        viewer.setItemOnCursor(
                new ItemStack(Material.AIR)
        );
        targetOwnedCursors.remove(viewerId);
        service.recordModification(viewer);
        return true;
    }

    private void mergeIntoExistingStacks(
            Session session,
            Inventory target,
            ItemStack remaining
    ) {
        for (int slot = 0;
             slot < target.getSize()
                     && remaining.getAmount() > 0;
             slot++) {
            if (service.blockedTopSlot(
                    session,
                    slot
            )) {
                continue;
            }

            ItemStack existing = target.getItem(slot);

            if (!hasItem(existing)
                    || !existing.isSimilar(remaining)) {
                continue;
            }

            int maxStack = existing.getMaxStackSize();
            int free = maxStack - existing.getAmount();

            if (free <= 0) {
                continue;
            }

            int moved = Math.min(
                    free,
                    remaining.getAmount()
            );
            ItemStack merged = existing.clone();
            merged.setAmount(existing.getAmount() + moved);
            target.setItem(slot, merged);
            remaining.setAmount(
                    remaining.getAmount() - moved
            );
        }
    }

    private void placeIntoEmptySlots(
            Session session,
            Inventory target,
            ItemStack remaining
    ) {
        for (int slot = 0;
             slot < target.getSize()
                     && remaining.getAmount() > 0;
             slot++) {
            if (service.blockedTopSlot(
                    session,
                    slot
            )) {
                continue;
            }

            ItemStack existing = target.getItem(slot);

            if (hasItem(existing)) {
                continue;
            }

            int moved = Math.min(
                    remaining.getMaxStackSize(),
                    remaining.getAmount()
            );
            ItemStack placed = remaining.clone();
            placed.setAmount(moved);
            target.setItem(slot, placed);
            remaining.setAmount(
                    remaining.getAmount() - moved
            );
        }
    }

    private void scheduleRecoveryReopen(
            Player viewer,
            Session session
    ) {
        core.getServer().getScheduler().runTask(
                core,
                () -> {
                    if (!viewer.isOnline()) {
                        return;
                    }

                    Session current = service.session(
                            viewer,
                            session.inventory()
                    );

                    if (current != session) {
                        return;
                    }

                    viewer.openInventory(
                            session.inventory()
                    );
                }
        );
    }

    private boolean hasItem(ItemStack item) {
        return item != null
                && !item.getType().isAir()
                && item.getAmount() > 0;
    }
}
