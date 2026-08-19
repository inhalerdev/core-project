package net.mineacle.core.warp.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportMovement;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.warp.model.WarpPoint;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WarpTeleportService {

    private static final String ERROR = "&c";
    private static final String BODY = "&#bbbbbb";
    private static final String SECONDARY = "&#B078FF";

    private static final int MAINTENANCE_INTERVAL_TICKS = 20;
    private static final long NANOS_PER_SECOND =
            1_000_000_000L;

    private final Core core;
    private final WarpService warpService;
    private final TeleportService teleportService;

    /*
     * queuedByPlayer is authoritative membership and gives O(1) lookup/removal.
     * Each priority lane is a LinkedHashSet so the head is FIFO and arbitrary
     * player removal is also expected O(1).
     */
    private final Map<UUID, QueueSession> queuedByPlayer =
            new HashMap<>();
    private final Map<Integer, LinkedHashSet<UUID>> priorityLanes =
            new HashMap<>();

    /*
     * active tracks only Warp pipelines admitted from the queue. It is bounded
     * by queue.max-active and is reconciled against the shared TeleportService.
     */
    private final Set<UUID> active =
            new HashSet<>();

    private WarpService.QueueSettings settings;
    private BukkitTask processorTask;
    private int maintenanceTicks;

    public WarpTeleportService(
            Core core,
            WarpService warpService,
            TeleportService teleportService
    ) {
        this.core = core;
        this.warpService = warpService;
        this.teleportService = teleportService;
        this.settings = warpService.queueSettings();
    }

    public void start() {
        if (processorTask != null) {
            return;
        }

        settings = warpService.queueSettings();
        processorTask =
                core.getServer()
                        .getScheduler()
                        .runTaskTimer(
                                core,
                                this::tickQueue,
                                1L,
                                1L
                        );
    }

    public void stop() {
        if (processorTask != null) {
            processorTask.cancel();
            processorTask = null;
        }

        List<UUID> queued =
                new ArrayList<>(
                        queuedByPlayer.keySet()
                );

        for (UUID playerId : queued) {
            removeQueued(
                    playerId,
                    false,
                    null
            );
        }

        List<UUID> admitted =
                new ArrayList<>(active);

        active.clear();

        for (UUID playerId : admitted) {
            teleportService.cancel(
                    playerId,
                    false
            );
        }

        priorityLanes.clear();
        queuedByPlayer.clear();
        maintenanceTicks = 0;
    }

    public void teleport(
            Player player,
            WarpPoint point
    ) {
        if (player == null
                || point == null
                || !player.isOnline()) {
            return;
        }

        Location target =
                warpService.targetLocation(
                        player,
                        point
                );
        String targetName =
                displayName(point);

        if (target == null
                || target.getWorld() == null) {
            fail(
                    player,
                    warpService.queueMessage(
                            "destination-unavailable",
                            ERROR
                                    + "Warp destination is unavailable",
                            targetName
                    )
            );
            return;
        }

        /*
         * Spawn1-3 are intentionally the fast path:
         * no admission queue and no countdown.
         *
         * Use the reserved-location entry point because the generic
         * beginLocation(..., WARP, 0, ...) path normalizes a zero-second Warp
         * back to the standard 5s/3s countdown. A reservation preserves normal
         * overlap protection while beginReservedLocation() honors the explicit
         * zero delay for this intentional spawn-only fast path.
         */
        if (warpService.isSpawnWorld(player)) {
            if (!teleportService.reserve(
                    player,
                    TeleportService.TeleportKind.WARP
            )) {
                fail(
                        player,
                        warpService.queueMessage(
                                "already-active",
                                ERROR
                                        + "You already have a teleport in progress",
                                targetName
                        )
                );
                return;
            }

            boolean started =
                    teleportService.beginReservedLocation(
                            player,
                            targetName,
                            target::clone,
                            TeleportService.TeleportKind.WARP,
                            0,
                            false,
                            null,
                            null
                    );

            if (!started) {
                teleportService.releaseReservation(
                        player.getUniqueId(),
                        TeleportService.TeleportKind.WARP
                );
            }
            return;
        }

        WarpService.WarpProfile profile =
                warpService.profile(player);

        if (!settings.enabled()) {
            teleportService.beginLocation(
                    player,
                    targetName,
                    target,
                    TeleportService.TeleportKind.WARP,
                    profile.delaySeconds(),
                    warpService.cancelOnMove()
            );
            return;
        }

        enqueue(
                player,
                targetName,
                target,
                profile
        );
    }

    public void handleMove(
            Player player,
            Location destination
    ) {
        if (player == null
                || destination == null
                || !warpService.cancelOnMove()) {
            return;
        }

        QueueSession session =
                queuedByPlayer.get(
                        player.getUniqueId()
                );

        if (session == null) {
            return;
        }

        if (TeleportMovement.movedTooFar(
                core,
                session.origin(),
                destination
        )) {
            removeQueued(
                    player.getUniqueId(),
                    true,
                    warpService.queueMessage(
                            "cancelled",
                            ERROR
                                    + "Teleport cancelled — you moved",
                            session.displayTarget()
                    )
            );
        }
    }

    public void handleTeleport(
            Player player,
            Location destination
    ) {
        handleMove(
                player,
                destination
        );
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId =
                player.getUniqueId();

        removeQueued(
                playerId,
                false,
                null
        );

        if (active.remove(playerId)) {
            teleportService.cancel(
                    playerId,
                    false
            );
        }
    }

    public void handleDeath(Player player) {
        handleQuit(player);
    }

    public void handleRespawn(Player player) {
        handleQuit(player);
    }

    private void enqueue(
            Player player,
            String displayTarget,
            Location target,
            WarpService.WarpProfile profile
    ) {
        UUID playerId =
                player.getUniqueId();

        if (queuedByPlayer.containsKey(playerId)
                || active.contains(playerId)
                || teleportService.isActive(playerId)) {
            fail(
                    player,
                    warpService.queueMessage(
                            "already-active",
                            ERROR
                                    + "You already have a teleport in progress",
                            displayTarget
                    )
            );
            return;
        }

        if (queuedByPlayer.size()
                >= settings.maxQueued()) {
            fail(
                    player,
                    warpService.queueMessage(
                            "queue-full",
                            ERROR
                                    + "Warp queue is busy — try again",
                            displayTarget
                    )
            );
            return;
        }

        if (!teleportService.reserve(
                player,
                TeleportService.TeleportKind.WARP
        )) {
            fail(
                    player,
                    warpService.queueMessage(
                            "already-active",
                            ERROR
                                    + "You already have a teleport in progress",
                            displayTarget
                    )
            );
            return;
        }

        boolean visiblyQueued =
                active.size()
                        >= settings.maxActive()
                        || !queuedByPlayer.isEmpty();

        QueueSession session =
                new QueueSession(
                        playerId,
                        player.getLocation()
                                .clone(),
                        target.clone(),
                        displayTarget,
                        profile.delaySeconds(),
                        profile.priority(),
                        System.nanoTime()
                );

        queuedByPlayer.put(
                playerId,
                session
        );
        priorityLanes
                .computeIfAbsent(
                        session.basePriority(),
                        ignored ->
                                new LinkedHashSet<>()
                )
                .add(playerId);

        if (visiblyQueued) {
            sendActionBar(
                    player,
                    warpService.queueMessage(
                            "queued",
                            BODY
                                    + "Warp queued — preparing "
                                    + SECONDARY
                                    + "%warp%",
                            displayTarget
                    )
            );
        }
    }

    private void tickQueue() {
        maintenanceTicks++;

        if (maintenanceTicks
                >= MAINTENANCE_INTERVAL_TICKS) {
            maintenanceTicks = 0;
            maintain();
        }

        if (queuedByPlayer.isEmpty()
                || active.size()
                >= settings.maxActive()) {
            return;
        }

        long now = System.nanoTime();
        int starts = 0;

        while (starts
                < settings.maxStartsPerTick()
                && active.size()
                < settings.maxActive()) {
            QueueSession session =
                    takeNext(now);

            if (session == null) {
                break;
            }

            Player player =
                    core.getServer()
                            .getPlayer(
                                    session.playerId()
                            );

            if (player == null
                    || !player.isOnline()) {
                teleportService.releaseReservation(
                        session.playerId(),
                        TeleportService.TeleportKind.WARP
                );
                continue;
            }

            if (warpService.cancelOnMove()
                    && TeleportMovement.movedTooFar(
                    core,
                    session.origin(),
                    player.getLocation()
            )) {
                teleportService.releaseReservation(
                        session.playerId(),
                        TeleportService.TeleportKind.WARP
                );
                sendActionBar(
                        player,
                        warpService.queueMessage(
                                "cancelled",
                                ERROR
                                        + "Teleport cancelled — you moved",
                                session.displayTarget()
                        )
                );
                SoundService.negative(
                        player,
                        core
                );
                continue;
            }

            UUID playerId =
                    session.playerId();

            active.add(playerId);

            boolean started =
                    teleportService.beginReservedLocation(
                            player,
                            session.displayTarget(),
                            session.target()::clone,
                            TeleportService.TeleportKind.WARP,
                            session.delaySeconds(),
                            warpService.cancelOnMove(),
                            () -> active.remove(
                                    playerId
                            ),
                            reason -> active.remove(
                                    playerId
                            )
                    );

            if (!started) {
                active.remove(playerId);
                teleportService.releaseReservation(
                        playerId,
                        TeleportService.TeleportKind.WARP
                );
            }

            starts++;
        }
    }

    private QueueSession takeNext(
            long now
    ) {
        QueueSession best = null;
        long bestScore = Long.MIN_VALUE;

        for (Map.Entry<Integer, LinkedHashSet<UUID>> entry :
                priorityLanes.entrySet()) {
            QueueSession candidate =
                    firstValid(
                            entry.getValue()
                    );

            if (candidate == null) {
                continue;
            }

            long score =
                    effectivePriority(
                            candidate,
                            now
                    );

            if (best == null
                    || score > bestScore
                    || (score == bestScore
                    && olderThan(
                    candidate,
                    best
            ))) {
                best = candidate;
                bestScore = score;
            }
        }

        if (best == null) {
            return null;
        }

        removeFromStructures(best);
        return best;
    }

    private QueueSession firstValid(
            LinkedHashSet<UUID> lane
    ) {
        while (!lane.isEmpty()) {
            UUID first =
                    lane.getFirst();
            QueueSession session =
                    queuedByPlayer.get(first);

            if (session != null) {
                return session;
            }

            lane.remove(first);
        }

        return null;
    }

    private long effectivePriority(
            QueueSession session,
            long now
    ) {
        long waitedNanos =
                Math.max(
                        0L,
                        now - session.enqueuedNanos()
                );
        long waitedSeconds =
                waitedNanos / NANOS_PER_SECOND;
        long aging =
                Math.min(
                        settings.maxAgingBonus(),
                        waitedSeconds
                                * (long) settings
                                .agingPointsPerSecond()
                );

        return session.basePriority()
                + aging;
    }

    private boolean olderThan(
            QueueSession first,
            QueueSession second
    ) {
        if (first.enqueuedNanos()
                != second.enqueuedNanos()) {
            return first.enqueuedNanos()
                    < second.enqueuedNanos();
        }

        return first.playerId()
                .compareTo(
                        second.playerId()
                ) < 0;
    }

    private void maintain() {
        expireQueued();
        reconcileActive();
    }

    private void expireQueued() {
        if (queuedByPlayer.isEmpty()) {
            return;
        }

        long now = System.nanoTime();
        long maxWaitNanos =
                settings.maxWaitSeconds()
                        * NANOS_PER_SECOND;
        List<QueueSession> sessions =
                new ArrayList<>(
                        queuedByPlayer.values()
                );

        for (QueueSession session : sessions) {
            if (now - session.enqueuedNanos()
                    <= maxWaitNanos) {
                continue;
            }

            Player player =
                    core.getServer()
                            .getPlayer(
                                    session.playerId()
                            );

            removeQueued(
                    session.playerId(),
                    player != null
                            && player.isOnline(),
                    warpService.queueMessage(
                            "queue-expired",
                            ERROR
                                    + "Warp queue expired — try again",
                            session.displayTarget()
                    )
            );
        }
    }

    private void reconcileActive() {
        if (active.isEmpty()) {
            return;
        }

        List<UUID> snapshot =
                new ArrayList<>(active);

        for (UUID playerId : snapshot) {
            if (!teleportService.isActive(
                    playerId
            )) {
                active.remove(playerId);
            }
        }
    }

    private void removeQueued(
            UUID playerId,
            boolean notify,
            String message
    ) {
        QueueSession session =
                queuedByPlayer.remove(playerId);

        if (session == null) {
            return;
        }

        LinkedHashSet<UUID> lane =
                priorityLanes.get(
                        session.basePriority()
                );

        if (lane != null) {
            lane.remove(playerId);

            if (lane.isEmpty()) {
                priorityLanes.remove(
                        session.basePriority()
                );
            }
        }

        teleportService.releaseReservation(
                playerId,
                TeleportService.TeleportKind.WARP
        );

        if (!notify) {
            return;
        }

        Player player =
                core.getServer()
                        .getPlayer(playerId);

        if (player == null
                || !player.isOnline()) {
            return;
        }

        sendActionBar(
                player,
                message == null
                        ? ERROR
                        + "Teleport cancelled"
                        : message
        );
        SoundService.negative(
                player,
                core
        );
    }

    private void removeFromStructures(
            QueueSession session
    ) {
        queuedByPlayer.remove(
                session.playerId()
        );

        LinkedHashSet<UUID> lane =
                priorityLanes.get(
                        session.basePriority()
                );

        if (lane == null) {
            return;
        }

        lane.remove(
                session.playerId()
        );

        if (lane.isEmpty()) {
            priorityLanes.remove(
                    session.basePriority()
            );
        }
    }

    private void fail(
            Player player,
            String message
    ) {
        if (player == null
                || !player.isOnline()) {
            return;
        }

        sendActionBar(
                player,
                message
        );
        SoundService.negative(
                player,
                core
        );
    }

    private void sendActionBar(
            Player player,
            String message
    ) {
        Component component =
                LegacyComponentSerializer
                        .legacySection()
                        .deserialize(
                                TextColor.color(
                                        message
                                )
                        );

        player.sendActionBar(component);
    }

    private String displayName(
            WarpPoint point
    ) {
        String stripped =
                TextColor.strip(
                        TextColor.color(
                                point.displayName()
                        )
                );

        return stripped.isBlank()
                ? point.key()
                : stripped;
    }

    private record QueueSession(
            UUID playerId,
            Location origin,
            Location target,
            String displayTarget,
            int delaySeconds,
            int basePriority,
            long enqueuedNanos
    ) {
    }
}
