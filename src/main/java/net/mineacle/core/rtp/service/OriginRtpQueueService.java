package net.mineacle.core.rtp.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
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
    private final Deque<UUID> searchQueue =
            new ArrayDeque<>();
    private final Map<UUID, Session> sessions =
            new HashMap<>();
    private final Map<UUID, Long> cooldowns =
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
        searchQueue.clear();
        sessions.clear();
        cooldowns.clear();
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

        if (!isKnownDestination(destination)
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

        if (plus && plusPriority()) {
            plusQueue.addLast(
                    request.sessionId()
            );
        } else {
            defaultQueue.addLast(
                    request.sessionId()
            );
        }

        send(
                player,
                message(destination, "queued")
                        .replace(
                                "%position%",
                                String.valueOf(
                                        queuePosition(
                                                request.sessionId()
                                        )
                                )
                        )
                        .replace(
                                "%type%",
                                plus
                                        ? "Mineacle+"
                                        : "Default"
                        )
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

        removeFromQueues(session.request.sessionId());
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

    public void handleMove(Player player) {
        if (player == null) {
            return;
        }

        Session session = sessions.get(
                player.getUniqueId()
        );

        if (session == null
                || session.phase == Phase.QUEUED
                || !cancelOnMove(
                session.request.destination()
        )) {
            return;
        }

        if (movedTooFar(
                session.startLocation,
                player.getLocation(),
                session.request.destination()
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

    private void process() {
        removeOfflineSessions();
        startCountdowns();
        startSearches();
        cleanupCooldowns();
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
                continue;
            }

            beginCountdown(player, session);
        }
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
            UUID sessionId = searchQueue.pollFirst();

            if (sessionId == null) {
                return;
            }

            Session session = sessionById(sessionId);

            if (session == null
                    || session.phase
                    != Phase.WAITING_SEARCH) {
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

            beginSearch(player, session);
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
            queueSearch(session);
            return;
        }

        send(
                player,
                countdownMessage(
                        session.request.destination(),
                        session.secondsRemaining
                )
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
                player.getLocation(),
                session.request.destination()
        )) {
            cancel(player, true);
            return;
        }

        session.secondsRemaining--;

        if (session.secondsRemaining <= 0) {
            if (session.countdownTask != null) {
                session.countdownTask.cancel();
                session.countdownTask = null;
            }

            queueSearch(session);
            return;
        }

        sendActionBar(
                player,
                countdownMessage(
                        session.request.destination(),
                        session.secondsRemaining
                )
        );
        SoundService.teleportCountdown(
                player,
                core
        );
    }

    private void queueSearch(Session session) {
        session.phase = Phase.WAITING_SEARCH;
        searchQueue.addLast(
                session.request.sessionId()
        );
    }

    private void beginSearch(
            Player player,
            Session session
    ) {
        session.phase = Phase.SEARCHING;

        sendActionBar(
                player,
                message(
                        session.request.destination(),
                        "searching"
                )
        );

        CompletableFuture<Location> future =
                locationService.findSafeLocation(
                        session.request.destination()
                );
        session.searchFuture = future;
        UUID playerId = player.getUniqueId();
        UUID sessionId =
                session.request.sessionId();

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
                player.getLocation(),
                session.request.destination()
        )) {
            cancel(player, true);
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

        boolean teleported = player.teleport(location);

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

        send(
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
        searchQueue.remove(sessionId);
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
            Location current,
            String destination
    ) {
        if (start == null
                || current == null
                || start.getWorld() == null
                || current.getWorld() == null
                || !start.getWorld().equals(
                current.getWorld()
        )) {
            return true;
        }

        double allowed = cancelDistance(destination);

        return start.distanceSquared(current)
                > allowed * allowed;
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

    private boolean isKnownDestination(
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

    private double cancelDistance(
            String destination
    ) {
        return Math.max(
                0.01D,
                core.getConfig().getDouble(
                        "origin-rtp.destinations."
                                + destination
                                + ".teleport.cancel-distance",
                        core.getConfig().getDouble(
                                "origin-rtp.teleport.cancel-distance",
                                2.0D
                        )
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
        int seconds = Math.max(
                0,
                core.getConfig().getInt(
                        player.hasPermission(
                                core.getConfig().getString(
                                        "origin-rtp.plus.permission",
                                        "mineacle.plus"
                                )
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

    private void cleanupCooldowns() {
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(
                entry -> entry.getValue() <= now
        );
    }

    private String countdownMessage(
            String destination,
            int seconds
    ) {
        return message(destination, "countdown")
                .replace(
                        "%seconds%",
                        String.valueOf(seconds)
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
                    "&cMissing RTP message: " + key
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
        COUNTDOWN,
        WAITING_SEARCH,
        SEARCHING
    }

    private static final class Session {

        private final OriginRtpRequest request;
        private Phase phase = Phase.QUEUED;
        private Location startLocation;
        private int secondsRemaining;
        private BukkitTask countdownTask;
        private CompletableFuture<Location> searchFuture;

        private Session(OriginRtpRequest request) {
            this.request = request;
        }

        private void cancelTasks() {
            if (countdownTask != null) {
                countdownTask.cancel();
                countdownTask = null;
            }

            if (searchFuture != null
                    && !searchFuture.isDone()) {
                searchFuture.cancel(false);
            }

            searchFuture = null;
        }
    }
}
