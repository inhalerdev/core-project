package net.mineacle.core.common.teleport;

import net.mineacle.core.Core;
import org.bukkit.Location;

public final class TeleportMovement {

    private static final double DEFAULT_CANCEL_DISTANCE = 2.0D;

    private TeleportMovement() {
    }

    public static boolean movedTooFar(
            Core core,
            Location start,
            Location current
    ) {
        if (start == null
                || current == null
                || start.getWorld() == null
                || current.getWorld() == null
                || !start.getWorld().equals(current.getWorld())) {
            return true;
        }

        double allowedDistance = cancelDistance(core);

        return Math.abs(start.getX() - current.getX()) >= allowedDistance
                || Math.abs(start.getY() - current.getY()) >= allowedDistance
                || Math.abs(start.getZ() - current.getZ()) >= allowedDistance;
    }

    private static double cancelDistance(Core core) {
        if (core == null) {
            return DEFAULT_CANCEL_DISTANCE;
        }

        double configured = core.getConfig().getDouble(
                "teleport-perks.cancel-distance",
                DEFAULT_CANCEL_DISTANCE
        );

        if (!Double.isFinite(configured) || configured < 0.01D) {
            return DEFAULT_CANCEL_DISTANCE;
        }

        return configured;
    }
}
