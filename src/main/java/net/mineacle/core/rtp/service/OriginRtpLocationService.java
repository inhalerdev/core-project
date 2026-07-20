package net.mineacle.core.rtp.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

public final class OriginRtpLocationService {

    private static final int BORDER_MARGIN = 8;

    private final Core core;

    public OriginRtpLocationService(Core core) {
        this.core = core;
    }

    public CompletableFuture<Location> findSafeLocation(
            String destination
    ) {
        CompletableFuture<Location> result =
                new CompletableFuture<>();
        OriginRtpSearchSettings settings =
                OriginRtpSearchSettings.fromConfig(
                        core,
                        destination
                );
        World world = Bukkit.getWorld(
                settings.worldName()
        );

        if (world == null) {
            result.complete(null);
            return result;
        }

        attempt(
                world,
                settings,
                0,
                result
        );
        return result;
    }

    private void attempt(
            World world,
            OriginRtpSearchSettings settings,
            int attempt,
            CompletableFuture<Location> result
    ) {
        if (result.isDone()) {
            return;
        }

        if (attempt >= settings.maxAttempts()) {
            result.complete(null);
            return;
        }

        Coordinates coordinates = randomCoordinates(
                world,
                settings
        );

        if (coordinates == null) {
            result.complete(null);
            return;
        }

        int chunkX = coordinates.x() >> 4;
        int chunkZ = coordinates.z() >> 4;

        world.getChunkAtAsync(
                chunkX,
                chunkZ,
                true
        ).whenComplete((chunk, throwable) ->
                Bukkit.getScheduler().runTask(
                        core,
                        () -> {
                            if (result.isDone()) {
                                return;
                            }

                            if (throwable != null
                                    || chunk == null) {
                                attempt(
                                        world,
                                        settings,
                                        attempt + 1,
                                        result
                                );
                                return;
                            }

                            Location safe = safeLocationAt(
                                    world,
                                    coordinates.x(),
                                    coordinates.z(),
                                    settings
                            );

                            if (safe != null) {
                                result.complete(safe);
                                return;
                            }

                            attempt(
                                    world,
                                    settings,
                                    attempt + 1,
                                    result
                            );
                        }
                )
        );
    }

    private Coordinates randomCoordinates(
            World world,
            OriginRtpSearchSettings settings
    ) {
        for (int attempt = 0; attempt < 128; attempt++) {
            Coordinates candidate =
                    configuredCandidate(settings);

            if (candidate == null) {
                continue;
            }

            if (!settings.useWorldBorder()
                    || insideBorder(
                    world.getWorldBorder(),
                    candidate.x(),
                    candidate.z()
            )) {
                return candidate;
            }
        }

        return null;
    }

    private Coordinates configuredCandidate(
            OriginRtpSearchSettings settings
    ) {
        ThreadLocalRandom random =
                ThreadLocalRandom.current();

        if (settings.shape()
                .equalsIgnoreCase("circle")) {
            double minimumSquared =
                    (double) settings.minRadius()
                            * settings.minRadius();
            double maximumSquared =
                    (double) settings.maxRadius()
                            * settings.maxRadius();
            double radius = Math.sqrt(
                    random.nextDouble(
                            minimumSquared,
                            maximumSquared
                    )
            );
            double angle = random.nextDouble(
                    0.0D,
                    Math.PI * 2.0D
            );

            return new Coordinates(
                    settings.centerX()
                            + (int) Math.round(
                            Math.cos(angle) * radius
                    ),
                    settings.centerZ()
                            + (int) Math.round(
                            Math.sin(angle) * radius
                    )
            );
        }

        for (int attempt = 0; attempt < 64; attempt++) {
            int x = random.nextInt(
                    -settings.maxRadius(),
                    settings.maxRadius() + 1
            ) + settings.centerX();
            int z = random.nextInt(
                    -settings.maxRadius(),
                    settings.maxRadius() + 1
            ) + settings.centerZ();

            if (distanceSquared(
                    x,
                    z,
                    settings.centerX(),
                    settings.centerZ()
            ) >= (long) settings.minRadius()
                    * settings.minRadius()) {
                return new Coordinates(x, z);
            }
        }

        return null;
    }

