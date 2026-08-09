package net.mineacle.core.rtp.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

public final class OriginRtpLocationService {

    private static final int WORLD_SAFE_LIMIT =
            29_999_000;

    private final Core core;
    private final Map<String, ArrayDeque<Coordinates>>
            recentDestinations = new HashMap<>();

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

        int groundY = reserved.getBlockY() - 1;
        int minimumY = settings.clampedMinimumY(expected);
        int maximumY = settings.clampedMaximumY(expected);

        if (!safeLandingAt(
                expected,
                x,
                groundY,
                z,
                settings,
                minimumY,
                maximumY
        )) {
            return null;
        }

        return new Location(
                expected,
                x + 0.5D,
                groundY + 1.0D,
                z + 0.5D,
                reserved.getYaw(),
                reserved.getPitch()
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
        int poolSize = Math.min(
                64,
                Math.max(
                        batchSize,
                        batchSize
                                * settings
                                .candidatePoolMultiplier()
                )
        );
        List<Coordinates> candidates =
                prioritizeCandidates(
                        world,
                        area.randomCandidates(poolSize),
                        batchSize,
                        settings
                );

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
                    Math.max(
                            settings.safePlatformRadius(),
                            settings.hazardCheckRadius()
                    )
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
                                                    rememberDestination(
                                                            settings,
                                                            candidate
                                                    );
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

    private List<Coordinates> prioritizeCandidates(
            World world,
            List<Coordinates> pool,
            int requested,
            OriginRtpSearchSettings settings
    ) {
        if (pool.isEmpty() || requested <= 0) {
            return List.of();
        }

        List<Coordinates> selected =
                new ArrayList<>(requested);
        Set<Long> used = new HashSet<>();
        List<Coordinates> recent =
                recentSnapshot(settings.destination());

        if (settings.preferUnexploredChunks()) {
            addCandidates(
                    world,
                    pool,
                    selected,
                    used,
                    requested,
                    recent,
                    settings.minimumRecentDestinationDistance(),
                    true,
                    true
            );
        }

        addCandidates(
                world,
                pool,
                selected,
                used,
                requested,
                recent,
                settings.minimumRecentDestinationDistance(),
                false,
                true
        );

        if (settings.preferUnexploredChunks()) {
            addCandidates(
                    world,
                    pool,
                    selected,
                    used,
                    requested,
                    recent,
                    0,
                    true,
                    false
            );
        }

        addCandidates(
                world,
                pool,
                selected,
                used,
                requested,
                recent,
                0,
                false,
                false
        );

        return List.copyOf(selected);
    }

    private void addCandidates(
            World world,
            List<Coordinates> pool,
            List<Coordinates> selected,
            Set<Long> used,
            int requested,
            List<Coordinates> recent,
            int minimumRecentDistance,
            boolean requireUnexplored,
            boolean requireFarFromRecent
    ) {
        for (Coordinates candidate : pool) {
            if (selected.size() >= requested) {
                return;
            }

            long packed = candidate.packed();

            if (used.contains(packed)) {
                continue;
            }

            if (requireUnexplored
                    && world.isChunkGenerated(
                    candidate.x() >> 4,
                    candidate.z() >> 4
            )) {
                continue;
            }

            if (requireFarFromRecent
                    && !farFromRecent(
                    candidate,
                    recent,
                    minimumRecentDistance
            )) {
                continue;
            }

            used.add(packed);
            selected.add(candidate);
        }
    }

    private boolean farFromRecent(
            Coordinates candidate,
            List<Coordinates> recent,
            int minimumDistance
    ) {
        if (minimumDistance <= 0 || recent.isEmpty()) {
            return true;
        }

        long minimumSquared =
                (long) minimumDistance * minimumDistance;

        for (Coordinates previous : recent) {
            long deltaX =
                    (long) candidate.x() - previous.x();
            long deltaZ =
                    (long) candidate.z() - previous.z();

            if (deltaX * deltaX + deltaZ * deltaZ
                    < minimumSquared) {
                return false;
            }
        }

        return true;
    }

    private List<Coordinates> recentSnapshot(
            String destination
    ) {
        ArrayDeque<Coordinates> history =
                recentDestinations.get(destination);

        return history == null || history.isEmpty()
                ? List.of()
                : List.copyOf(history);
    }

    private void rememberDestination(
            OriginRtpSearchSettings settings,
            Coordinates coordinates
    ) {
        int maximum = settings.recentDestinationHistory();

        if (maximum <= 0) {
            return;
        }

        ArrayDeque<Coordinates> history =
                recentDestinations.computeIfAbsent(
                        settings.destination(),
                        ignored -> new ArrayDeque<>()
                );
        history.addFirst(coordinates);

        while (history.size() > maximum) {
            history.removeLast();
        }
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

        if (!safeLandingAt(
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
        if (settings.surfaceOnly()) {
            int startY = Math.min(
                    world.getHighestBlockAt(x, z).getY(),
                    maximumY
            );

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

        int height = maximumY - minimumY + 1;
        int startY = settings.randomizedVerticalSearch()
                ? ThreadLocalRandom.current().nextInt(
                minimumY,
                maximumY + 1
        )
                : maximumY;

        for (int offset = 0; offset < height; offset++) {
            int groundY = minimumY + Math.floorMod(
                    startY - minimumY - offset,
                    height
            );

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

    private boolean safeLandingAt(
            World world,
            int x,
            int groundY,
            int z,
            OriginRtpSearchSettings settings,
            int minimumY,
            int maximumY
    ) {
        return groundY >= minimumY
                && groundY <= maximumY
                && safeColumn(
                world,
                x,
                groundY,
                z,
                settings
        )
                && safePlatform(
                world,
                x,
                groundY,
                z,
                settings,
                minimumY,
                maximumY
        )
                && !unsafeNearby(
                world,
                x,
                groundY,
                z,
                settings
        );
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

    private boolean unsafeNearby(
            World world,
            int centerX,
            int groundY,
            int centerZ,
            OriginRtpSearchSettings settings
    ) {
        int radius = settings.hazardCheckRadius();

        for (int x = centerX - radius;
             x <= centerX + radius;
             x++) {
            for (int z = centerZ - radius;
                 z <= centerZ + radius;
                 z++) {
                for (int y = groundY;
                     y <= groundY + 3;
                     y++) {
                    Block block = world.getBlockAt(x, y, z);

                    if (unsafeMaterial(block, settings)) {
                        return true;
                    }
                }
            }
        }

        return false;
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
                || isWaterlogged(block)
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

        if (unsafeMaterial(block, settings)) {
            return false;
        }

        return material.isAir()
                || block.isPassable();
    }

    private boolean unsafeMaterial(
            Block block,
            OriginRtpSearchSettings settings
    ) {
        return block == null
                || block.isLiquid()
                || isWaterlogged(block)
                || settings.unsafeBlocks().contains(
                block.getType()
        );
    }

    private boolean isWaterlogged(Block block) {
        return block != null
                && block.getBlockData()
                instanceof Waterlogged waterlogged
                && waterlogged.isWaterlogged();
    }

    private record Coordinates(int x, int z) {

        private long packed() {
            return ((long) x << 32)
                    ^ (z & 0xffffffffL);
        }
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
                    -WORLD_SAFE_LIMIT,
                    minimumX
            );
            maximumX = Math.min(
                    WORLD_SAFE_LIMIT,
                    maximumX
            );
            minimumZ = Math.max(
                    -WORLD_SAFE_LIMIT,
                    minimumZ
            );
            maximumZ = Math.min(
                    WORLD_SAFE_LIMIT,
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

                Coordinates candidate =
                        new Coordinates(x, z);

                if (unique.add(candidate.packed())) {
                    candidates.add(
                            candidate
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
