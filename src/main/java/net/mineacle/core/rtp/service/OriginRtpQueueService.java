package net.mineacle.core.rtp.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.listener.PortalFreezeListener;
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
        if (!enabled()) {
            sendActionBar(player, message("disabled"));
            SoundService.guiError(player, core);
            return;
        }

        UUID playerId = player.getUniqueId();

        if (queuedRequests.containsKey(playerId) || activeRequests.containsKey(playerId)) {
            sendActionBar(player, message("already-queued"));
            SoundService.guiError(player, core);
            return;
        }

        boolean plus = isPlus(player);

        OriginRtpRequest request = new OriginRtpRequest(
                playerId,
                player.getName(),
                player.getLocation().clone(),
                plus,
                System.currentTimeMillis()
        );

        if (plus && plusPriority()) {
            plusQueue.addLast(request);
        } else {
            defaultQueue.addLast(request);
        }

        queuedRequests.put(playerId, request);

        String queuedMessage = message("queued-position")
                .replace("%position%", String.valueOf(position(playerId)))
                .replace("%type%", plus ? "Mineacle+" : "Default")
                .replace("%world%", world());

        sendActionBar(player, queuedMessage);
        SoundService.teleportRequest(player, core);
    }

    public void cancel(Player player, boolean sendMessage) {
        UUID playerId = player.getUniqueId();

        OriginRtpRequest queued = queuedRequests.remove(playerId);

        if (queued != null) {
            plusQueue.removeIf(request -> request.playerId().equals(playerId));
            defaultQueue.removeIf(request -> request.playerId().equals(playerId));

            if (sendMessage) {
                sendActionBar(player, TextColor.color(CANCELLED_MOVE_MESSAGE));
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
                SoundService.teleportCancelled(player, core);
            }
        }
    }

    public void handleMove(Player player) {
        if (!cancelOnMove()) {
            return;
        }

        UUID playerId = player.getUniqueId();

        OriginRtpRequest queued = queuedRequests.get(playerId);

        if (queued != null) {
            lockToStart(player, queued.startLocation());
            return;
        }

        ActiveRtp active = activeRequests.get(playerId);

        if (active != null) {
            lockToStart(player, active.startLocation());
        }
    }

    private void lockToStart(Player player, Location startLocation) {
        if (player == null || startLocation == null) {
            return;
        }

        if (player.getWorld() == null || startLocation.getWorld() == null) {
            return;
        }

        if (!player.getWorld().equals(startLocation.getWorld())) {
            return;
        }

        if (player.getLocation().distanceSquared(startLocation) <= 0.04D) {
            return;
        }

        Location locked = startLocation.clone();
        locked.setYaw(player.getLocation().getYaw());
        locked.setPitch(player.getLocation().getPitch());

        PortalFreezeListener.skipNextFreeze(player, core);
        player.teleport(locked);
        sendActionBar(player, message("searching"));
    }

    private void processQueue() {
        if (!enabled()) {
            return;
        }

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
        int delay = request.plus() ? plusDelaySeconds() : defaultDelaySeconds();

        if (delay <= 0) {
            beginNativeRtp(player);
            return;
        }

        ActiveRtp active = new ActiveRtp(
                request.startLocation(),
                delay,
                null
        );

        activeRequests.put(player.getUniqueId(), active);
        sendActionBar(player, countdownMessage(delay));
        SoundService.teleportCountdown(player, core);

        BukkitTask task = core.getServer().getScheduler().runTaskTimer(
                core,
                () -> tickCountdown(player),
                20L,
                20L
        );

        activeRequests.put(
                player.getUniqueId(),
                new ActiveRtp(
                        request.startLocation(),
                        delay,
                        task
                )
        );
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

        lockToStart(player, active.startLocation());

        int nextSeconds = active.secondsRemaining() - 1;

        if (nextSeconds <= 0) {
            BukkitTask task = active.task();

            if (task != null) {
                task.cancel();
            }

            activeRequests.remove(player.getUniqueId());
            beginNativeRtp(player);
            return;
        }

        activeRequests.put(
                player.getUniqueId(),
                new ActiveRtp(
                        active.startLocation(),
                        nextSeconds,
                        active.task()
                )
        );

        sendActionBar(player, countdownMessage(nextSeconds));
        SoundService.teleportCountdown(player, core);
    }

    private void beginNativeRtp(Player player) {
        if (!player.isOnline()) {
            return;
        }

        sendActionBar(player, message("searching"));

        locationService.findSafeLocation().thenAccept(location -> Bukkit.getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                return;
            }

            if (location == null) {
                sendActionBar(player, message("failed"));
                SoundService.guiError(player, core);
                return;
            }

            PortalFreezeListener.skipNextFreeze(player, core);
            player.teleport(location);
            sendActionBar(player, message("teleported"));
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

    private String countdownMessage(int seconds) {
        return message("countdown")
                .replace("%seconds%", String.valueOf(seconds))
                .replace("%world%", world());
    }

    private boolean enabled() {
        return core.getConfig().getBoolean("origin-rtp.enabled", true);
    }

    private String world() {
        return core.getConfig().getString("origin-rtp.world", "origins");
    }

    private boolean cancelOnMove() {
        return core.getConfig().getBoolean("origin-rtp.teleport.cancel-on-move", true);
    }

    private int defaultDelaySeconds() {
        return Math.max(0, core.getConfig().getInt("origin-rtp.default.delay-seconds", 5));
    }

    private int plusDelaySeconds() {
        return Math.max(0, core.getConfig().getInt("origin-rtp.plus.delay-seconds",
                core.getConfig().getInt("teleport-perks.plus-delay-seconds", 3)));
    }

    private boolean plusPriority() {
        return core.getConfig().getBoolean("origin-rtp.plus.priority", true);
    }

    private boolean isPlus(Player player) {
        String permission = core.getConfig().getString("origin-rtp.plus.permission",
                core.getConfig().getString("teleport-perks.plus-permission", "mineacle.plus"));
        return player.hasPermission(permission);
    }

    private String message(String key) {
        return TextColor.color(core.getConfig().getString(
                "origin-rtp.messages." + key,
                "&cMissing origin-rtp message: " + key
        ));
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
            BukkitTask task
    ) {
    }
}