    private boolean insideBorder(
            WorldBorder border,
            int x,
            int z
    ) {
        if (border == null) {
            return true;
        }

        Location center = border.getCenter();
        double half = Math.max(
                0.0D,
                border.getSize() / 2.0D
                        - BORDER_MARGIN
        );

        return x + 0.5D >= center.getX() - half
                && x + 0.5D <= center.getX() + half
                && z + 0.5D >= center.getZ() - half
                && z + 0.5D <= center.getZ() + half;
    }

    private Location safeLocationAt(
            World world,
            int x,
            int z,
            OriginRtpSearchSettings settings
    ) {
        int minimumY = settings.clampedMinimumY(world);
        int maximumY = settings.clampedMaximumY(world);

        if (maximumY <= minimumY) {
            return null;
        }

        if (settings.surfaceOnly()) {
            int groundY = Math.min(
                    world.getHighestBlockAt(x, z).getY(),
                    maximumY
            );

            return safeAtGround(
                    world,
                    x,
                    groundY,
                    z,
                    settings,
                    minimumY,
                    maximumY
            );
        }

        for (int groundY = maximumY;
             groundY >= minimumY;
             groundY--) {
            Location safe = safeAtGround(
                    world,
                    x,
                    groundY,
                    z,
                    settings,
                    minimumY,
                    maximumY
            );

            if (safe != null) {
                return safe;
            }
        }

        return null;
    }

    private Location safeAtGround(
            World world,
            int x,
            int groundY,
            int z,
            OriginRtpSearchSettings settings,
            int minimumY,
            int maximumY
    ) {
        if (groundY < minimumY
                || groundY + 2 > maximumY) {
            return null;
        }

        Block ground = world.getBlockAt(
                x,
                groundY,
                z
        );
        Block feet = world.getBlockAt(
                x,
                groundY + 1,
                z
        );
        Block head = world.getBlockAt(
                x,
                groundY + 2,
                z
        );

        if (!safeGround(ground, settings)
                || !safeSpace(feet, settings)
                || !safeSpace(head, settings)
                || unsafeNearby(
                world,
                x,
                groundY,
                z,
                settings
        )) {
            return null;
        }

        return new Location(
                world,
                x + 0.5D,
                groundY + 1.0D,
                z + 0.5D,
                ThreadLocalRandom.current()
                        .nextFloat() * 360.0F,
                0.0F
        );
    }

    private boolean safeGround(
            Block block,
            OriginRtpSearchSettings settings
    ) {
        if (block == null) {
            return false;
        }

        Material material = block.getType();

        if (!material.isSolid()
                || settings.unsafeBlocks()
                .contains(material)) {
            return false;
        }

        String name = material.name();

        return !name.endsWith("_LEAVES")
                && !name.endsWith("_LOG")
                && !name.endsWith("_WOOD")
                && !name.endsWith("_STEM")
                && !name.endsWith("_HYPHAE");
    }

    private boolean safeSpace(
            Block block,
            OriginRtpSearchSettings settings
    ) {
        if (block == null) {
            return false;
        }

        Material material = block.getType();

        if (settings.unsafeBlocks()
                .contains(material)) {
            return false;
        }

        return material.isAir()
                || block.isPassable();
    }

    private boolean unsafeNearby(
            World world,
            int centerX,
            int groundY,
            int centerZ,
            OriginRtpSearchSettings settings
    ) {
        int radius = settings.nearbySafetyRadius();

        for (int x = centerX - radius;
             x <= centerX + radius;
             x++) {
            for (int z = centerZ - radius;
                 z <= centerZ + radius;
                 z++) {
                for (int y = groundY;
                     y <= groundY + 2;
                     y++) {
                    Material material = world
                            .getBlockAt(x, y, z)
                            .getType();

                    if (settings.unsafeBlocks()
                            .contains(material)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private long distanceSquared(
            int x,
            int z,
            int centerX,
            int centerZ
    ) {
        long deltaX = (long) x - centerX;
        long deltaZ = (long) z - centerZ;

        return deltaX * deltaX
                + deltaZ * deltaZ;
    }

    private record Coordinates(int x, int z) {
    }
}
