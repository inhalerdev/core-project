package net.mineacle.core.rtp.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class OriginRtpQueueService {

    private static final String CANCELLED_MOVE_MESSAGE = "&cTeleport cancelled — you moved";

    private final Core core;
    private final OriginRtpLocationService locationService;

    private final Deque<OriginRtpRequest> plusQueue = new ArrayDeque<>();
    private final Deque<OriginRtpRequest> defaultQueue = new ArrayDeque<>();
    private final Map<UUID, OriginRtpRequest> queuedRequests = new HashMap<>();
    private final Map<UUID, ActiveRtp> activeRequests = new HashMap<>();

    private BukkitTask processorTask;

    public OriginRtpQueueService(Core core) {
        this.core = core;
        this.locationService = new OriginRtpLocationService(core);
    }

    public void start() {
        stop();

        long interval = Math.max(1L, core.getConfig().getLong("origin-rtp.queue.process-every-ticks", 20L));

        processorTask = core.getServer().getScheduler().runTaskTimer(
                core,
                this::processQueue,
                interval,
                interval
        );
    }

    public void stop() {
        if (processorTask != null) {
            processorTask.cancel();
            processorTask = null;
        }

        for (ActiveRtp active : activeRequests.values()) {
            if (active.task() != null) {
                active.task().cancel();
            }
        }

        plusQueue.clear();
        defaultQueue.clear();
        queuedRequests.clear();
        activeRequests.clear();
    }

    public void request(Player player) {
        request(player, "origins");
    }

    public void request(Player player, String rtpKey) {
        String key = normalizeDestination(rtpKey);

        if (!enabled(key)) {
            sendActionBar(player, message(key, "disabled"));
            SoundService.guiError(player, core);
            return;
        }

        UUID playerId = player.getUniqueId();

        if (queuedRequests.containsKey(playerId) || activeRequests.containsKey(playerId)) {
            sendActionBar(player, message(key, "already-queued"));
            SoundService.guiError(player, core);
            return;
        }

        boolean plus = isPlus(player);

        OriginRtpRequest request = new OriginRtpRequest(
                playerId,
                player.getName(),
                player.getLocation().clone(),
                plus,
                key,
                System.currentTimeMillis()
        );

        if (plus && plusPriority(key)) {
            plusQueue.addLast(request);
        } else {
            defaultQueue.addLast(request);
        }

        queuedRequests.put(playerId, request);

        String queuedMessage = message(key, "queued-position")
                .replace("%position%", String.valueOf(position(playerId)))
                .replace("%type%", plus ? "Mineacle+" : "Default")
                .replace("%world%", displayName(key));

        sendActionBar(player, queuedMessage);
        SoundService.teleportStart(player, core);
    }

    public void cancel(Player player, boolean sendMessage) {
        UUID playerId = player.getUniqueId();

        OriginRtpRequest queued = queuedRequests.remove(playerId);

        if (queued != null) {
            plusQueue.removeIf(request -> request.playerId().equals(playerId));
            defaultQueue.removeIf(request -> request.playerId().equals(playerId));

            if (sendMessage) {
                sendActionBar(player, TextColor.color(CANCELLED_MOVE_MESSAGE));
                player.sendMessage(TextColor.color(CANCELLED_MOVE_MESSAGE));
                SoundService.teleportCancelled(player, core);
            }

            return;
        }

        ActiveRtp active = activeRequests.remove(playerId);

        if (active != null) {
            if (active.task() != null) {
                active.task().cancel();
            }

            if (sendMessage) {
                sendActionBar(player, TextColor.color(CANCELLED_MOVE_MESSAGE));
                player.sendMessage(TextColor.color(CANCELLED_MOVE_MESSAGE));
                SoundService.teleportCancelled(player, core);
            }
        }
    }

    public void handleMove(Player player) {
        UUID playerId = player.getUniqueId();

        OriginRtpRequest queued = queuedRequests.get(playerId);

        if (queued != null && cancelOnMove(queued.rtpKey()) && movedTooFar(queued.startLocation(), player.getLocation(), queued.rtpKey())) {
            cancel(player, true);
            return;
        }

        ActiveRtp active = activeRequests.get(playerId);

        if (active != null && cancelOnMove(active.rtpKey()) && movedTooFar(active.startLocation(), player.getLocation(), active.rtpKey())) {
            cancel(player, true);
        }
    }

    private void processQueue() {
        int max = Math.max(1, core.getConfig().getInt("origin-rtp.queue.max-processing-at-once", 1));
        int started = 0;

        while (started < max) {
            OriginRtpRequest request = pollNextValidRequest();

            if (request == null) {
                return;
            }

            Player player = Bukkit.getPlayer(request.playerId());

            if (player == null || !player.isOnline()) {
                queuedRequests.remove(request.playerId());
                continue;
            }

            queuedRequests.remove(request.playerId());
            beginCountdown(player, request);
            started++;
        }
    }

    private OriginRtpRequest pollNextValidRequest() {
        OriginRtpRequest request = plusQueue.pollFirst();

        if (request != null) {
            return request;
        }

        return defaultQueue.pollFirst();
    }

    private void beginCountdown(Player player, OriginRtpRequest request) {
        int delay = request.plus() ? plusDelaySeconds(request.rtpKey()) : defaultDelaySeconds(request.rtpKey());

        if (delay <= 0) {
            beginNativeRtp(player, request.rtpKey());
            return;
        }

        ActiveRtp active = new ActiveRtp(request.startLocation(), delay, request.rtpKey(), null);
        activeRequests.put(player.getUniqueId(), active);

        sendActionBar(player, countdownMessage(request.rtpKey(), delay));
        SoundService.teleportCountdown(player, core);

        BukkitTask task = core.getServer().getScheduler().runTaskTimer(
                core,
                () -> tickCountdown(player),
                20L,
                20L
        );

        activeRequests.put(player.getUniqueId(), new ActiveRtp(request.startLocation(), delay, request.rtpKey(), task));
    }

    private void tickCountdown(Player player) {
        ActiveRtp active = activeRequests.get(player.getUniqueId());

        if (active == null) {
            return;
        }

        if (!player.isOnline()) {
            cancel(player, false);
            return;
        }

        if (cancelOnMove(active.rtpKey()) && movedTooFar(active.startLocation(), player.getLocation(), active.rtpKey())) {
            cancel(player, true);
            return;
        }

        int nextSeconds = active.secondsRemaining() - 1;

        if (nextSeconds <= 0) {
            BukkitTask task = active.task();

            if (task != null) {
                task.cancel();
            }

            activeRequests.remove(player.getUniqueId());
            beginNativeRtp(player, active.rtpKey());
            return;
        }

        activeRequests.put(player.getUniqueId(), new ActiveRtp(active.startLocation(), nextSeconds, active.rtpKey(), active.task()));

        sendActionBar(player, countdownMessage(active.rtpKey(), nextSeconds));
        SoundService.teleportCountdown(player, core);
    }

    private void beginNativeRtp(Player player, String rtpKey) {
        if (!player.isOnline()) {
            return;
        }

        sendActionBar(player, message(rtpKey, "searching"));

        locationService.findSafeLocation(rtpKey).thenAccept(location -> Bukkit.getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (location == null) {
                sendActionBar(player, message(rtpKey, "failed"));
                SoundService.guiError(player, core);
                return;
            }

            player.teleport(location);
            sendActionBar(player, message(rtpKey, "teleported"));
            SoundService.teleportComplete(player, core);
        }));
    }

    private int position(UUID playerId) {
        int position = 1;

        for (OriginRtpRequest request : plusQueue) {
            if (request.playerId().equals(playerId)) {
                return position;
            }

            position++;
        }

        for (OriginRtpRequest request : defaultQueue) {
            if (request.playerId().equals(playerId)) {
                return position;
            }

            position++;
        }

        return position;
    }

    public void removeOfflineRequests() {
        removeOfflineFromQueue(plusQueue);
        removeOfflineFromQueue(defaultQueue);

        Iterator<Map.Entry<UUID, ActiveRtp>> iterator = activeRequests.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, ActiveRtp> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());

            if (player == null || !player.isOnline()) {
                if (entry.getValue().task() != null) {
                    entry.getValue().task().cancel();
                }

                iterator.remove();
            }
        }
    }

    private void removeOfflineFromQueue(Deque<OriginRtpRequest> queue) {
        queue.removeIf(request -> {
            Player player = Bukkit.getPlayer(request.playerId());

            if (player == null || !player.isOnline()) {
                queuedRequests.remove(request.playerId());
                return true;
            }

            return false;
        });
    }

    private boolean movedTooFar(Location start, Location current, String rtpKey) {
        if (start == null || current == null) {
            return true;
        }

        if (start.getWorld() == null || current.getWorld() == null) {
            return true;
        }

        if (!start.getWorld().equals(current.getWorld())) {
            return true;
        }

        double distance = cancelDistance(rtpKey);
        return start.distanceSquared(current) > distance * distance;
    }

    private String countdownMessage(String rtpKey, int seconds) {
        return message(rtpKey, "countdown")
                .replace("%seconds%", String.valueOf(seconds))
                .replace("%world%", displayName(rtpKey));
    }

    private boolean enabled(String rtpKey) {
        return core.getConfig().getBoolean("origin-rtp.destinations." + rtpKey + ".enabled",
                core.getConfig().getBoolean("origin-rtp.enabled", true));
    }

    private boolean cancelOnMove(String rtpKey) {
        return core.getConfig().getBoolean("origin-rtp.destinations." + rtpKey + ".teleport.cancel-on-move",
                core.getConfig().getBoolean("origin-rtp.teleport.cancel-on-move", true));
    }

    private double cancelDistance(String rtpKey) {
        return Math.max(0.01D, core.getConfig().getDouble("origin-rtp.destinations." + rtpKey + ".teleport.cancel-distance",
                core.getConfig().getDouble("origin-rtp.teleport.cancel-distance", 2.0D)));
    }

    private int defaultDelaySeconds(String rtpKey) {
        return Math.max(0, core.getConfig().getInt("origin-rtp.destinations." + rtpKey + ".default.delay-seconds",
                core.getConfig().getInt("origin-rtp.default.delay-seconds", 5)));
    }

    private int plusDelaySeconds(String rtpKey) {
        return Math.max(0, core.getConfig().getInt("origin-rtp.destinations." + rtpKey + ".plus.delay-seconds",
                core.getConfig().getInt("origin-rtp.plus.delay-seconds",
                        core.getConfig().getInt("teleport-perks.plus-delay-seconds", 3))));
    }

    private boolean plusPriority(String rtpKey) {
        return core.getConfig().getBoolean("origin-rtp.destinations." + rtpKey + ".plus.priority",
                core.getConfig().getBoolean("origin-rtp.plus.priority", true));
    }

    private boolean isPlus(Player player) {
        String permission = core.getConfig().getString("origin-rtp.plus.permission",
                core.getConfig().getString("teleport-perks.plus-permission", "mineacle.plus"));
        return player.hasPermission(permission);
    }

    private String displayName(String rtpKey) {
        return core.getConfig().getString("origin-rtp.destinations." + rtpKey + ".display-name",
                switch (rtpKey) {
                    case "nether" -> "Nether";
                    case "end" -> "End";
                    default -> "Origins";
                });
    }

    private String message(String rtpKey, String key) {
        String destinationPath = "origin-rtp.destinations." + rtpKey + ".messages." + key;

        String raw = core.getConfig().getString(destinationPath);

        if (raw == null) {
            raw = core.getConfig().getString("origin-rtp.messages." + key, "&cMissing origin-rtp message: " + key);
        }

        return TextColor.color(raw.replace("%world%", displayName(rtpKey)));
    }

    private String normalizeDestination(String input) {
        if (input == null || input.isBlank()) {
            return "origins";
        }

        String value = input.toLowerCase(Locale.ROOT);

        if (value.equals("origin") || value.equals("overworld") || value.equals("world")) {
            return "origins";
        }

        if (value.equals("the_nether")) {
            return "nether";
        }

        if (value.equals("the_end")) {
            return "end";
        }

        return value;
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    private record ActiveRtp(
            Location startLocation,
            int secondsRemaining,
            String rtpKey,
            BukkitTask task
    ) {
    }
}
