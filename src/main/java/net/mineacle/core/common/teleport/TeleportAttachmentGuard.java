package net.mineacle.core.common.teleport;

import net.mineacle.core.Core;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Handles entity attachments that Paper cannot carry with a Player across
 * worlds. Mineacle nametags are TextDisplay passengers, so they must be
 * detached immediately before a cross-world teleport.
 * <p>
 * The guard is intentionally owned by the central teleport package: feature
 * systems never need to know how nametags, vehicles, or passengers are
 * represented.
 */
final class TeleportAttachmentGuard {

    private final NamespacedKey nametagOwnerKey;

    TeleportAttachmentGuard(Core core) {
        nametagOwnerKey = new NamespacedKey(core, "nametag_owner");
    }

    Snapshot suspendFor(
            Player player,
            Location destination
    ) {
        if (player == null
                || destination == null
                || destination.getWorld() == null
                || player.getWorld() == destination.getWorld()) {
            return Snapshot.none();
        }

        World originWorld = player.getWorld();
        Entity vehicle = player.getVehicle();

        if (vehicle != null) {
            player.leaveVehicle();
        }

        List<Entity> detachedPassengers =
                new ArrayList<>(player.getPassengers());
        int mineacleNametags = 0;

        for (Entity passenger : detachedPassengers) {
            if (isMineacleNametag(passenger, player.getUniqueId())) {
                mineacleNametags++;
            }

            player.removePassenger(passenger);
        }

        return new Snapshot(
                true,
                originWorld,
                vehicle,
                List.copyOf(detachedPassengers),
                mineacleNametags
        );
    }

    void restoreAfterFailure(
            Player player,
            Snapshot snapshot
    ) {
        if (player == null
                || snapshot == null
                || !snapshot.crossWorld()
                || !player.isOnline()
                || player.getWorld() != snapshot.originWorld()) {
            return;
        }

        Entity vehicle = snapshot.vehicle();

        if (vehicle != null
                && vehicle.isValid()
                && vehicle.getWorld() == player.getWorld()) {
            vehicle.addPassenger(player);
        }

        for (Entity passenger : snapshot.passengers()) {
            if (passenger != null
                    && passenger.isValid()
                    && passenger.getWorld() == player.getWorld()) {
                player.addPassenger(passenger);
            }
        }
    }

    void completeSuccess(
            UUID playerId,
            Snapshot snapshot
    ) {
        if (playerId == null
                || snapshot == null
                || !snapshot.crossWorld()) {
            return;
        }

        /*
         * Real passengers are intentionally left in the old world when a
         * player uses a convenience cross-world teleport. Mineacle's old
         * nametag display is removed immediately; NametagListener rebuilds
         * the display in the destination world on PlayerChangedWorldEvent.
         */
        for (Entity passenger : snapshot.passengers()) {
            if (isMineacleNametag(passenger, playerId)
                    && passenger.isValid()) {
                passenger.remove();
            }
        }
    }

    private boolean isMineacleNametag(
            Entity entity,
            UUID ownerId
    ) {
        if (entity == null || ownerId == null) {
            return false;
        }

        String owner = entity
                .getPersistentDataContainer()
                .get(
                        nametagOwnerKey,
                        PersistentDataType.STRING
                );

        return ownerId.toString().equals(owner);
    }

    record Snapshot(
            boolean crossWorld,
            World originWorld,
            Entity vehicle,
            List<Entity> passengers,
            int mineacleNametags
    ) {
        private static Snapshot none() {
            return new Snapshot(
                    false,
                    null,
                    null,
                    List.of(),
                    0
            );
        }

        int detachedPassengerCount() {
            return passengers.size();
        }
    }
}
