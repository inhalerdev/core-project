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
import org.bukkit.plugin.IllegalPluginAccessException;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

public final class OriginRtpLocationService {

    private static final int WORLD_SAFE_LIMIT = 29_999_000;
    private static final int HARD_MAX_CHUNK_LOAD_JOBS = 8;

    private final Core core;

    private final Map<String, ArrayDeque<Coordinates>>
            recentDestinations = new HashMap<>();

    /*
     * Every RTP search shares this one terrain-generation lane. Searches feed
     * one candidate at a time, which gives natural round-robin fairness when
     * several players are searching difficult terrain at once.
     */
    private final ArrayDeque<ChunkLoadJob> chunkLoadQueue =
            new ArrayDeque<>();

    /* Reference-counted because two countdowns can reserve the same chunk. */
    private final Map<ChunkTicketKey, Integer> chunkTicketRefs =
            new HashMap<>();
    private final Set<ChunkTicketKey> ownedChunkTickets =
            new HashSet<>();

    private boolean shuttingDown;
    private boolean drainingChunkLoads;
    private int activeChunkLoadJobs;
    private long lifecycleGeneration;
    private int maximumChunkLoadJobs = 2;

    public OriginRtpLocationService(Core core) {
        this.core = core;
        reload();
    }

    public void start() {
        lifecycleGeneration++;
        shuttingDown = false;
        activeChunkLoadJobs = 0;
        reload();
    }

    public void reload() {
        String path =
                "origin-rtp.search.max-candidate-load-jobs-at-once";
        int configured = core.getConfig().contains(path)
                ? core.getConfig().getInt(path, 2)
                : core.getConfig().getInt(
                "origin-rtp.search.max-chunk-load-jobs-at-once",
                2
        );

        maximumChunkLoadJobs = Math.clamp(
                configured,
                1,
                HARD_MAX_CHUNK_LOAD_JOBS
        );

        if (!shuttingDown) {
            drainChunkLoads();
        }
    }

    public void shutdown() {
        shuttingDown = true;
        lifecycleGeneration++;
        activeChunkLoadJobs = 0;

        while (!chunkLoadQueue.isEmpty()) {
            ChunkLoadJob job = chunkLoadQueue.pollFirst();

            if (job != null) {
                job.completion().cancel(false);
            }
        }

        releaseAllChunkTickets();
        recentDestinations.clear();
    }

    public boolean destinationAvailable(String destination) {
        if (shuttingDown) {
            return false;
        }

        OriginRtpSearchSettings settings =
                OriginRtpSearchSettings.fromConfig(
                        core,
                        destination
                );
        World world = Bukkit.getWorld(settings.worldName());

        return world != null
                && SearchArea.capture(
                world,
                settings
        ).valid();
    }

    /**
     * Finds a uniformly random safe destination inside the current live world
     * border. Candidate chunks load/generate asynchronously through a globally
     * bounded lane. Live block safety validation remains on the server thread.
     */
    public CompletableFuture<Location> findSafeLocation(
            String destination
    ) {
        CompletableFuture<Location> result =
                new CompletableFuture<>();

        runOnMain(
                () -> {
                    if (shuttingDown || result.isDone()) {
                        result.complete(null);
                        return;
                    }

                    try {
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

                        SearchRun run = new SearchRun(
                                world,
                                settings,
                                result
                        );
                        launchBatch(run);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                    }
                }
        );

        return result;
    }

    /**
     * Pins every chunk touched by final validation for the short RTP countdown.
     * Revalidation therefore never needs to synchronously reload terrain.
     */
    public ChunkReservation retainReservation(
            Location reserved,
            String destination
    ) {
        if (shuttingDown
                || reserved == null
                || reserved.getWorld() == null) {
            return null;
        }

        OriginRtpSearchSettings settings =
                OriginRtpSearchSettings.fromConfig(
                        core,
                        destination
                );
        World world = Bukkit.getWorld(
                settings.worldName()
        );

        if (world == null
                || world != reserved.getWorld()) {
            return null;
        }

        SearchArea area = SearchArea.capture(
                world,
                settings
        );
        int x = reserved.getBlockX();
        int z = reserved.getBlockZ();

        if (!area.valid() || !area.allows(x, z)) {
            return null;
        }

        int radius = validationRadius(settings);
        List<ChunkTicketKey> keys = requiredChunkKeys(
                world,
                x,
                z,
                radius
        );

        /* Refuse to turn retention into a synchronous chunk load. */
        for (ChunkTicketKey key : keys) {
            if (!world.isChunkLoaded(
                    key.chunkX(),
                    key.chunkZ()
            )) {
                return null;
            }
        }

        List<ChunkTicketKey> acquired =
                new ArrayList<>(keys.size());

        try {
            for (ChunkTicketKey key : keys) {
                int references = chunkTicketRefs.getOrDefault(
                        key,
                        0
                );

                if (references == 0) {
                    boolean added = world.addPluginChunkTicket(
                            key.chunkX(),
                            key.chunkZ(),
                            core
                    );

                    if (added) {
                        ownedChunkTickets.add(key);
                    }
                }

                chunkTicketRefs.put(
                        key,
                        references + 1
                );
                acquired.add(key);
            }

            return new ChunkReservation(
                    List.copyOf(keys)
            );
        } catch (RuntimeException exception) {
            releaseKeys(acquired);
            return null;
        }
    }

