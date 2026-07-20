package net.mineacle.core.rtp.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class OriginRtpLocationService {

    private final Core core;

    public OriginRtpLocationService(Core core) {
        this.core = core;
    }

    /**
     * Finds a uniformly random safe block inside the world's current border.
     *
     * The border and world spawn are read again for every candidate batch, so
     * border expansions and spawn changes take effect without a reload.
     */
    public CompletableFuture<Location> findSafeLocation(
            String destination
    ) {
        CompletableFuture<Location> result =
                new CompletableFuture<>();

        Runnable begin = () -> {
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
                return;
            }

            launchBatch(
                    world,
                    settings,
                    0,
                    result
            );
        };

        if (Bukkit.isPrimaryThread()) {
            begin.run();
        } else {
            Bukkit.getScheduler().runTask(
                    core,
                    begin
            );
        }

        return result;
    }

    /**
     * Rechecks a reserved destination immediately before teleporting.
     * This protects against a border shrink, a changed world spawn, or blocks
     * being altered while the countdown was running.
     */
    public Location revalidateReservedLocation(
            Location reserved,
            String destination
    ) {
        if (reserved == null
                || reserved.getWorld() == null) {
            return null;
        }

        OriginRtpSearchSettings settings =
                OriginRtpSearchSettings.fromConfig(
                        core,
                        destination
                );
        World expected = Bukkit.getWorld(
                settings.worldName()
        );

        if (expected == null
                || expected != reserved.getWorld()) {
            return null;
        }

        SearchArea area = SearchArea.capture(
                expected,
                settings
        );
        int x = reserved.getBlockX();
        int z = reserved.getBlockZ();

        if (!area.valid() || !area.allows(x, z)) {
            return null;
        }

        return safeLocationAt(
                expected,
                x,
                z,
                settings
        );
    }

    private void launchBatch(
            World world,
            OriginRtpSearchSettings settings,
            int attemptsUsed,
            CompletableFuture<Location> result
    ) {
        if (result.isDone()) {
            return;
        }

        if (attemptsUsed >= settings.maximumAttempts()) {
            result.complete(null);
            return;
        }

        /*
         * Capture the live border and live world spawn for every batch.
         * Expanding the border while the server is running automatically
         * expands the next candidate pool.
         */
        SearchArea area = SearchArea.capture(
                world,
                settings
        );

        if (!area.valid()) {
            result.complete(null);
            return;
        }

        int batchSize = Math.min(
                settings.candidatesPerBatch(),
                settings.maximumAttempts() - attemptsUsed
        );
        List<Coordinates> candidates =
                area.randomCandidates(batchSize);

        if (candidates.isEmpty()) {
            result.complete(null);
            return;
        }

        AtomicInteger remaining =
                new AtomicInteger(candidates.size());

        for (Coordinates candidate : candidates) {
            loadRequiredChunks(
                    world,
                    candidate,
                    settings.safePlatformRadius()
            ).whenComplete(
                    (ignored, throwable) ->
                            Bukkit.getScheduler().runTask(
                                    core,
                                    () -> {
                                        if (result.isDone()) {
                                            return;
                                        }

                                        if (throwable == null) {
                                            /*
                                             * The border may have changed while
                                             * the chunks were loading, so check
                                             * the current area once more.
                                             */
                                            SearchArea current =
                                                    SearchArea.capture(
                                                            world,
                                                            settings
                                                    );

                                            if (current.valid()
                                                    && current.allows(
                                                    candidate.x(),
                                                    candidate.z()
                                            )) {
                                                Location safe =
                                                        safeLocationAt(
                                                                world,
                                                                candidate.x(),
                                                                candidate.z(),
                                                                settings
                                                        );

                                                if (safe != null) {
                                                    result.complete(safe);
                                                    return;
                                                }
                                            }
                                        }

                                        if (remaining.decrementAndGet()
                                                == 0) {
                                            launchBatch(
                                                    world,
                                                    settings,
                                                    attemptsUsed
                                                            + candidates.size(),
                                                    result
                                            );
                                        }
                                    }
                            )
            );
        }
    }

    private CompletableFuture<Void> loadRequiredChunks(
            World world,
            Coordinates candidate,
            int radius
    ) {
        int minimumChunkX =
                (candidate.x() - radius) >> 4;
        int maximumChunkX =
                (candidate.x() + radius) >> 4;
        int minimumChunkZ =
                (candidate.z() - radius) >> 4;
        int maximumChunkZ =
                (candidate.z() + radius) >> 4;
        List<CompletableFuture<Chunk>> futures =
                new ArrayList<>();

        for (int chunkX = minimumChunkX;
             chunkX <= maximumChunkX;
             chunkX++) {
            for (int chunkZ = minimumChunkZ;
                 chunkZ <= maximumChunkZ;
                 chunkZ++) {
                futures.add(
                        world.getChunkAtAsync(
                                chunkX,
                                chunkZ,
                                true
                        )
                );
            }
        }

        return CompletableFuture.allOf(
                futures.toArray(
                        CompletableFuture[]::new
                )
        );
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

        int centerGroundY = findCenterGroundY(
                world,
                x,
                z,
                settings,
                minimumY,
                maximumY
        );

        if (centerGroundY == Integer.MIN_VALUE) {
            return null;
        }

        if (!safePlatform(
                world,
                x,
                centerGroundY,
                z,
                settings,
                minimumY,
                maximumY
        )) {
            return null;
        }

        return new Location(
                world,
                x + 0.5D,
                centerGroundY + 1.0D,
                z + 0.5D,
                ThreadLocalRandom.current()
                        .nextFloat() * 360.0F,
                0.0F
        );
    }

    private int findCenterGroundY(
            World world,
            int x,
            int z,
            OriginRtpSearchSettings settings,
            int minimumY,
            int maximumY
    ) {
        int startY = settings.surfaceOnly()
                ? Math.min(
                world.getHighestBlockAt(x, z).getY(),
                maximumY
        )
                : maximumY;

        for (int groundY = startY;
             groundY >= minimumY;
             groundY--) {
            if (safeColumn(
                    world,
                    x,
                    groundY,
                    z,
                    settings
            )) {
                return groundY;
            }
        }

        return Integer.MIN_VALUE;
    }

    private boolean safePlatform(
            World world,
            int centerX,
            int centerGroundY,
            int centerZ,
            OriginRtpSearchSettings settings,
            int minimumY,
            int maximumY
    ) {
        int radius = settings.safePlatformRadius();
        int maximumDifference =
                settings.maximumGroundHeightDifference();

        for (int x = centerX - radius;
             x <= centerX + radius;
             x++) {
            for (int z = centerZ - radius;
                 z <= centerZ + radius;
                 z++) {
                int nearbyGroundY = findNearbyGroundY(
                        world,
                        x,
                        z,
                        centerGroundY,
                        maximumDifference,
                        settings,
                        minimumY,
                        maximumY
                );

                if (nearbyGroundY == Integer.MIN_VALUE) {
                    return false;
                }
            }
        }

        return true;
    }

    private int findNearbyGroundY(
            World world,
            int x,
            int z,
            int centerGroundY,
            int maximumDifference,
            OriginRtpSearchSettings settings,
            int minimumY,
            int maximumY
    ) {
        int top = Math.min(
                maximumY,
                centerGroundY + maximumDifference
        );
        int bottom = Math.max(
                minimumY,
                centerGroundY - maximumDifference
        );

        for (int y = top; y >= bottom; y--) {
            if (safeColumn(
                    world,
                    x,
                    y,
                    z,
                    settings
            )) {
                return y;
            }
        }

        return Integer.MIN_VALUE;
    }

    private boolean safeColumn(
            World world,
            int x,
            int groundY,
            int z,
            OriginRtpSearchSettings settings
    ) {
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

        return safeGround(ground, settings)
                && safeSpace(feet, settings)
                && safeSpace(head, settings);
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
                && !name.endsWith("_HYPHAE")
                && !name.endsWith("_FENCE")
                && !name.endsWith("_WALL")
                && !name.endsWith("_FENCE_GATE")
                && !name.endsWith("_DOOR")
                && !name.endsWith("_TRAPDOOR")
                && !name.endsWith("_RAIL")
                && !name.contains("CAMPFIRE")
                && !name.contains("DRIPSTONE");
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

    private record Coordinates(int x, int z) {
    }

    private record SearchArea(
            long minimumX,
            long maximumX,
            long minimumZ,
            long maximumZ,
            double spawnX,
            double spawnZ,
            long minimumSpawnDistanceSquared,
            long maximumSpawnDistanceSquared,
            boolean valid
    ) {

        private static SearchArea capture(
                World world,
                OriginRtpSearchSettings settings
        ) {
            long minimumX;
            long maximumX;
            long minimumZ;
            long maximumZ;

            if (settings.useWorldBorder()) {
                WorldBorder border =
                        world.getWorldBorder();
                Location center = border.getCenter();
                double halfSize = border.getSize()
                        / 2.0D;
                double padding =
                        settings.worldBorderPadding();

                minimumX = ceilToLong(
                        center.getX()
                                - halfSize
                                + padding
                );
                maximumX = floorToLong(
                        center.getX()
                                + halfSize
                                - padding
                                - 0.000001D
                );
                minimumZ = ceilToLong(
                        center.getZ()
                                - halfSize
                                + padding
                );
                maximumZ = floorToLong(
                        center.getZ()
                                + halfSize
                                - padding
                                - 0.000001D
                );
            } else {
                int radius =
                        settings.fallbackMaximumRadius();
                minimumX = (long) settings.fallbackCenterX()
                        - radius;
                maximumX = (long) settings.fallbackCenterX()
                        + radius;
                minimumZ = (long) settings.fallbackCenterZ()
                        - radius;
                maximumZ = (long) settings.fallbackCenterZ()
                        + radius;
            }

            minimumX = Math.max(
                    Integer.MIN_VALUE + 1L,
                    minimumX
            );
            maximumX = Math.min(
                    Integer.MAX_VALUE - 1L,
                    maximumX
            );
            minimumZ = Math.max(
                    Integer.MIN_VALUE + 1L,
                    minimumZ
            );
            maximumZ = Math.min(
                    Integer.MAX_VALUE - 1L,
                    maximumZ
            );

            Location spawn = world.getSpawnLocation();
            long minimumDistance =
                    settings.minimumDistanceFromWorldSpawn();
            long maximumDistance =
                    settings.maximumDistanceFromWorldSpawn();

            return new SearchArea(
                    minimumX,
                    maximumX,
                    minimumZ,
                    maximumZ,
                    spawn.getX(),
                    spawn.getZ(),
                    minimumDistance * minimumDistance,
                    maximumDistance <= 0
                            ? 0L
                            : maximumDistance
                            * maximumDistance,
                    minimumX <= maximumX
                            && minimumZ <= maximumZ
            );
        }

        private List<Coordinates> randomCandidates(
                int requested
        ) {
            if (!valid || requested <= 0) {
                return List.of();
            }

            ThreadLocalRandom random =
                    ThreadLocalRandom.current();
            List<Coordinates> candidates =
                    new ArrayList<>(requested);
            Set<Long> unique = new HashSet<>();
            int maximumDraws = Math.max(
                    2048,
                    requested * 1024
            );

            for (int draw = 0;
                 draw < maximumDraws
                         && candidates.size() < requested;
                 draw++) {
                int x = (int) random.nextLong(
                        minimumX,
                        maximumX + 1L
                );
                int z = (int) random.nextLong(
                        minimumZ,
                        maximumZ + 1L
                );

                if (!allows(x, z)) {
                    continue;
                }

                long packed = ((long) x << 32)
                        ^ (z & 0xffffffffL);

                if (unique.add(packed)) {
                    candidates.add(
                            new Coordinates(x, z)
                    );
                }
            }

            return List.copyOf(candidates);
        }

        private boolean allows(int x, int z) {
            if (!valid
                    || x < minimumX
                    || x > maximumX
                    || z < minimumZ
                    || z > maximumZ) {
                return false;
            }

            double deltaX = x + 0.5D - spawnX;
            double deltaZ = z + 0.5D - spawnZ;
            double distanceSquared =
                    deltaX * deltaX
                            + deltaZ * deltaZ;

            if (distanceSquared
                    < minimumSpawnDistanceSquared) {
                return false;
            }

            return maximumSpawnDistanceSquared <= 0L
                    || distanceSquared
                    <= maximumSpawnDistanceSquared;
        }

        private static long ceilToLong(double value) {
            if (value <= Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }

            if (value >= Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }

            return (long) Math.ceil(value);
        }

        private static long floorToLong(double value) {
            if (value <= Long.MIN_VALUE) {
                return Long.MIN_VALUE;
            }

            if (value >= Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }

            return (long) Math.floor(value);
        }
    }
}
