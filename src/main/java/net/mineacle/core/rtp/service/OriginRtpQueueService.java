package net.mineacle.core.rtp.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportMovement;
import net.mineacle.core.common.text.TextColor;
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

    private final Core core;
    private final OriginRtpLocationService locationService;

    private final Deque<UUID> plusQueue =
            new ArrayDeque<>();
    private final Deque<UUID> defaultQueue =
            new ArrayDeque<>();
    private final Deque<UUID> readyQueue =
            new ArrayDeque<>();
    private final Map<UUID, Session> sessions =
            new HashMap<>();
    private final Map<UUID, Long> cooldowns =
            new HashMap<>();
    private final Map<UUID, Long> landingProtection =
            new HashMap<>();

    private BukkitTask processorTask;
    private int consecutivePlus;

    public OriginRtpQueueService(Core core) {
        this.core = core;
        this.locationService =
                new OriginRtpLocationService(core);
    }

    public void start() {
        stop();

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
        if (processorTask != null) {
            processorTask.cancel();
            processorTask = null;
        }

        for (Session session : sessions.values()) {
            session.cancelTasks();
        }

        plusQueue.clear();
        defaultQueue.clear();
        readyQueue.clear();
        sessions.clear();
        cooldowns.clear();
        landingProtection.clear();
        consecutivePlus = 0;
    }

    public void request(Player player) {
        request(player, "overworld");
    }

    public void request(
            Player player,
            String rawDestination
    ) {
        if (player == null || !player.isOnline()) {
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
                    message(destination, "disabled")
            );
            return;
        }

        UUID playerId = player.getUniqueId();

        if (sessions.containsKey(playerId)) {
            error(
                    player,
                    message(
                            destination,
                            "already-active"
                    )
            );
            return;
        }

        long cooldownRemaining =
                cooldownRemainingSeconds(player);

        if (cooldownRemaining > 0L) {
            error(
                    player,
                    message(destination, "cooldown")
                            .replace(
                                    "%seconds%",
                                    String.valueOf(
                                            cooldownRemaining
                                    )
                            )
            );
            return;
        }

        boolean plus = isPlus(player);
        OriginRtpRequest request =
                new OriginRtpRequest(
                        UUID.randomUUID(),
                        playerId,
                        plus,
                        destination,
                        System.currentTimeMillis()
                );
        Session session = new Session(request);
        sessions.put(playerId, session);

        enqueueSearch(session, false);

        sendActionBar(
                player,
                message(destination, "queued")
        );
        SoundService.teleportStart(player, core);
    }

    public void cancel(
            Player player,
            boolean sendMessage
    ) {
        if (player == null) {
            return;
        }

        Session session = sessions.remove(
                player.getUniqueId()
        );

        if (session == null) {
            return;
        }

        removeFromQueues(
                session.request.sessionId()
        );
        session.cancelTasks();

        if (sendMessage) {
            send(
                    player,
                    message(
                            session.request.destination(),
                            "cancelled-move"
                    )
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

        cancel(player, false);
        landingProtection.remove(
                player.getUniqueId()
        );
    }

    /**
     * Movement is intentionally ignored while the destination is being found.
     * The player's current position is captured only when the final countdown
     * begins, matching the prepare-first flow used by fixed-destination systems.
     */
    public void handleMove(Player player) {
        if (player == null) {
            return;
        }

        Session session = sessions.get(
                player.getUniqueId()
        );

        if (session == null
                || session.phase != Phase.COUNTDOWN
                || !cancelOnMove(
                session.request.destination()
        )) {
            return;
        }

        if (movedTooFar(
                session.startLocation,
                player.getLocation()
        )) {
            cancel(player, true);
        }
    }

    public void handleTeleport(
            Player player,
            Location destination
    ) {
        if (player == null || destination == null) {
            return;
        }

        Session session = sessions.get(
                player.getUniqueId()
        );

        if (session == null
                || session.phase != Phase.COUNTDOWN
                || !cancelOnMove(
                session.request.destination()
        )) {
            return;
        }

        if (movedTooFar(
                session.startLocation,
                destination
        )) {
            cancel(player, true);
        }
    }

    public boolean active(Player player) {
        return player != null
                && sessions.containsKey(
                player.getUniqueId()
        );
    }

    public int queuePosition(Player player) {
        if (player == null) {
            return 0;
        }

        Session session = sessions.get(
                player.getUniqueId()
        );

        if (session == null
                || session.phase != Phase.QUEUED) {
            return 0;
        }

        return queuePosition(
                session.request.sessionId()
        );
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

        if (until <= System.currentTimeMillis()) {
            landingProtection.remove(playerId);
            return false;
        }

        return true;
    }

    private void process() {
        removeOfflineSessions();
        startCountdowns();
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

        while (countPhase(Phase.SEARCHING) < maximum) {
            Session session = pollNextQueued();

            if (session == null) {
                return;
            }

            Player player = Bukkit.getPlayer(
                    session.request.playerId()
            );

            if (player == null || !player.isOnline()) {
                sessions.remove(
                        session.request.playerId()
                );
                session.cancelTasks();
                continue;
            }

            beginSearch(player, session);
        }
    }

    private void startCountdowns() {
        int maximum = Math.max(
                1,
                core.getConfig().getInt(
                        "origin-rtp.queue.max-countdowns-at-once",
                        3
                )
        );

        while (countPhase(Phase.COUNTDOWN) < maximum) {
            UUID sessionId = readyQueue.pollFirst();

            if (sessionId == null) {
                return;
            }

            Session session = sessionById(sessionId);

            if (session == null
                    || session.phase != Phase.READY
                    || session.reservedLocation == null) {
                continue;
            }

            Player player = Bukkit.getPlayer(
                    session.request.playerId()
            );

            if (player == null || !player.isOnline()) {
                sessions.remove(
                        session.request.playerId()
                );
                session.cancelTasks();
                continue;
            }

            beginCountdown(player, session);
        }
    }

    private Session pollNextQueued() {
        int burst = Math.max(
                1,
                core.getConfig().getInt(
                        "origin-rtp.queue.plus-priority-burst",
                        3
                )
        );
        boolean choosePlus = !plusQueue.isEmpty()
                && (defaultQueue.isEmpty()
                || consecutivePlus < burst);
        UUID sessionId;

        if (choosePlus) {
            sessionId = plusQueue.pollFirst();
            consecutivePlus++;
        } else {
            sessionId = defaultQueue.pollFirst();
            consecutivePlus = 0;
        }

        if (sessionId == null) {
            return null;
        }

        Session session = sessionById(sessionId);

        if (session == null
                || session.phase != Phase.QUEUED) {
            return pollNextQueued();
        }

        return session;
    }

    private void beginSearch(
            Player player,
            Session session
    ) {
        session.phase = Phase.SEARCHING;

        CompletableFuture<Location> future =
                locationService.findSafeLocation(
                        session.request.destination()
                );
        session.searchFuture = future;

        UUID playerId = player.getUniqueId();
        UUID sessionId =
                session.request.sessionId();
        int timeoutSeconds = Math.max(
                5,
                core.getConfig().getInt(
                        "origin-rtp.search.timeout-seconds",
                        30
                )
        );

        session.searchTimeoutTask = core.getServer()
                .getScheduler()
                .runTaskLater(
                        core,
                        () -> timeoutSearch(
                                playerId,
                                sessionId
                        ),
                        timeoutSeconds * 20L
                );

        future.whenComplete(
                (location, throwable) ->
                        Bukkit.getScheduler().runTask(
                                core,
                                () -> completeSearch(
                                        playerId,
                                        sessionId,
                                        location,
                                        throwable
                                )
                        )
        );
    }

    private void completeSearch(
            UUID playerId,
            UUID sessionId,
            Location location,
            Throwable throwable
    ) {
        Session session = sessions.get(playerId);

        if (session == null
                || !session.request.sessionId()
                .equals(sessionId)
                || session.phase != Phase.SEARCHING) {
            return;
        }

        session.cancelSearchTimeout();
        session.searchFuture = null;

        Player player = Bukkit.getPlayer(playerId);

        if (player == null || !player.isOnline()) {
            sessions.remove(playerId);
            session.cancelTasks();
            return;
        }

        if (throwable != null || location == null) {
            sessions.remove(playerId);
            session.cancelTasks();
            error(
                    player,
                    message(
                            session.request.destination(),
                            "failed"
                    )
            );
            return;
        }

        session.reservedLocation = location;
        session.phase = Phase.READY;
        readyQueue.addLast(sessionId);

    }

    private void timeoutSearch(
            UUID playerId,
            UUID sessionId
    ) {
        Session session = sessions.get(playerId);

        if (session == null
                || !session.request.sessionId()
                .equals(sessionId)
                || session.phase != Phase.SEARCHING) {
            return;
        }

        sessions.remove(playerId);
        session.cancelTasks();

        Player player = Bukkit.getPlayer(playerId);

        if (player != null && player.isOnline()) {
            error(
                    player,
                    message(
                            session.request.destination(),
                            "failed"
                    )
            );
        }
    }

    private void beginCountdown(
            Player player,
            Session session
    ) {
        session.phase = Phase.COUNTDOWN;
        session.startLocation =
                player.getLocation().clone();
        session.secondsRemaining =
                session.request.plus()
                        ? plusDelaySeconds()
                        : defaultDelaySeconds();

        if (session.secondsRemaining <= 0) {
            finishTeleport(
                    player.getUniqueId(),
                    session.request.sessionId()
            );
            return;
        }

        sendCountdownActionBar(player, session);
        SoundService.teleportCountdown(
                player,
                core
        );

        UUID playerId = player.getUniqueId();
        UUID sessionId =
                session.request.sessionId();

        session.countdownTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        () -> tickCountdown(
                                playerId,
                                sessionId
                        ),
                        20L,
                        20L
                );
    }

    private void tickCountdown(
            UUID playerId,
            UUID sessionId
    ) {
        Session session = sessions.get(playerId);

        if (session == null
                || !session.request.sessionId()
                .equals(sessionId)
                || session.phase != Phase.COUNTDOWN) {
            return;
        }

        Player player = Bukkit.getPlayer(playerId);

        if (player == null || !player.isOnline()) {
            sessions.remove(playerId);
            session.cancelTasks();
            return;
        }

        if (cancelOnMove(
                session.request.destination()
        )
                && movedTooFar(
                session.startLocation,
                player.getLocation()
        )) {
            cancel(player, true);
            return;
        }

        session.secondsRemaining--;

        if (session.secondsRemaining <= 0) {
            finishTeleport(playerId, sessionId);
            return;
        }

        sendCountdownActionBar(player, session);
        SoundService.teleportCountdown(
                player,
                core
        );
    }

    private void sendCountdownActionBar(
            Player player,
            Session session
    ) {
        sendActionBar(
                player,
                message(
                        session.request.destination(),
                        "countdown"
                ).replace(
                        "%seconds%",
                        String.valueOf(
                                session.secondsRemaining
                        )
                )
        );
    }

    private void finishTeleport(
            UUID playerId,
            UUID sessionId
    ) {
        Session session = sessions.get(playerId);

        if (session == null
                || !session.request.sessionId()
                .equals(sessionId)
                || session.phase != Phase.COUNTDOWN) {
            return;
        }

        if (session.countdownTask != null) {
            session.countdownTask.cancel();
            session.countdownTask = null;
        }

        Player player = Bukkit.getPlayer(playerId);

        if (player == null || !player.isOnline()) {
            sessions.remove(playerId);
            session.cancelTasks();
            return;
        }

        if (cancelOnMove(
                session.request.destination()
        )
                && movedTooFar(
                session.startLocation,
                player.getLocation()
        )) {
            cancel(player, true);
            return;
        }

        Location validated =
                locationService.revalidateReservedLocation(
                        session.reservedLocation,
                        session.request.destination()
                );

        /*
         * The border may have changed during the countdown. Re-search with
         * the same session instead of teleporting outside the live border.
         */
        if (validated == null) {
            session.reservedLocation = null;
            session.startLocation = null;
            session.phase = Phase.QUEUED;
            enqueueSearch(session, true);

            sendActionBar(
                    player,
                    message(
                            session.request.destination(),
                            "queued"
                    )
            );
            return;
        }

        session.phase = Phase.TELEPORTING;
        boolean teleported = player.teleport(validated);

        if (!teleported) {
            sessions.remove(playerId);
            session.cancelTasks();
            error(
                    player,
                    message(
                            session.request.destination(),
                            "failed"
                    )
            );
            return;
        }

        sessions.remove(playerId);
        session.cancelTasks();
        applyCooldown(player);
        applyLandingProtection(player);

        sendActionBar(
                player,
                message(
                        session.request.destination(),
                        "teleported"
                )
        );
        SoundService.teleportComplete(
                player,
                core
        );
    }

    private void enqueueSearch(
            Session session,
            boolean first
    ) {
        session.phase = Phase.QUEUED;
        UUID sessionId =
                session.request.sessionId();
        Deque<UUID> queue =
                session.request.plus()
                        && plusPriority()
                        ? plusQueue
                        : defaultQueue;

        if (first) {
            queue.addFirst(sessionId);
        } else {
            queue.addLast(sessionId);
        }
    }

    private void removeOfflineSessions() {
        Iterator<Map.Entry<UUID, Session>> iterator =
                sessions.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry =
                    iterator.next();
            Player player = Bukkit.getPlayer(
                    entry.getKey()
            );

            if (player != null && player.isOnline()) {
                continue;
            }

            Session session = entry.getValue();
            removeFromQueues(
                    session.request.sessionId()
            );
            session.cancelTasks();
            iterator.remove();
        }
    }

    private void removeFromQueues(UUID sessionId) {
        plusQueue.remove(sessionId);
        defaultQueue.remove(sessionId);
        readyQueue.remove(sessionId);
    }

    private Session sessionById(UUID sessionId) {
        if (sessionId == null) {
            return null;
        }

        for (Session session : sessions.values()) {
            if (session.request.sessionId()
                    .equals(sessionId)) {
                return session;
            }
        }

        return null;
    }

    private int countPhase(Phase phase) {
        int count = 0;

        for (Session session : sessions.values()) {
            if (session.phase == phase) {
                count++;
            }
        }

        return count;
    }

    private int queuePosition(UUID sessionId) {
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

    private boolean movedTooFar(
            Location start,
            Location current
    ) {
        return TeleportMovement.movedTooFar(
                core,
                start,
                current
        );
    }

    private boolean enabled(String destination) {
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
        String permission = core.getConfig().getString(
                "origin-rtp.plus.permission",
                core.getConfig().getString(
                        "teleport-perks.plus-permission",
                        "mineacle.plus"
                )
        );

        return player.hasPermission(permission);
    }

    private long cooldownRemainingSeconds(
            Player player
    ) {
        Long until = cooldowns.get(
                player.getUniqueId()
        );

        if (until == null) {
            return 0L;
        }

        long remaining = until
                - System.currentTimeMillis();

        if (remaining <= 0L) {
            cooldowns.remove(
                    player.getUniqueId()
            );
            return 0L;
        }

        return Math.max(
                1L,
                (remaining + 999L) / 1000L
        );
    }

    private void applyCooldown(Player player) {
        String permission = core.getConfig().getString(
                "origin-rtp.plus.permission",
                "mineacle.plus"
        );
        int seconds = Math.max(
                0,
                core.getConfig().getInt(
                        player.hasPermission(permission)
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

    private void applyLandingProtection(Player player) {
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
        String raw = core.getConfig().getString(
                destinationPath
        );

        if (raw == null) {
            raw = core.getConfig().getString(
                    "origin-rtp.messages." + key,
                    key.equals("countdown")
                            ? "&#bbbbbbTeleporting to &d%world% &#bbbbbbin &d%seconds%s"
                            : "&cMissing RTP message: " + key
            );
        }

        return TextColor.color(
                raw.replace(
                        "%world%",
                        displayName(destination)
                )
        );
    }

    private String displayName(String destination) {
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
        SoundService.guiError(player, core);
    }

    private void send(
            Player player,
            String message
    ) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(
                actionBar(colored)
        );
    }

    private void sendActionBar(
            Player player,
            String message
    ) {
        player.sendActionBar(
                actionBar(message)
        );
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }

    private enum Phase {
        QUEUED,
        SEARCHING,
        READY,
        COUNTDOWN,
        TELEPORTING
    }

    private static final class Session {

        private final OriginRtpRequest request;
        private Phase phase = Phase.QUEUED;
        private Location reservedLocation;
        private Location startLocation;
        private int secondsRemaining;
        private BukkitTask countdownTask;
        private BukkitTask searchTimeoutTask;
        private CompletableFuture<Location> searchFuture;

        private Session(OriginRtpRequest request) {
            this.request = request;
        }

        private void cancelSearchTimeout() {
            if (searchTimeoutTask != null) {
                searchTimeoutTask.cancel();
                searchTimeoutTask = null;
            }
        }

        private void cancelTasks() {
            if (countdownTask != null) {
                countdownTask.cancel();
                countdownTask = null;
            }

            cancelSearchTimeout();

            if (searchFuture != null
                    && !searchFuture.isDone()) {
                searchFuture.cancel(false);
            }

            searchFuture = null;
        }
    }
}