    public void releaseReservation(
            ChunkReservation reservation
    ) {
        if (reservation == null
                || reservation.released()) {
            return;
        }

        reservation.markReleased();
        releaseKeys(reservation.keys());
    }

    /**
     * Rechecks a reserved destination immediately before teleport execution.
     * The chunk reservation must still be alive and every validation chunk must
     * already be loaded.
     */
    public Location revalidateReservedLocation(
            Location reserved,
            String destination,
            ChunkReservation reservation
    ) {
        if (reserved == null
                || reserved.getWorld() == null
                || reservation == null
                || reservation.released()) {
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

        if (!area.valid()
                || !area.allows(x, z)
                || !requiredChunksLoaded(
                expected,
                x,
                z,
                validationRadius(settings)
        )) {
            return null;
        }

        int groundY = reserved.getBlockY() - 1;
        int minimumY = settings.clampedMinimumY(expected);
        int maximumY = settings.clampedMaximumY(expected);

        if (safeLandingAt(
                expected,
                x,
                groundY,
                z,
                settings,
                minimumY,
                maximumY
        )) {
            return new Location(
                    expected,
                    x + 0.5D,
                    groundY + 1.0D,
                    z + 0.5D,
                    reserved.getYaw(),
                    reserved.getPitch()
            );
        }

        return null;
    }

    private void launchBatch(SearchRun run) {
        if (shuttingDown || run.result().isDone()) {
            return;
        }

        if (run.attemptsUsed()
                >= run.settings().maximumAttempts()) {
            run.result().complete(null);
            return;
        }

        /* Live border/spawn are captured again for every candidate batch. */
        SearchArea area = SearchArea.capture(
                run.world(),
                run.settings()
        );

        if (!area.valid()) {
            run.result().complete(null);
            return;
        }

        int batchSize = Math.min(
                run.settings().candidatesPerBatch(),
                run.settings().maximumAttempts()
                        - run.attemptsUsed()
        );
        int poolSize = Math.clamp(
                Math.multiplyExact(
                        batchSize,
                        run.settings()
                                .candidatePoolMultiplier()
                ),
                batchSize,
                64
        );

        List<Coordinates> candidates =
                prioritizeCandidates(
                        area,
                        area.randomCandidates(poolSize),
                        batchSize,
                        run.settings()
                );

        if (candidates.isEmpty()) {
            run.result().complete(null);
            return;
        }

        evaluateCandidate(
                run,
                candidates,
                0
        );
    }

    private void evaluateCandidate(
            SearchRun run,
            List<Coordinates> candidates,
            int index
    ) {
        if (shuttingDown || run.result().isDone()) {
            return;
        }

        if (run.attemptsUsed()
                >= run.settings().maximumAttempts()) {
            run.result().complete(null);
            return;
        }

        if (index >= candidates.size()) {
            launchBatch(run);
            return;
        }

        Coordinates candidate = candidates.get(index);
        run.incrementAttempts();

        SearchArea beforeLoad = SearchArea.capture(
                run.world(),
                run.settings()
        );

        if (!beforeLoad.valid()
                || !beforeLoad.allows(
                candidate.x(),
                candidate.z()
        )) {
            evaluateCandidate(
                    run,
                    candidates,
                    index + 1
            );
            return;
        }

        loadRequiredChunksBounded(
                run.world(),
                candidate,
                validationRadius(run.settings()),
                run.result(),
                () -> {
                    SearchArea current = SearchArea.capture(
                            run.world(),
                            run.settings()
                    );

                    return current.valid()
                            && current.allows(
                            candidate.x(),
                            candidate.z()
                    );
                }
        ).whenComplete(
                (ignored, throwable) ->
                        runSearchOnMain(
                                run,
                                () -> validateCandidate(
                                        run,
                                        candidates,
                                        index,
                                        candidate,
                                        throwable
                                )
                        )
        );
    }

    private void validateCandidate(
            SearchRun run,
            List<Coordinates> candidates,
            int index,
            Coordinates candidate,
            Throwable throwable
    ) {
        if (shuttingDown || run.result().isDone()) {
            return;
        }

        if (throwable == null) {
            SearchArea current = SearchArea.capture(
                    run.world(),
                    run.settings()
            );

            if (current.valid()
                    && current.allows(
                    candidate.x(),
                    candidate.z()
            )
                    && requiredChunksLoaded(
                    run.world(),
                    candidate.x(),
                    candidate.z(),
                    validationRadius(run.settings())
            )) {
                Location safe = safeLocationAt(
                        run.world(),
                        candidate.x(),
                        candidate.z(),
                        run.settings()
                );

                int recentDistance =
                        current.effectiveRecentDistance(
                                run.settings()
                                        .minimumRecentDestinationDistance()
                        );

                /*
                 * Recheck after async work so two concurrent successes cannot
                 * reserve nearby regions based on the same older history view.
                 */
                if (safe != null
                        && farFromRecent(
                        candidate,
                        recentSnapshot(run.settings()),
                        recentDistance
                )) {
                    rememberDestination(
                            run.settings(),
                            candidate
                    );
                    run.result().complete(safe);
                    return;
                }
            }
        }

        evaluateCandidate(
                run,
                candidates,
                index + 1
        );
    }

    private CompletableFuture<Void> loadRequiredChunksBounded(
            World world,
            Coordinates candidate,
            int radius,
            CompletableFuture<Location> owner,
            BooleanSupplier preflight
    ) {
        CompletableFuture<Void> completion =
                new CompletableFuture<>();

        if (shuttingDown || owner.isDone()) {
            completion.cancel(false);
            return completion;
        }

        chunkLoadQueue.addLast(
                new ChunkLoadJob(
                        lifecycleGeneration,
                        world,
                        candidate,
                        radius,
                        owner,
                        preflight,
                        completion
                )
        );
        drainChunkLoads();
        return completion;
    }

    private void drainChunkLoads() {
        if (shuttingDown || drainingChunkLoads) {
            return;
        }

        drainingChunkLoads = true;

        try {
            while (!shuttingDown
                    && activeChunkLoadJobs
                    < maximumChunkLoadJobs
                    && !chunkLoadQueue.isEmpty()) {
                ChunkLoadJob job =
                        chunkLoadQueue.pollFirst();

                if (job == null) {
                    continue;
                }

                if (job.lifecycleGeneration() != lifecycleGeneration
                        || job.owner().isDone()) {
                    job.completion().cancel(false);
                    continue;
                }

                boolean valid;

                try {
                    valid = job.preflight().getAsBoolean();
                } catch (RuntimeException exception) {
                    valid = false;
                }

                if (!valid) {
                    job.completion().cancel(false);
                    continue;
                }

                activeChunkLoadJobs++;
                startChunkLoad(job);
            }
        } finally {
            drainingChunkLoads = false;
        }
    }

    private void startChunkLoad(ChunkLoadJob job) {
        int minimumChunkX =
                (job.candidate().x() - job.radius()) >> 4;
        int maximumChunkX =
                (job.candidate().x() + job.radius()) >> 4;
        int minimumChunkZ =
                (job.candidate().z() - job.radius()) >> 4;
        int maximumChunkZ =
                (job.candidate().z() + job.radius()) >> 4;

        List<CompletableFuture<Chunk>> futures =
                new ArrayList<>(4);

        try {
            for (int chunkX = minimumChunkX;
                 chunkX <= maximumChunkX;
                 chunkX++) {
                for (int chunkZ = minimumChunkZ;
                     chunkZ <= maximumChunkZ;
                     chunkZ++) {
                    futures.add(
                            job.world().getChunkAtAsync(
                                    chunkX,
                                    chunkZ,
                                    true
                            )
                    );
                }
            }
        } catch (RuntimeException exception) {
            finishChunkLoad(job, exception);
            return;
        }

        CompletableFuture.allOf(
                futures.toArray(
                        CompletableFuture[]::new
                )
        ).whenComplete(
                (ignored, throwable) -> {
                    if (shuttingDown
                            || job.lifecycleGeneration()
                            != lifecycleGeneration
                            || !core.isEnabled()) {
                        job.completion().cancel(false);
                        return;
                    }

                    runOnMain(
                            () -> finishChunkLoad(
                                    job,
                                    throwable
                            )
                    );
                }
        );
    }

    private void finishChunkLoad(
            ChunkLoadJob job,
            Throwable throwable
    ) {
        if (job.lifecycleGeneration() != lifecycleGeneration) {
            job.completion().cancel(false);
            return;
        }

        activeChunkLoadJobs = Math.max(
                0,
                activeChunkLoadJobs - 1
        );

        if (shuttingDown || job.owner().isDone()) {
            job.completion().cancel(false);
        } else if (throwable != null) {
            job.completion().completeExceptionally(
                    throwable
            );
        } else {
            job.completion().complete(null);
        }

        drainChunkLoads();
    }

    private List<Coordinates> prioritizeCandidates(
            SearchArea area,
            List<Coordinates> pool,
            int requested,
            OriginRtpSearchSettings settings
    ) {
        if (pool.isEmpty() || requested <= 0) {
            return List.of();
        }

        List<Coordinates> selected =
                new ArrayList<>(requested);
        List<Coordinates> recent =
                recentSnapshot(settings);
        int recentDistance =
                area.effectiveRecentDistance(
                        settings.minimumRecentDestinationDistance()
                );

        /*
         * Do not call World#isChunkGenerated here. Paper's implementation can
         * synchronously wait for chunk I/O on the main thread. Uniform full-
         * border sampling plus recent-region spacing gives Mineacle the same
         * exploration behavior without a tick-thread disk probe.
         */
        for (Coordinates candidate : pool) {
            if (selected.size() >= requested) {
                break;
            }

            if (farFromRecent(
                    candidate,
                    recent,
                    recentDistance
            )) {
                selected.add(candidate);
            }
        }

        if (selected.size() >= requested) {
            return List.copyOf(selected);
        }

        Set<Long> used = new HashSet<>();

        for (Coordinates coordinate : selected) {
            used.add(coordinate.packed());
        }

        for (Coordinates candidate : pool) {
            if (selected.size() >= requested) {
                break;
            }

            if (used.add(candidate.packed())) {
                selected.add(candidate);
            }
        }

        return List.copyOf(selected);
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
                (long) minimumDistance
                        * minimumDistance;

        for (Coordinates previous : recent) {
            long deltaX =
                    (long) candidate.x()
                            - previous.x();
            long deltaZ =
                    (long) candidate.z()
                            - previous.z();

            if (deltaX * deltaX
                    + deltaZ * deltaZ
                    < minimumSquared) {
                return false;
            }
        }

        return true;
    }

    private List<Coordinates> recentSnapshot(
            OriginRtpSearchSettings settings
    ) {
        ArrayDeque<Coordinates> history =
                recentDestinations.get(
                        historyKey(settings)
                );

        return history == null || history.isEmpty()
                ? List.of()
                : List.copyOf(history);
    }

    private void rememberDestination(
            OriginRtpSearchSettings settings,
            Coordinates coordinates
    ) {
        int maximum =
                settings.recentDestinationHistory();

        if (maximum <= 0) {
            return;
        }

        ArrayDeque<Coordinates> history =
                recentDestinations.computeIfAbsent(
                        historyKey(settings),
                        ignored -> new ArrayDeque<>()
                );
        history.addFirst(coordinates);

        while (history.size() > maximum) {
            history.removeLast();
        }
    }

    private String historyKey(
            OriginRtpSearchSettings settings
    ) {
        return settings.destination()
                + "|"
                + settings.worldName()
                .toLowerCase(Locale.ROOT);
    }

    private Location safeLocationAt(
            World world,
            int x,
            int z,
            OriginRtpSearchSettings settings
    ) {
        int minimumY =
                settings.clampedMinimumY(world);
        int maximumY =
                settings.clampedMaximumY(world);

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

        if (safeLandingAt(
                world,
                x,
                centerGroundY,
                z,
                settings,
                minimumY,
                maximumY
        )) {
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

        return null;
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
        int startY =
                settings.randomizedVerticalSearch()
                        ? ThreadLocalRandom.current()
                        .nextInt(
                                minimumY,
                                maximumY + 1
                        )
                        : maximumY;

        for (int offset = 0;
             offset < height;
             offset++) {
            int groundY =
                    minimumY + Math.floorMod(
                            startY
                                    - minimumY
                                    - offset,
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

        if (radius <= 0) {
            return true;
        }

        int maximumDifference =
                settings.maximumGroundHeightDifference();

        for (int x = centerX - radius;
             x <= centerX + radius;
             x++) {
            for (int z = centerZ - radius;
                 z <= centerZ + radius;
                 z++) {
                int nearbyGroundY =
                        findNearbyGroundY(
                                world,
                                x,
                                z,
                                centerGroundY,
                                maximumDifference,
                                settings,
                                minimumY,
                                maximumY
                        );

                if (nearbyGroundY
                        == Integer.MIN_VALUE) {
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
        int minimumY = Math.max(
                world.getMinHeight(),
                groundY - 1
        );
        int maximumY = Math.min(
                world.getMaxHeight() - 1,
                groundY + 3
        );

        for (int x = centerX - radius;
             x <= centerX + radius;
             x++) {
            for (int z = centerZ - radius;
                 z <= centerZ + radius;
                 z++) {
                for (int y = minimumY;
                     y <= maximumY;
                     y++) {
                    if (unsafeMaterial(
                            world.getBlockAt(x, y, z),
                            settings
                    )) {
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
        return block.isLiquid()
                || isWaterlogged(block)
                || settings.unsafeBlocks()
                .contains(block.getType());
    }

    private boolean isWaterlogged(Block block) {
        return block.getBlockData()
                instanceof Waterlogged waterlogged
                && waterlogged.isWaterlogged();
    }

    private int validationRadius(
            OriginRtpSearchSettings settings
    ) {
        return Math.max(
                settings.safePlatformRadius(),
                settings.hazardCheckRadius()
        );
    }

    private boolean requiredChunksLoaded(
            World world,
            int x,
            int z,
            int radius
    ) {
        int minimumChunkX = (x - radius) >> 4;
        int maximumChunkX = (x + radius) >> 4;
        int minimumChunkZ = (z - radius) >> 4;
        int maximumChunkZ = (z + radius) >> 4;

        for (int chunkX = minimumChunkX;
             chunkX <= maximumChunkX;
             chunkX++) {
            for (int chunkZ = minimumChunkZ;
                 chunkZ <= maximumChunkZ;
                 chunkZ++) {
                if (!world.isChunkLoaded(
                        chunkX,
                        chunkZ
                )) {
                    return false;
                }
            }
        }

        return true;
    }

    private List<ChunkTicketKey> requiredChunkKeys(
            World world,
            int x,
            int z,
            int radius
    ) {
        int minimumChunkX = (x - radius) >> 4;
        int maximumChunkX = (x + radius) >> 4;
        int minimumChunkZ = (z - radius) >> 4;
        int maximumChunkZ = (z + radius) >> 4;
        List<ChunkTicketKey> keys =
                new ArrayList<>(4);

        for (int chunkX = minimumChunkX;
             chunkX <= maximumChunkX;
             chunkX++) {
            for (int chunkZ = minimumChunkZ;
                 chunkZ <= maximumChunkZ;
                 chunkZ++) {
                keys.add(
                        new ChunkTicketKey(
                                world.getUID(),
                                chunkX,
                                chunkZ
                        )
                );
            }
        }

        return List.copyOf(keys);
    }

    private void releaseKeys(
            List<ChunkTicketKey> keys
    ) {
        for (ChunkTicketKey key : keys) {
            Integer references = chunkTicketRefs.get(key);

            if (references == null) {
                continue;
            }

            if (references > 1) {
                chunkTicketRefs.put(
                        key,
                        references - 1
                );
                continue;
            }

            chunkTicketRefs.remove(key);

            /*
             * A plugin ticket is unique per plugin/chunk. If another future
             * MineacleCore subsystem already owned the ticket when RTP arrived,
             * RTP must not remove that subsystem's retention on release.
             */
            if (!ownedChunkTickets.remove(key)) {
                continue;
            }

            World world = Bukkit.getWorld(
                    key.worldId()
            );

            if (world == null
                    || !world.isChunkLoaded(
                    key.chunkX(),
                    key.chunkZ()
            )) {
                continue;
            }

            try {
                world.removePluginChunkTicket(
                        key.chunkX(),
                        key.chunkZ(),
                        core
                );
            } catch (RuntimeException ignored) {
                // World/plugin shutdown can race the final ticket release.
            }
        }
    }

    private void releaseAllChunkTickets() {
        List<ChunkTicketKey> keys =
                List.copyOf(ownedChunkTickets);
        chunkTicketRefs.clear();
        ownedChunkTickets.clear();

        for (ChunkTicketKey key : keys) {
            World world = Bukkit.getWorld(
                    key.worldId()
            );

            if (world == null
                    || !world.isChunkLoaded(
                    key.chunkX(),
                    key.chunkZ()
            )) {
                continue;
            }

            try {
                world.removePluginChunkTicket(
                        key.chunkX(),
                        key.chunkZ(),
                        core
                );
            } catch (RuntimeException ignored) {
                // Plugin is shutting down.
            }
        }
    }

    private void runSearchOnMain(
            SearchRun run,
            Runnable action
    ) {
        runOnMain(
                () -> {
                    if (run.result().isDone()) {
                        return;
                    }

                    try {
                        action.run();
                    } catch (RuntimeException exception) {
                        run.result().completeExceptionally(exception);
                    }
                }
        );
    }

    private void runOnMain(Runnable action) {
        if (shuttingDown) {
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }

        if (!core.isEnabled()) {
            return;
        }

        try {
            Bukkit.getScheduler().runTask(
                    core,
                    () -> {
                        if (!shuttingDown
                                && core.isEnabled()) {
                            action.run();
                        }
                    }
            );
        } catch (IllegalPluginAccessException ignored) {
            // Plugin is shutting down.
        }
    }

    public static final class ChunkReservation {

        private final List<ChunkTicketKey> keys;
        private boolean released;

        private ChunkReservation(
                List<ChunkTicketKey> keys
        ) {
            this.keys = keys;
        }

        private List<ChunkTicketKey> keys() {
            return keys;
        }

        private boolean released() {
            return released;
        }

        private void markReleased() {
            released = true;
        }
    }

    private static final class SearchRun {

        private final World world;
        private final OriginRtpSearchSettings settings;
        private final CompletableFuture<Location> result;
        private int attemptsUsed;

        private SearchRun(
                World world,
                OriginRtpSearchSettings settings,
                CompletableFuture<Location> result
        ) {
            this.world = world;
            this.settings = settings;
            this.result = result;
        }

        private World world() {
            return world;
        }

        private OriginRtpSearchSettings settings() {
            return settings;
        }

        private CompletableFuture<Location> result() {
            return result;
        }

        private int attemptsUsed() {
            return attemptsUsed;
        }

        private void incrementAttempts() {
            attemptsUsed++;
        }
    }

    private record ChunkLoadJob(
            long lifecycleGeneration,
            World world,
            Coordinates candidate,
            int radius,
            CompletableFuture<Location> owner,
            BooleanSupplier preflight,
            CompletableFuture<Void> completion
    ) {
    }

    private record ChunkTicketKey(
            UUID worldId,
            int chunkX,
            int chunkZ
    ) {
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
                double halfSize =
                        border.getSize() / 2.0D;
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
                minimumX =
                        (long) settings.fallbackCenterX()
                                - radius;
                maximumX =
                        (long) settings.fallbackCenterX()
                                + radius;
                minimumZ =
                        (long) settings.fallbackCenterZ()
                                - radius;
                maximumZ =
                        (long) settings.fallbackCenterZ()
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
                         && candidates.size()
                         < requested;
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
                    candidates.add(candidate);
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

            double deltaX =
                    x + 0.5D - spawnX;
            double deltaZ =
                    z + 0.5D - spawnZ;
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

        private int effectiveRecentDistance(
                int configured
        ) {
            if (configured <= 0 || !valid) {
                return 0;
            }

            long width = maximumX - minimumX + 1L;
            long depth = maximumZ - minimumZ + 1L;
            long shortest = Math.min(width, depth);

            if (shortest <= 0L) {
                return 0;
            }

            /*
             * Avoid making small custom borders mathematically impractical.
             * Mineacle's large worlds are unchanged by this cap.
             */
            long sensibleCap = Math.max(
                    256L,
                    shortest / 6L
            );

            return (int) Math.min(
                    configured,
                    Math.min(
                            sensibleCap,
                            Integer.MAX_VALUE
                    )
            );
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
