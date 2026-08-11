package net.mineacle.core.rtp.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.common.teleport.TeleportMovement;
import net.mineacle.core.common.teleport.TeleportService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class OriginRtpQueueService {

    private enum Phase {
        QUEUED,
        SEARCHING,
        COUNTDOWN
    }

    private static final String PRIMARY =
            "&#8436FE";
    private static final String SECONDARY =
            "&#B078FF";
    private static final String ACCENT =
            "&#D0AFFF";
    private static final String BODY =
            "&#bbbbbb";

    private final Core core;
    private final OriginRtpLocationService locationService;
    private final TeleportService teleportService;

    private final Deque<UUID> plusQueue =
            new ArrayDeque<>();
    private final Deque<UUID> defaultQueue =
            new ArrayDeque<>();
    private final Map<UUID, Session> sessionsByPlayer =
            new HashMap<>();
    private final Map<UUID, Session> sessionsById =
            new HashMap<>();
    private final Map<UUID, Long> cooldowns =
            new HashMap<>();
    private final Map<UUID, Long> landingProtection =
            new HashMap<>();

    private BukkitTask processorTask;
    private int consecutivePlus;

    public OriginRtpQueueService(
            Core core,
            TeleportService teleportService
    ) {
        this.core = core;
        this.teleportService = teleportService;
        this.locationService =
                new OriginRtpLocationService(core);
    }

    public void start() {
        stopProcessorOnly();

        long interval = Math.max(
                1L,
                core.getConfig().getLong(
                        "origin-rtp.queue.process-every-ticks",
                        10L
                )
        );

        processorTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        this::process,
                        interval,
                        interval
                );
    }

    public void stop() {
        stopProcessorOnly();

        for (Session session :
                sessionsByPlayer.values()) {
            session.cancelSearch();
            teleportService.releaseReservation(
                    session.request().playerId(),
                    TeleportService
                            .TeleportKind
                            .RTP
            );
            teleportService.cancel(
                    session.request().playerId()
            );
        }

        plusQueue.clear();
        defaultQueue.clear();
        sessionsByPlayer.clear();
        sessionsById.clear();
        cooldowns.clear();
        landingProtection.clear();
        consecutivePlus = 0;
    }

    public void request(Player player) {
        request(
                player,
                "overworld"
        );
    }

    public void request(
            Player player,
            String rawDestination
    ) {
        if (player == null
                || !player.isOnline()) {
            return;
        }

        String destination =
                OriginRtpSearchSettings
                        .canonicalDestination(
                                rawDestination
                        );

        if (!knownDestination(destination)
                || !enabled(destination)) {
            error(
                    player,
                    message(
                            destination,
                            "disabled"
                    )
            );
            return;
        }

        UUID playerId = player.getUniqueId();

        if (sessionsByPlayer
                .containsKey(playerId)
                || teleportService
                .isActive(player)) {
            error(
                    player,
                    "&cYou already have a teleport in progress"
            );
            return;
        }

        long cooldown =
                cooldownRemainingSeconds(
                        player
                );

        if (cooldown > 0L) {
            error(
                    player,
                    message(
                            destination,
                            "cooldown"
                    ).replace(
                            "%seconds%",
                            SECONDARY
                                    + cooldown
                    )
            );
            return;
        }

        if (!teleportService.reserve(
                player,
                TeleportService
                        .TeleportKind
                        .RTP
        )) {
            error(
                    player,
                    "&cYou already have a teleport in progress"
            );
            return;
        }

        OriginRtpRequest request =
                new OriginRtpRequest(
                        UUID.randomUUID(),
                        playerId,
                        isPlus(player),
                        destination,
                        System.currentTimeMillis()
                );
        Session session =
                new Session(
                        request,
                        player.getLocation().clone()
                );

        sessionsByPlayer.put(
                playerId,
                session
        );
        sessionsById.put(
                request.sessionId(),
                session
        );
        enqueue(
                session,
                false
        );

        sendActionBar(
                player,
                BODY
                        + "Finding a safe "
                        + PRIMARY
                        + displayName(destination)
                        + " "
                        + BODY
                        + "destination "
                        + ACCENT
                        + "#"
                        + queuePosition(player)
        );
        SoundService.teleportStart(
                player,
                core
        );
    }

    public void cancel(
            Player player,
            boolean sendMessage
    ) {
        if (player == null) {
            return;
        }

        Session session =
                removeSession(
                        player.getUniqueId()
                );

        if (session == null) {
            return;
        }

        session.cancelSearch();
        removeFromQueues(
                session.request().sessionId()
        );
        teleportService.releaseReservation(
                player.getUniqueId(),
                TeleportService
                        .TeleportKind
                        .RTP
        );
        teleportService.cancel(
                player.getUniqueId(),
                false
        );

        if (sendMessage) {
            send(
                    player,
                    "&cTeleport cancelled — you moved"
            );
            SoundService.teleportCancelled(
                    player,
                    core
            );
        }
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }

        cancel(
                player,
                false
        );
        landingProtection.remove(
                player.getUniqueId()
        );
    }

    /**
     * RTP also protects the queue/search phase. Once COUNTDOWN begins, the
     * common TeleportService owns the same movement rule for final execution.
     */
    public void handleMove(Player player, Location destination) {
        if (player == null || destination == null) {
            return;
        }

        Session session = sessionsByPlayer.get(player.getUniqueId());

        if (session == null
                || session.phase() == Phase.COUNTDOWN
                || !cancelOnMove(session.request().destination())) {
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

    public int queuePosition(Player player) {
        if (player == null) {
            return 0;
        }

        Session session =
                sessionsByPlayer.get(
                        player.getUniqueId()
                );

        if (session == null
                || session.phase()
                != Phase.QUEUED) {
            return 0;
        }

        UUID sessionId =
                session.request().sessionId();
        int position = 1;

        for (UUID queued : plusQueue) {
            if (queued.equals(sessionId)) {
                return position;
            }
            position++;
        }

        for (UUID queued : defaultQueue) {
            if (queued.equals(sessionId)) {
                return position;
            }
            position++;
        }

        return 0;
    }

    public boolean hasLandingProtection(
            Player player
    ) {
        if (player == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();
        Long until =
                landingProtection.get(playerId);

        if (until == null) {
            return false;
        }

        if (until <= System.currentTimeMillis()) {
            landingProtection.remove(playerId);
            return false;
        }

        return true;
    }

    private void process() {
        removeOfflineSessions();
        startSearches();
        cleanupExpiringState();
    }

    private void startSearches() {
        int maximum = Math.max(
                1,
                core.getConfig().getInt(
                        "origin-rtp.queue.max-searches-at-once",
                        2
                )
        );

        while (searchingCount() < maximum) {
            Session session = pollNext();

            if (session == null) {
                return;
            }

            Player player = Bukkit.getPlayer(
                    session.request().playerId()
            );

            if (player == null
                    || !player.isOnline()) {
                removeSession(
                        session.request().playerId()
                );
                teleportService
                        .releaseReservation(
                                session.request()
                                        .playerId(),
                                TeleportService
                                        .TeleportKind
                                        .RTP
                        );
                continue;
            }

            beginSearch(
                    player,
                    session
            );
        }
    }

    private Session pollNext() {
        int burst = Math.max(
                1,
                core.getConfig().getInt(
                        "origin-rtp.queue.plus-priority-burst",
                        3
                )
        );

        while (!plusQueue.isEmpty()
                || !defaultQueue.isEmpty()) {
            boolean choosePlus =
                    !plusQueue.isEmpty()
                            && (
                            defaultQueue.isEmpty()
                                    || consecutivePlus
                                    < burst
                    );

            UUID sessionId =
                    choosePlus
                            ? plusQueue.pollFirst()
                            : defaultQueue.pollFirst();

            if (choosePlus) {
                consecutivePlus++;
            } else {
                consecutivePlus = 0;
            }

            Session session =
                    sessionsById.get(sessionId);

            if (session != null
                    && session.phase()
                    == Phase.QUEUED) {
                return session;
            }
        }

        return null;
    }

    private void beginSearch(
            Player player,
            Session session
    ) {
        session.phase(Phase.SEARCHING);

        CompletableFuture<Location> future =
                locationService.findSafeLocation(
                        session.request()
                                .destination()
                );
        session.searchFuture(future);

        UUID playerId =
                session.request().playerId();
        UUID sessionId =
                session.request().sessionId();
        int timeoutSeconds = Math.max(
                5,
                core.getConfig().getInt(
                        "origin-rtp.search.timeout-seconds",
                        30
                )
        );

        session.searchTimeoutTask(
                core.getServer()
                        .getScheduler()
                        .runTaskLater(
                                core,
                                () -> timeoutSearch(
                                        playerId,
                                        sessionId
                                ),
                                timeoutSeconds * 20L
                        )
        );

        future.whenComplete(
                (location, throwable) ->
                        Bukkit.getScheduler()
                                .runTask(
                                        core,
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
                        + "Searching "
                        + BODY
                        + "safe terrain "
                        + BODY
                        + "in "
                        + PRIMARY
                        + displayName(
                        session.request()
                                .destination()
                )
        );
    }

    private void completeSearch(
            UUID playerId,
            UUID sessionId,
            Location location,
            Throwable throwable
    ) {
        Session session =
                sessionsByPlayer.get(playerId);

        if (mismatched(
                session,
                sessionId,
                Phase.SEARCHING
        )) {
            return;
        }

        session.cancelSearchTimeout();
        session.searchFuture(null);

        Player player =
                Bukkit.getPlayer(playerId);

        if (player == null
                || !player.isOnline()) {
            removeSession(playerId);
            teleportService
                    .releaseReservation(
                            playerId,
                            TeleportService
                                    .TeleportKind
                                    .RTP
                    );
            return;
        }

        if (throwable != null
                || location == null) {
            failSearch(
                    player,
                    session
            );
            return;
        }

        session.reservedLocation(
                location.clone()
        );
        session.phase(Phase.COUNTDOWN);

        int delay =
                session.request().plus()
                        ? plusDelaySeconds()
                        : defaultDelaySeconds();

        boolean started =
                teleportService
                        .beginReservedLocation(
                                player,
                                displayName(
                                        session.request()
                                                .destination()
                                ),
                                () -> locationService
                                        .revalidateReservedLocation(
                                                session.reservedLocation(),
                                                session.request()
                                                        .destination()
                                        ),
                                TeleportService
                                        .TeleportKind
                                        .RTP,
                                delay,
                                cancelOnMove(
                                        session.request()
                                                .destination()
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
            teleportService
                    .releaseReservation(
                            playerId,
                            TeleportService
                                    .TeleportKind
                                    .RTP
                    );
            error(
                    player,
                    "&cCould not start RTP teleport"
            );
        }
    }

    private void timeoutSearch(
            UUID playerId,
            UUID sessionId
    ) {
        Session session =
                sessionsByPlayer.get(playerId);

        if (mismatched(
                session,
                sessionId,
                Phase.SEARCHING
        )) {
            return;
        }

        session.cancelSearch();
        removeSession(playerId);
        teleportService.releaseReservation(
                playerId,
                TeleportService
                        .TeleportKind
                        .RTP
        );

        Player player =
                Bukkit.getPlayer(playerId);

        if (player != null
                && player.isOnline()) {
            error(
                    player,
                    message(
                            session.request()
                                    .destination(),
                            "failed"
                    )
            );
        }
    }

    private void completeTeleport(
            UUID playerId,
            UUID sessionId
    ) {
        Session session =
                sessionsByPlayer.get(playerId);

        if (mismatched(
                session,
                sessionId,
                Phase.COUNTDOWN
        )) {
            return;
        }

        Player player =
                Bukkit.getPlayer(playerId);

        removeSession(playerId);

        if (player == null
                || !player.isOnline()) {
            return;
        }

        applyCooldown(player);
        applyLandingProtection(player);
    }

    private void teleportFailed(
            UUID playerId,
            UUID sessionId,
            TeleportService.FailureReason reason
    ) {
        Session session =
                sessionsByPlayer.get(playerId);

        if (mismatched(
                session,
                sessionId,
                Phase.COUNTDOWN
        )) {
            return;
        }

        Player player =
                Bukkit.getPlayer(playerId);

        if (reason
                == TeleportService
                .FailureReason
                .DESTINATION_UNAVAILABLE
                && player != null
                && player.isOnline()
                && teleportService.reserve(
                player,
                TeleportService
                        .TeleportKind
                        .RTP
        )) {
            session.reservedLocation(null);
            session.phase(Phase.QUEUED);
            enqueue(
                    session,
                    true
            );
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
                != TeleportService
                .FailureReason
                .CANCELLED_MOVE
                && reason
                != TeleportService
                .FailureReason
                .CANCELLED) {
            error(
                    player,
                    message(
                            session.request()
                                    .destination(),
                            "failed"
                    )
            );
        }
    }

    private void failSearch(
            Player player,
            Session session
    ) {
        UUID playerId =
                session.request().playerId();

        session.cancelSearch();
        removeSession(playerId);
        teleportService.releaseReservation(
                playerId,
                TeleportService
                        .TeleportKind
                        .RTP
        );
        error(
                player,
                message(
                        session.request()
                                .destination(),
                        "failed"
                )
        );
    }

    private void enqueue(
            Session session,
            boolean first
    ) {
        session.phase(Phase.QUEUED);

        Deque<UUID> queue =
                session.request().plus()
                        && plusPriority()
                        ? plusQueue
                        : defaultQueue;
        UUID sessionId =
                session.request().sessionId();

        if (first) {
            queue.addFirst(sessionId);
        } else {
            queue.addLast(sessionId);
        }
    }

    private void removeOfflineSessions() {
        Iterator<Map.Entry<UUID, Session>>
                iterator =
                sessionsByPlayer
                        .entrySet()
                        .iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry =
                    iterator.next();
            Player player =
                    Bukkit.getPlayer(
                            entry.getKey()
                    );

            if (player != null
                    && player.isOnline()) {
                continue;
            }

            Session session =
                    entry.getValue();
            session.cancelSearch();
            removeFromQueues(
                    session.request()
                            .sessionId()
            );
            sessionsById.remove(
                    session.request()
                            .sessionId()
            );
            teleportService
                    .releaseReservation(
                            entry.getKey(),
                            TeleportService
                                    .TeleportKind
                                    .RTP
                    );
            teleportService.cancel(
                    entry.getKey()
            );
            iterator.remove();
        }
    }

    private Session removeSession(
            UUID playerId
    ) {
        Session session =
                sessionsByPlayer.remove(
                        playerId
                );

        if (session == null) {
            return null;
        }

        sessionsById.remove(
                session.request().sessionId()
        );
        removeFromQueues(
                session.request().sessionId()
        );
        session.cancelSearch();
        return session;
    }

    private void removeFromQueues(
            UUID sessionId
    ) {
        plusQueue.remove(sessionId);
        defaultQueue.remove(sessionId);
    }

    private int searchingCount() {
        int count = 0;

        for (Session session :
                sessionsByPlayer.values()) {
            if (session.phase()
                    == Phase.SEARCHING) {
                count++;
            }
        }

        return count;
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

    private boolean enabled(
            String destination
    ) {
        return core.getConfig().getBoolean(
                "origin-rtp.destinations."
                        + destination
                        + ".enabled",
                core.getConfig().getBoolean(
                        "origin-rtp.enabled",
                        true
                )
        );
    }

    private boolean knownDestination(
            String destination
    ) {
        return destination.equals("overworld")
                || destination.equals("nether")
                || destination.equals("end");
    }

    private boolean plusPriority() {
        return core.getConfig().getBoolean(
                "origin-rtp.plus.priority",
                true
        );
    }

    private boolean cancelOnMove(
            String destination
    ) {
        return core.getConfig().getBoolean(
                "origin-rtp.destinations."
                        + destination
                        + ".teleport.cancel-on-move",
                core.getConfig().getBoolean(
                        "origin-rtp.teleport.cancel-on-move",
                        true
                )
        );
    }

    private int defaultDelaySeconds() {
        return Math.max(
                0,
                core.getConfig().getInt(
                        "origin-rtp.default.delay-seconds",
                        5
                )
        );
    }

    private int plusDelaySeconds() {
        return Math.max(
                0,
                core.getConfig().getInt(
                        "origin-rtp.plus.delay-seconds",
                        core.getConfig().getInt(
                                "teleport-perks.plus-delay-seconds",
                                3
                        )
                )
        );
    }

    private boolean isPlus(Player player) {
        String permission =
                core.getConfig().getString(
                        "origin-rtp.plus.permission",
                        core.getConfig().getString(
                                "teleport-perks.plus-permission",
                                "mineacle.plus"
                        )
                );

        return !permission.isBlank()
                && player.hasPermission(
                permission
        );
    }

    private long cooldownRemainingSeconds(
            Player player
    ) {
        UUID playerId = player.getUniqueId();
        Long until =
                cooldowns.get(playerId);

        if (until == null) {
            return 0L;
        }

        long remaining =
                until - System.currentTimeMillis();

        if (remaining <= 0L) {
            cooldowns.remove(playerId);
            return 0L;
        }

        return Math.max(
                1L,
                (remaining + 999L) / 1000L
        );
    }

    private void applyCooldown(Player player) {
        String permission =
                core.getConfig().getString(
                        "origin-rtp.plus.permission",
                        "mineacle.plus"
                );
        int seconds = Math.max(
                0,
                core.getConfig().getInt(
                        !permission.isBlank()
                                && player.hasPermission(
                                permission
                        )
                                ? "origin-rtp.cooldown.plus-seconds"
                                : "origin-rtp.cooldown.default-seconds",
                        0
                )
        );

        if (seconds <= 0) {
            cooldowns.remove(
                    player.getUniqueId()
            );
            return;
        }

        cooldowns.put(
                player.getUniqueId(),
                System.currentTimeMillis()
                        + seconds * 1000L
        );
    }

    private void applyLandingProtection(
            Player player
    ) {
        int seconds = Math.max(
                0,
                core.getConfig().getInt(
                        "origin-rtp.teleport."
                                + "post-teleport-protection-seconds",
                        5
                )
        );

        if (seconds <= 0) {
            landingProtection.remove(
                    player.getUniqueId()
            );
            return;
        }

        landingProtection.put(
                player.getUniqueId(),
                System.currentTimeMillis()
                        + seconds * 1000L
        );
    }

    private void cleanupExpiringState() {
        long now = System.currentTimeMillis();

        cooldowns.entrySet().removeIf(
                entry -> entry.getValue() <= now
        );
        landingProtection.entrySet().removeIf(
                entry -> entry.getValue() <= now
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
        String raw =
                core.getConfig().getString(
                        destinationPath
                );

        if (raw == null) {
            raw = core.getConfig().getString(
                    "origin-rtp.messages." + key,
                    "&cMissing RTP message: " + key
            );
        }

        String normalized =
                normalizePalette(raw)
                        .replace(
                                "%world%",
                                PRIMARY
                                        + displayName(
                                        destination
                                )
                        );

        return TextColor.color(normalized);
    }

    private String normalizePalette(
            String value
    ) {
        return value
                .replace(
                        "&#" + "ff55ff",
                        PRIMARY
                )
                .replace(
                        "&#" + "FF55FF",
                        PRIMARY
                )
                .replace(
                        "&#" + "ff88ff",
                        SECONDARY
                )
                .replace(
                        "&#" + "FF88FF",
                        SECONDARY
                )
                .replace(
                        "&" + "d",
                        PRIMARY
                )
                .replace(
                        "&#cccccc",
                        BODY
                )
                .replace(
                        "&#CCCCCC",
                        BODY
                );
    }

    private String displayName(
            String destination
    ) {
        return core.getConfig().getString(
                "origin-rtp.destinations."
                        + destination
                        + ".display-name",
                switch (destination) {
                    case "nether" -> "Nether";
                    case "end" -> "The End";
                    default -> "Overworld";
                }
        );
    }

    private void error(
            Player player,
            String message
    ) {
        send(player, message);
        SoundService.guiError(
                player,
                core
        );
    }

    private void send(
            Player player,
            String message
    ) {
        String colored =
                TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(
                component(colored)
        );
    }

    private void sendActionBar(
            Player player,
            String message
    ) {
        player.sendActionBar(
                component(message)
        );
    }

    private Component component(
            String message
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }

    private void stopProcessorOnly() {
        if (processorTask != null) {
            processorTask.cancel();
            processorTask = null;
        }
    }

    private static final class Session {

        private final OriginRtpRequest request;
        private final Location origin;
        private Phase phase = Phase.QUEUED;
        private Location reservedLocation;
        private CompletableFuture<Location> searchFuture;
        private BukkitTask searchTimeoutTask;

        private Session(
                OriginRtpRequest request,
                Location origin
        ) {
            this.request = request;
            this.origin = origin;
        }

        private OriginRtpRequest request() {
            return request;
        }

        private Location origin() {
            return origin;
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

        private void reservedLocation(
                Location value
        ) {
            reservedLocation = value;
        }

        private void searchFuture(
                CompletableFuture<Location> value
        ) {
            searchFuture = value;
        }

        private void searchTimeoutTask(
                BukkitTask value
        ) {
            searchTimeoutTask = value;
        }

        private void cancelSearchTimeout() {
            if (searchTimeoutTask != null) {
                searchTimeoutTask.cancel();
                searchTimeoutTask = null;
            }
        }

        private void cancelSearch() {
            cancelSearchTimeout();

            if (searchFuture != null
                    && !searchFuture.isDone()) {
                searchFuture.cancel(false);
            }

            searchFuture = null;
        }
    }
}
