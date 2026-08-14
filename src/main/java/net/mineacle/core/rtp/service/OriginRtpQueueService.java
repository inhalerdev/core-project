package net.mineacle.core.rtp.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportMovement;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OriginRtpQueueService {

    private static final int DEFAULT_COUNTDOWN_SECONDS = 4;
    private static final int PLUS_COUNTDOWN_SECONDS = 3;

    private static final int HARD_MAX_CONCURRENT_SEARCHES = 8;
    private static final int HARD_MAX_QUEUED_REQUESTS = 4096;
    private static final int HARD_MAX_SEARCH_PASSES = 3;

    private static final long NANOS_PER_SECOND = 1_000_000_000L;
    private static final long FAILURE_LOG_INTERVAL_NANOS =
            30L * NANOS_PER_SECOND;

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String ACCENT = "&#D0AFFF";
    private static final String BODY = "&#bbbbbb";

    private enum Phase {
        QUEUED,
        SEARCHING,
        COUNTDOWN
    }

    private final Core core;
    private final OriginRtpLocationService locationService;
    private final TeleportService teleportService;

    /*
     * Stable FIFO order plus O(1) arbitrary removal. This prevents mass
     * disconnect/movement cancellation from becoming quadratic with a large
     * queue.
     */
    private final LinkedHashSet<UUID> plusQueue = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> defaultQueue = new LinkedHashSet<>();

    private final Map<UUID, Session> sessionsByPlayer = new HashMap<>();

    /* Only actively-searching sessions live here; this map is hard-bounded. */
    private final Map<UUID, Session> searchingSessions = new HashMap<>();

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Long> landingProtection = new HashMap<>();

    private volatile boolean running;

    private QueueSettings settings;
    private BukkitTask processorTask;
    private int consecutivePlus;
    private long nextMaintenanceNanos;
    private long lastFailureLogNanos;

    public OriginRtpQueueService(
            Core core,
            TeleportService teleportService
    ) {
        this.core = core;
        this.teleportService = teleportService;
        this.locationService = new OriginRtpLocationService(core);
        this.settings = QueueSettings.fromConfig(core);
    }

    public void start() {
        if (running) {
            return;
        }

        running = true;
        settings = QueueSettings.fromConfig(core);
        locationService.start();
        rebuildQueuedSessions();
        scheduleProcessor();
        nextMaintenanceNanos = System.nanoTime()
                + settings.maintenanceIntervalNanos();
    }

    public void reload() {
        QueueSettings previous = settings;
        settings = QueueSettings.fromConfig(core);
        locationService.reload();
        rebuildQueuedSessions();

        if (running
                && (processorTask == null
                || previous.processEveryTicks()
                != settings.processEveryTicks())) {
            scheduleProcessor();
        }

        nextMaintenanceNanos = System.nanoTime()
                + settings.maintenanceIntervalNanos();
    }

    public void stop() {
        if (!running
                && processorTask == null
                && sessionsByPlayer.isEmpty()) {
            locationService.shutdown();
            return;
        }

        running = false;
        stopProcessorOnly();

        List<Session> sessions = List.copyOf(
                sessionsByPlayer.values()
        );

        /*
         * Clear ownership before cancelling TeleportService. Its failure
         * callback can call back into this service synchronously.
         */
        plusQueue.clear();
        defaultQueue.clear();
        searchingSessions.clear();
        sessionsByPlayer.clear();
        consecutivePlus = 0;

        for (Session session : sessions) {
            session.cancelSearchFuture();
            session.releaseChunkReservation(locationService);

            UUID playerId = session.request().playerId();
            teleportService.releaseReservation(
                    playerId,
                    TeleportService.TeleportKind.RTP
            );
            teleportService.cancel(playerId, false);
        }

        cooldowns.clear();
        landingProtection.clear();
        locationService.shutdown();
    }

    public boolean request(Player player) {
        return request(player, "overworld");
    }

    public boolean request(
            Player player,
            String rawDestination
    ) {
        if (!running
                || player == null
                || !player.isOnline()) {
            return false;
        }

        String destination =
                OriginRtpSearchSettings.canonicalDestination(
                        rawDestination
                );
        DestinationSettings destinationSettings =
                settings.destination(destination);

        if (destinationSettings == null
                || !destinationSettings.enabled()) {
            error(
                    player,
                    message(destination, "disabled")
            );
            return false;
        }

        if (!locationService.destinationAvailable(destination)) {
            error(
                    player,
                    message(destination, "world-unavailable")
            );
            return false;
        }

        UUID playerId = player.getUniqueId();

        if (sessionsByPlayer.containsKey(playerId)
                || teleportService.isActive(player)) {
            error(
                    player,
                    "&cYou already have a teleport in progress"
            );
            return false;
        }

        long cooldown = cooldownRemainingSeconds(player);

        if (cooldown > 0L) {
            error(
                    player,
                    message(destination, "cooldown")
                            .replace(
                                    "%seconds%",
                                    SECONDARY + cooldown
                            )
            );
            return false;
        }

        if (queuedCount() >= settings.maxQueuedRequests()) {
            error(
                    player,
                    message(destination, "busy")
            );
            return false;
        }

        if (!teleportService.reserve(
                player,
                TeleportService.TeleportKind.RTP
        )) {
            error(
                    player,
                    "&cYou already have a teleport in progress"
            );
            return false;
        }

        boolean plus = isPlus(player);
        OriginRtpRequest request = new OriginRtpRequest(
                UUID.randomUUID(),
                playerId,
                plus,
                destination,
                System.currentTimeMillis()
        );
        Session session = new Session(
                request,
                player.getLocation().clone(),
                System.nanoTime()
        );

        sessionsByPlayer.put(playerId, session);
        enqueue(session, false);

        int position = Math.max(
                1,
                queuePosition(player)
        );

        sendActionBar(
                player,
                BODY
                        + "Finding a safe "
                        + SECONDARY
                        + destinationSettings.displayName()
                        + " "
                        + BODY
                        + "destination "
                        + ACCENT
                        + "#"
                        + position
        );
        SoundService.teleportStart(player, core);
        return true;
    }

    public void cancel(
            Player player,
            boolean sendMessage
    ) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        Session session = removeSession(playerId);

        if (session == null) {
            return;
        }

        teleportService.releaseReservation(
                playerId,
                TeleportService.TeleportKind.RTP
        );
        teleportService.cancel(playerId, false);

        if (sendMessage && player.isOnline()) {
            send(
                    player,
                    "&cTeleport cancelled — you moved"
            );
            SoundService.teleportCancelled(player, core);
        }
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }

        cancel(player, false);
        landingProtection.remove(player.getUniqueId());
    }

    public void handleDeath(Player player) {
        if (player == null) {
            return;
        }

        cancel(player, false);
        landingProtection.remove(player.getUniqueId());
    }

    public void handleRespawn(Player player) {
        if (player == null) {
            return;
        }

        cancel(player, false);
        landingProtection.remove(player.getUniqueId());
    }

    /**
     * RTP owns movement cancellation only while queued/searching. Once final
     * countdown begins, the common TeleportService is the sole owner.
     */
    public void handleMove(
            Player player,
            Location destination
    ) {
        if (player == null || destination == null) {
            return;
        }

        Session session = sessionsByPlayer.get(
                player.getUniqueId()
        );

        if (session == null
                || session.phase() == Phase.COUNTDOWN
                || !cancelOnMove(
                session.request().destination()
        )) {
            return;
        }

        if (TeleportMovement.movedTooFar(
                core,
                session.origin(),
                destination
        )) {
            cancel(player, true);
        }
    }

    public void handleTeleport(
            Player player,
            Location destination
    ) {
        handleMove(player, destination);
    }

    public boolean active(Player player) {
        return player != null
                && sessionsByPlayer.containsKey(
                player.getUniqueId()
        );
    }

    public boolean trackingMovement(Player player) {
        if (player == null) {
            return false;
        }

        Session session = sessionsByPlayer.get(
                player.getUniqueId()
        );

        return session != null
                && session.phase() != Phase.COUNTDOWN;
    }

    public int queuePosition(Player player) {
        if (player == null) {
            return 0;
        }

        UUID playerId = player.getUniqueId();
        Session session = sessionsByPlayer.get(playerId);

        if (session == null
                || session.phase() != Phase.QUEUED) {
            return 0;
        }

        /* Simulate the exact Plus burst scheduler used by pollNext(). */
        List<UUID> plus = List.copyOf(plusQueue);
        List<UUID> normal = List.copyOf(defaultQueue);
        int plusIndex = 0;
        int defaultIndex = 0;
        int simulatedBurst = consecutivePlus;
        int position = 1;

        while (plusIndex < plus.size()
                || defaultIndex < normal.size()) {
            boolean choosePlus =
                    plusIndex < plus.size()
                            && (defaultIndex >= normal.size()
                            || simulatedBurst
                            < settings.plusPriorityBurst());

            UUID next;

            if (choosePlus) {
                next = plus.get(plusIndex++);
                simulatedBurst++;
            } else {
                next = normal.get(defaultIndex++);
                simulatedBurst = 0;
            }

            if (next.equals(playerId)) {
                return position;
            }

            position++;
        }

        return 0;
    }

    public boolean hasLandingProtection(Player player) {
        if (player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        Long until = landingProtection.get(playerId);

        if (until == null) {
            return false;
        }

        if (until <= System.nanoTime()) {
            landingProtection.remove(playerId);
            return false;
        }

        return true;
    }

    private void process() {
        if (!running) {
            return;
        }

        timeoutSearches();
        startSearches();

        long now = System.nanoTime();

        if (now >= nextMaintenanceNanos) {
            performMaintenance(now);
            nextMaintenanceNanos =
                    now + settings.maintenanceIntervalNanos();
        }
    }

    private void startSearches() {
        while (running
                && searchingSessions.size()
                < settings.maxSearchesAtOnce()) {
            Session session = pollNext();

            if (session == null) {
                return;
            }

            Player player = Bukkit.getPlayer(
                    session.request().playerId()
            );

            if (player == null || !player.isOnline()) {
                UUID playerId = session.request().playerId();
                removeSession(playerId);
                teleportService.releaseReservation(
                        playerId,
                        TeleportService.TeleportKind.RTP
                );
                continue;
            }

            beginSearch(player, session);
        }
    }

    private Session pollNext() {
        while (!plusQueue.isEmpty()
                || !defaultQueue.isEmpty()) {
            boolean choosePlus =
                    !plusQueue.isEmpty()
                            && (defaultQueue.isEmpty()
                            || consecutivePlus
                            < settings.plusPriorityBurst());

            UUID playerId;

            if (choosePlus) {
                playerId = removeFirst(plusQueue);
                consecutivePlus++;
            } else {
                playerId = removeFirst(defaultQueue);
                consecutivePlus = 0;
            }

            if (playerId == null) {
                continue;
            }

            Session session = sessionsByPlayer.get(playerId);

            if (session != null
                    && session.phase() == Phase.QUEUED) {
                return session;
            }
        }

        return null;
    }

    private UUID removeFirst(LinkedHashSet<UUID> queue) {
        if (queue.isEmpty()) {
            return null;
        }

        var iterator = queue.iterator();
        UUID first = iterator.next();
        iterator.remove();
        return first;
    }

    private void beginSearch(
            Player player,
            Session session
    ) {
        session.phase(Phase.SEARCHING);
        session.beginSearchPass();
        session.searchDeadlineNanos(
                System.nanoTime()
                        + settings.searchTimeoutNanos()
        );

        searchingSessions.put(
                session.request().playerId(),
                session
        );

        CompletableFuture<Location> future =
                locationService.findSafeLocation(
                        session.request().destination()
                );
        session.searchFuture(future);

        UUID playerId = session.request().playerId();
        UUID sessionId = session.request().sessionId();

        future.whenComplete(
                (location, throwable) ->
                        runOnMain(
                                () -> completeSearch(
                                        playerId,
                                        sessionId,
                                        location,
                                        throwable
                                )
                        )
        );

        sendActionBar(
                player,
                BODY
                        + "Searching safe terrain in "
                        + SECONDARY
                        + displayName(
                        session.request().destination()
                )
        );
    }

    private void completeSearch(
            UUID playerId,
            UUID sessionId,
            Location location,
            Throwable throwable
    ) {
        Session session = sessionsByPlayer.get(playerId);

        if (mismatched(
                session,
                sessionId,
                Phase.SEARCHING
        )) {
            return;
        }

        releaseSearchSlot(session);
        session.searchFuture(null);

        Player player = Bukkit.getPlayer(playerId);

        if (player == null || !player.isOnline()) {
            removeSession(playerId);
            teleportService.releaseReservation(
                    playerId,
                    TeleportService.TeleportKind.RTP
            );
            return;
        }

        if (throwable != null) {
            logSearchFailure(
                    session.request().destination(),
                    throwable
            );
            retryOrFailSearch(player, session);
            return;
        }

        if (location == null) {
            retryOrFailSearch(player, session);
            return;
        }

        OriginRtpLocationService.ChunkReservation chunkReservation =
                locationService.retainReservation(
                        location,
                        session.request().destination()
                );

        if (chunkReservation == null) {
            retryOrFailSearch(player, session);
            return;
        }

        session.chunkReservation(chunkReservation);

        Location confirmed =
                locationService.revalidateReservedLocation(
                        location,
                        session.request().destination(),
                        chunkReservation
                );

        if (confirmed == null) {
            session.releaseChunkReservation(locationService);
            retryOrFailSearch(player, session);
            return;
        }

        session.reservedLocation(confirmed.clone());
        session.phase(Phase.COUNTDOWN);

        int delay = session.request().plus()
                ? PLUS_COUNTDOWN_SECONDS
                : DEFAULT_COUNTDOWN_SECONDS;

        boolean started =
                teleportService.beginReservedLocation(
                        player,
                        displayName(
                                session.request().destination()
                        ),
                        () -> locationService
                                .revalidateReservedLocation(
                                        session.reservedLocation(),
                                        session.request().destination(),
                                        session.chunkReservation()
                                ),
                        TeleportService.TeleportKind.RTP,
                        delay,
                        cancelOnMove(
                                session.request().destination()
                        ),
                        () -> completeTeleport(
                                playerId,
                                sessionId
                        ),
                        reason -> teleportFailed(
                                playerId,
                                sessionId,
                                reason
                        )
                );

        if (!started) {
            removeSession(playerId);
            teleportService.releaseReservation(
                    playerId,
                    TeleportService.TeleportKind.RTP
            );

            if (player.isOnline()) {
                error(
                        player,
                        "&cCould not start RTP teleport"
                );
            }
        }
    }

    private void timeoutSearches() {
        if (searchingSessions.isEmpty()) {
            return;
        }

        long now = System.nanoTime();

        for (Session session :
                List.copyOf(searchingSessions.values())) {
            if (session.searchDeadlineNanos() <= 0L
                    || now < session.searchDeadlineNanos()) {
                continue;
            }

            UUID playerId = session.request().playerId();
            UUID sessionId = session.request().sessionId();

            if (mismatched(
                    sessionsByPlayer.get(playerId),
                    sessionId,
                    Phase.SEARCHING
            )) {
                releaseSearchSlot(session);
                continue;
            }

            releaseSearchSlot(session);
            session.cancelSearchFuture();

            Player player = Bukkit.getPlayer(playerId);

            if (player == null || !player.isOnline()) {
                removeSession(playerId);
                teleportService.releaseReservation(
                        playerId,
                        TeleportService.TeleportKind.RTP
                );
                continue;
            }

            retryOrFailSearch(player, session);
        }
    }

    private void completeTeleport(
            UUID playerId,
            UUID sessionId
    ) {
        Session session = sessionsByPlayer.get(playerId);

        if (mismatched(
                session,
                sessionId,
                Phase.COUNTDOWN
        )) {
            return;
        }

        boolean plus = session.request().plus();
        Player player = Bukkit.getPlayer(playerId);

        removeSession(playerId);

        if (player == null || !player.isOnline()) {
            return;
        }

        applyCooldown(player, plus);
        applyLandingProtection(player);
    }

    private void teleportFailed(
            UUID playerId,
            UUID sessionId,
            TeleportService.FailureReason reason
    ) {
        Session session = sessionsByPlayer.get(playerId);

        if (mismatched(
                session,
                sessionId,
                Phase.COUNTDOWN
        )) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);

        session.releaseChunkReservation(locationService);
        session.reservedLocation(null);

        if (reason
                == TeleportService.FailureReason.DESTINATION_UNAVAILABLE
                && player != null
                && player.isOnline()
                && session.searchPasses()
                < settings.maxSearchPasses()
                && teleportService.reserve(
                player,
                TeleportService.TeleportKind.RTP
        )) {
            enqueue(session, true);
            sendActionBar(
                    player,
                    BODY
                            + "Destination changed "
                            + BODY
                            + "finding another safe location"
            );
            return;
        }

        removeSession(playerId);

        if (player != null
                && player.isOnline()
                && reason
                != TeleportService.FailureReason.CANCELLED_MOVE
                && reason
                != TeleportService.FailureReason.CANCELLED) {
            error(
                    player,
                    message(
                            session.request().destination(),
                            "failed"
                    )
            );
        }
    }

    private void retryOrFailSearch(
            Player player,
            Session session
    ) {
        session.cancelSearchFuture();
        session.releaseChunkReservation(locationService);
        session.reservedLocation(null);

        if (session.searchPasses()
                < settings.maxSearchPasses()) {
            enqueue(session, true);
            sendActionBar(
                    player,
                    BODY
                            + "Still searching for safe "
                            + SECONDARY
                            + displayName(
                            session.request().destination()
                    )
                            + BODY
                            + " terrain"
            );
            return;
        }

        failSearch(player, session);
    }

    private void failSearch(
            Player player,
            Session session
    ) {
        UUID playerId = session.request().playerId();

        removeSession(playerId);
        teleportService.releaseReservation(
                playerId,
                TeleportService.TeleportKind.RTP
        );

        if (player.isOnline()) {
            error(
                    player,
                    message(
                            session.request().destination(),
                            "failed"
                    )
            );
        }
    }

    private void enqueue(
            Session session,
            boolean first
    ) {
        releaseSearchSlot(session);
        session.phase(Phase.QUEUED);

        UUID playerId = session.request().playerId();

        plusQueue.remove(playerId);
        defaultQueue.remove(playerId);

        LinkedHashSet<UUID> queue =
                session.request().plus()
                        && settings.plusPriority()
                        ? plusQueue
                        : defaultQueue;

        if (first) {
            queue.addFirst(playerId);
        } else {
            queue.addLast(playerId);
        }
    }

    private void releaseSearchSlot(Session session) {
        if (session == null) {
            return;
        }

        searchingSessions.remove(
                session.request().playerId(),
                session
        );
        session.searchDeadlineNanos(0L);
    }

    private Session removeSession(UUID playerId) {
        Session session = sessionsByPlayer.remove(playerId);

        if (session == null) {
            return null;
        }

        plusQueue.remove(playerId);
        defaultQueue.remove(playerId);
        releaseSearchSlot(session);
        session.cancelSearchFuture();
        session.releaseChunkReservation(locationService);
        return session;
    }

    private void performMaintenance(long now) {
        List<UUID> remove = new ArrayList<>();

        for (Session session : sessionsByPlayer.values()) {
            Player player = Bukkit.getPlayer(
                    session.request().playerId()
            );

            if (player == null || !player.isOnline()) {
                remove.add(session.request().playerId());
                continue;
            }

            if (session.phase() == Phase.QUEUED
                    && now - session.createdAtNanos()
                    >= settings.maxQueueWaitNanos()) {
                remove.add(session.request().playerId());
            }
        }

        for (UUID playerId : remove) {
            Session session = sessionsByPlayer.get(playerId);

            if (session == null) {
                continue;
            }

            boolean timedOut =
                    session.phase() == Phase.QUEUED
                            && now - session.createdAtNanos()
                            >= settings.maxQueueWaitNanos();
            Player player = Bukkit.getPlayer(playerId);

            removeSession(playerId);
            teleportService.releaseReservation(
                    playerId,
                    TeleportService.TeleportKind.RTP
            );
            teleportService.cancel(playerId, false);

            if (timedOut
                    && player != null
                    && player.isOnline()) {
                error(
                        player,
                        message(
                                session.request().destination(),
                                "queue-timeout"
                        )
                );
            }
        }

        cooldowns.entrySet().removeIf(
                entry -> entry.getValue() <= now
        );
        landingProtection.entrySet().removeIf(
                entry -> entry.getValue() <= now
        );
    }

    private void rebuildQueuedSessions() {
        if (sessionsByPlayer.isEmpty()) {
            plusQueue.clear();
            defaultQueue.clear();
            consecutivePlus = 0;
            return;
        }

        List<Session> queued = sessionsByPlayer.values()
                .stream()
                .filter(
                        session ->
                                session.phase() == Phase.QUEUED
                )
                .sorted(
                        Comparator.comparingLong(
                                Session::createdAtNanos
                        )
                )
                .toList();

        plusQueue.clear();
        defaultQueue.clear();
        consecutivePlus = 0;

        for (Session session : queued) {
            enqueue(session, false);
        }
    }

    private boolean mismatched(
            Session session,
            UUID sessionId,
            Phase phase
    ) {
        return session == null
                || !session.request()
                .sessionId()
                .equals(sessionId)
                || session.phase() != phase;
    }

    private int queuedCount() {
        return plusQueue.size() + defaultQueue.size();
    }

    private boolean cancelOnMove(String destination) {
        DestinationSettings destinationSettings =
                settings.destination(destination);

        return destinationSettings == null
                || destinationSettings.cancelOnMove();
    }

    private boolean isPlus(Player player) {
        String permission = settings.plusPermission();

        return !permission.isBlank()
                && player.hasPermission(permission);
    }

    private long cooldownRemainingSeconds(Player player) {
        UUID playerId = player.getUniqueId();
        Long until = cooldowns.get(playerId);

        if (until == null) {
            return 0L;
        }

        long remaining = until - System.nanoTime();

        if (remaining <= 0L) {
            cooldowns.remove(playerId);
            return 0L;
        }

        return Math.max(
                1L,
                (remaining + NANOS_PER_SECOND - 1L)
                        / NANOS_PER_SECOND
        );
    }

    private void applyCooldown(
            Player player,
            boolean plus
    ) {
        int seconds = plus
                ? settings.plusCooldownSeconds()
                : settings.defaultCooldownSeconds();

        UUID playerId = player.getUniqueId();

        if (seconds <= 0) {
            cooldowns.remove(playerId);
            return;
        }

        cooldowns.put(
                playerId,
                System.nanoTime()
                        + seconds * NANOS_PER_SECOND
        );
    }

    private void applyLandingProtection(Player player) {
        int seconds = settings.landingProtectionSeconds();
        UUID playerId = player.getUniqueId();

        if (seconds <= 0) {
            landingProtection.remove(playerId);
            return;
        }

        landingProtection.put(
                playerId,
                System.nanoTime()
                        + seconds * NANOS_PER_SECOND
        );
    }

    private void logSearchFailure(
            String destination,
            Throwable throwable
    ) {
        long now = System.nanoTime();

        if (now - lastFailureLogNanos
                < FAILURE_LOG_INTERVAL_NANOS) {
            return;
        }

        lastFailureLogNanos = now;
        Throwable cause = throwable;

        while (cause.getCause() != null
                && cause.getCause() != cause) {
            cause = cause.getCause();
        }

        String detail = cause.getMessage();
        core.getLogger().warning(
                "RTP search error for "
                        + destination
                        + ": "
                        + cause.getClass().getSimpleName()
                        + (detail == null || detail.isBlank()
                        ? ""
                        : " — " + detail)
        );
    }

    private String message(
            String destination,
            String key
    ) {
        String destinationPath =
                "origin-rtp.destinations."
                        + destination
                        + ".messages."
                        + key;
        String raw = core.getConfig().getString(
                destinationPath
        );

        if (raw == null) {
            raw = core.getConfig().getString(
                    "origin-rtp.messages." + key,
                    switch (key) {
                        case "busy" ->
                                "&cRandom teleport is busy — try again in a moment";
                        case "queue-timeout" ->
                                "&cRandom teleport queue timed out — try again";
                        case "world-unavailable" ->
                                "&c%world% is unavailable right now";
                        default ->
                                "&cMissing RTP message: " + key;
                    }
            );
        }

        String normalized = normalizePalette(raw)
                .replace(
                        "%world%",
                        PRIMARY + displayName(destination)
                );

        return TextColor.color(normalized);
    }

    private String normalizePalette(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }

        return value
                .replace("&#ff55ff", PRIMARY)
                .replace("&#FF55FF", PRIMARY)
                .replace("&#ff88ff", SECONDARY)
                .replace("&#FF88FF", SECONDARY)
                .replace("&d", PRIMARY)
                .replace("&#cccccc", BODY)
                .replace("&#CCCCCC", BODY);
    }

    private String displayName(String destination) {
        DestinationSettings destinationSettings =
                settings.destination(destination);

        if (destinationSettings != null) {
            return destinationSettings.displayName();
        }

        return switch (destination) {
            case "nether" -> "Nether";
            case "end" -> "The End";
            default -> "Overworld";
        };
    }

    private void error(
            Player player,
            String message
    ) {
        send(player, message);
        SoundService.guiError(player, core);
    }

    private void send(
            Player player,
            String message
    ) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(component(colored));
    }

    private void sendActionBar(
            Player player,
            String message
    ) {
        player.sendActionBar(component(message));
    }

    private Component component(String message) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }

    private void scheduleProcessor() {
        stopProcessorOnly();

        if (!running || !core.isEnabled()) {
            return;
        }

        long interval = settings.processEveryTicks();

        processorTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        this::process,
                        interval,
                        interval
                );
    }

    private void stopProcessorOnly() {
        if (processorTask != null) {
            processorTask.cancel();
            processorTask = null;
        }
    }

    private void runOnMain(Runnable action) {
        if (!running || !core.isEnabled()) {
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            action.run();
            return;
        }

        try {
            Bukkit.getScheduler().runTask(
                    core,
                    () -> {
                        if (running && core.isEnabled()) {
                            action.run();
                        }
                    }
            );
        } catch (IllegalPluginAccessException ignored) {
            // Plugin is shutting down; stop() owns final cleanup.
        }
    }

    private static String configuredString(
            Core core,
            String path,
            String fallback
    ) {
        String value = core.getConfig().getString(path);

        return value == null || value.isBlank()
                ? fallback
                : value;
    }

    private static int clampedInt(
            Core core,
            String path,
            int fallback,
            int minimum,
            int maximum
    ) {
        return Math.clamp(
                core.getConfig().getInt(path, fallback),
                minimum,
                maximum
        );
    }

    private record DestinationSettings(
            boolean enabled,
            boolean cancelOnMove,
            String displayName
    ) {
    }

    private record QueueSettings(
            String plusPermission,
            boolean plusPriority,
            int defaultCooldownSeconds,
            int plusCooldownSeconds,
            int processEveryTicks,
            int maxSearchesAtOnce,
            int maxQueuedRequests,
            int plusPriorityBurst,
            int maxSearchPasses,
            long searchTimeoutNanos,
            long maxQueueWaitNanos,
            long maintenanceIntervalNanos,
            int landingProtectionSeconds,
            Map<String, DestinationSettings> destinations
    ) {

        private static QueueSettings fromConfig(Core core) {
            String fallbackPlusPermission =
                    configuredString(
                            core,
                            "teleport-perks.plus-permission",
                            "mineacle.plus"
                    );
            String plusPermission =
                    configuredString(
                            core,
                            "origin-rtp.plus.permission",
                            fallbackPlusPermission
                    );

            boolean globalEnabled = core.getConfig()
                    .getBoolean(
                            "origin-rtp.enabled",
                            true
                    );
            boolean globalCancel = core.getConfig()
                    .getBoolean(
                            "origin-rtp.teleport.cancel-on-move",
                            true
                    );

            Map<String, DestinationSettings> destinations =
                    new HashMap<>();

            for (String destination :
                    List.of("overworld", "nether", "end")) {
                String base =
                        "origin-rtp.destinations." + destination;

                destinations.put(
                        destination,
                        new DestinationSettings(
                                globalEnabled
                                        && core.getConfig().getBoolean(
                                        base + ".enabled",
                                        true
                                ),
                                core.getConfig().getBoolean(
                                        base
                                                + ".teleport.cancel-on-move",
                                        globalCancel
                                ),
                                configuredString(
                                        core,
                                        base + ".display-name",
                                        switch (destination) {
                                            case "nether" -> "Nether";
                                            case "end" -> "The End";
                                            default -> "Overworld";
                                        }
                                )
                        )
                );
            }

            int timeoutSeconds = clampedInt(
                    core,
                    "origin-rtp.search.timeout-seconds",
                    60,
                    15,
                    180
            );
            int queueWaitSeconds = clampedInt(
                    core,
                    "origin-rtp.queue.max-wait-seconds",
                    120,
                    15,
                    900
            );
            int maintenanceSeconds = clampedInt(
                    core,
                    "origin-rtp.queue.maintenance-every-seconds",
                    10,
                    1,
                    60
            );

            return new QueueSettings(
                    plusPermission,
                    core.getConfig().getBoolean(
                            "origin-rtp.plus.priority",
                            true
                    ),
                    clampedInt(
                            core,
                            "origin-rtp.cooldown.default-seconds",
                            0,
                            0,
                            86_400
                    ),
                    clampedInt(
                            core,
                            "origin-rtp.cooldown.plus-seconds",
                            0,
                            0,
                            86_400
                    ),
                    clampedInt(
                            core,
                            "origin-rtp.queue.process-every-ticks",
                            10,
                            1,
                            20
                    ),
                    clampedInt(
                            core,
                            "origin-rtp.queue.max-searches-at-once",
                            2,
                            1,
                            HARD_MAX_CONCURRENT_SEARCHES
                    ),
                    clampedInt(
                            core,
                            "origin-rtp.queue.max-queued-requests",
                            256,
                            16,
                            HARD_MAX_QUEUED_REQUESTS
                    ),
                    clampedInt(
                            core,
                            "origin-rtp.queue.plus-priority-burst",
                            3,
                            1,
                            20
                    ),
                    clampedInt(
                            core,
                            "origin-rtp.search.max-passes",
                            2,
                            1,
                            HARD_MAX_SEARCH_PASSES
                    ),
                    timeoutSeconds * NANOS_PER_SECOND,
                    queueWaitSeconds * NANOS_PER_SECOND,
                    maintenanceSeconds * NANOS_PER_SECOND,
                    clampedInt(
                            core,
                            "origin-rtp.teleport."
                                    + "post-teleport-protection-seconds",
                            5,
                            0,
                            30
                    ),
                    Map.copyOf(destinations)
            );
        }

        private DestinationSettings destination(String key) {
            return destinations.get(
                    key == null
                            ? ""
                            : key.toLowerCase(Locale.ROOT)
            );
        }
    }

    private static final class Session {

        private final OriginRtpRequest request;
        private final Location origin;
        private final long createdAtNanos;

        private Phase phase = Phase.QUEUED;
        private Location reservedLocation;
        private OriginRtpLocationService.ChunkReservation
                chunkReservation;
        private CompletableFuture<Location> searchFuture;
        private long searchDeadlineNanos;
        private int searchPasses;

        private Session(
                OriginRtpRequest request,
                Location origin,
                long createdAtNanos
        ) {
            this.request = request;
            this.origin = origin;
            this.createdAtNanos = createdAtNanos;
        }

        private OriginRtpRequest request() {
            return request;
        }

        private Location origin() {
            return origin;
        }

        private long createdAtNanos() {
            return createdAtNanos;
        }

        private Phase phase() {
            return phase;
        }

        private void phase(Phase value) {
            phase = value;
        }

        private Location reservedLocation() {
            return reservedLocation;
        }

        private void reservedLocation(Location value) {
            reservedLocation = value;
        }

        private OriginRtpLocationService.ChunkReservation
        chunkReservation() {
            return chunkReservation;
        }

        private void chunkReservation(
                OriginRtpLocationService.ChunkReservation value
        ) {
            chunkReservation = value;
        }

        private void releaseChunkReservation(
                OriginRtpLocationService service
        ) {
            if (chunkReservation == null) {
                return;
            }

            service.releaseReservation(chunkReservation);
            chunkReservation = null;
        }

        private void searchFuture(
                CompletableFuture<Location> value
        ) {
            searchFuture = value;
        }

        private void cancelSearchFuture() {
            if (searchFuture != null
                    && !searchFuture.isDone()) {
                searchFuture.cancel(false);
            }

            searchFuture = null;
        }

        private void searchDeadlineNanos(long value) {
            searchDeadlineNanos = value;
        }

        private long searchDeadlineNanos() {
            return searchDeadlineNanos;
        }

        private void beginSearchPass() {
            searchPasses++;
        }

        private int searchPasses() {
            return searchPasses;
        }
    }
}
