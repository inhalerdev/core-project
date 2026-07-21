package net.mineacle.core.common.sound;

import net.mineacle.core.Core;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"deprecation", "removal"})
public final class SoundService {

    private static final Map<String, Sound> SOUND_CACHE =
            new ConcurrentHashMap<>();
    private static final Set<String> INVALID_SOUNDS =
            ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Map<String, Long>> LAST_PLAYED =
            new ConcurrentHashMap<>();
    private static final Map<UUID, PendingPlayback> PENDING =
            new ConcurrentHashMap<>();

    private SoundService() {
    }

    /**
     * Queues one player sound for the next tick.
     *
     * Multiple sound requests produced by one GUI action are coalesced and
     * only the highest-priority semantic sound is played. This prevents a
     * generic button click from stacking with confirm, error, payment,
     * teleport, invite, delete, or toggle sounds from the same action.
     */
    public static void play(
            Player player,
            Core core,
            String path
    ) {
        if (!validRequest(player, core, path)) {
            return;
        }

        String basePath = "sounds." + settingsPath(path);

        if (!core.getConfig().getBoolean(
                basePath + ".enabled",
                true
        )) {
            return;
        }

        String configured = core.getConfig().getString(
                basePath + ".sound",
                ""
        );

        if (configured == null || configured.isBlank()) {
            return;
        }

        queue(
                player,
                core,
                path,
                priority(path)
        );
    }

    public static void clearPlayer(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        LAST_PLAYED.remove(playerId);

        PendingPlayback pending = PENDING.remove(playerId);

        if (pending != null && pending.task() != null) {
            pending.task().cancel();
        }
    }

    public static void clearCache() {
        SOUND_CACHE.clear();
        INVALID_SOUNDS.clear();
        LAST_PLAYED.clear();

        for (PendingPlayback pending : PENDING.values()) {
            if (pending.task() != null) {
                pending.task().cancel();
            }
        }

        PENDING.clear();
    }

    private static boolean validRequest(
            Player player,
            Core core,
            String path
    ) {
        return player != null
                && core != null
                && path != null
                && !path.isBlank()
                && player.isOnline()
                && core.isEnabled()
                && core.getConfig().getBoolean(
                "sounds.enabled",
                true
        );
    }

    private static void queue(
            Player player,
            Core core,
            String path,
            int priority
    ) {
        UUID playerId = player.getUniqueId();

        PENDING.compute(playerId, (ignored, existing) -> {
            if (existing == null) {
                BukkitTask task = core.getServer()
                        .getScheduler()
                        .runTask(
                                core,
                                () -> flush(
                                        playerId,
                                        player,
                                        core
                                )
                        );

                return new PendingPlayback(
                        path,
                        priority,
                        task
                );
            }

            if (priority > existing.priority()) {
                return new PendingPlayback(
                        path,
                        priority,
                        existing.task()
                );
            }

            return existing;
        });
    }

    private static void flush(
            UUID playerId,
            Player player,
            Core core
    ) {
        PendingPlayback pending =
                PENDING.remove(playerId);

        if (pending == null
                || !validRequest(
                player,
                core,
                pending.path()
        )) {
            return;
        }

        playNow(
                player,
                core,
                pending.path()
        );
    }

    private static void playNow(
            Player player,
            Core core,
            String path
    ) {
        String settingsPath = settingsPath(path);
        String basePath = "sounds." + settingsPath;

        if (!core.getConfig().getBoolean(
                basePath + ".enabled",
                true
        )
                || !claimPlayback(
                player,
                core,
                settingsPath,
                basePath
        )) {
            return;
        }

        String configured = core.getConfig().getString(
                basePath + ".sound",
                ""
        );

        if (configured == null || configured.isBlank()) {
            return;
        }

        float volume = nonNegative(
                core.getConfig().getDouble(
                        "sounds.volume",
                        0.8D
                )
        );
        float pitch = nonNegative(
                core.getConfig().getDouble(
                        basePath + ".pitch",
                        1.0D
                )
        );
        String soundName = configured.trim();
        Sound sound = resolveEnumSound(soundName);

        try {
            if (sound != null) {
                player.playSound(
                        player.getLocation(),
                        sound,
                        volume,
                        pitch
                );
                return;
            }

            if (isKeyStyle(soundName)) {
                player.playSound(
                        player.getLocation(),
                        soundName.toLowerCase(Locale.ROOT),
                        volume,
                        pitch
                );
                return;
            }

            warnInvalidOnce(
                    core,
                    basePath,
                    soundName
            );
        } catch (IllegalArgumentException exception) {
            warnInvalidOnce(
                    core,
                    basePath,
                    soundName
            );
        }
    }

    private static boolean claimPlayback(
            Player player,
            Core core,
            String path,
            String basePath
    ) {
        long fallback = path.startsWith("gui.")
                ? core.getConfig().getLong(
                "sounds.gui.minimum-interval-millis",
                55L
        )
                : core.getConfig().getLong(
                "sounds.minimum-interval-millis",
                50L
        );
        long minimumInterval = Math.max(
                0L,
                core.getConfig().getLong(
                        basePath
                                + ".minimum-interval-millis",
                        fallback
                )
        );

        if (minimumInterval <= 0L) {
            return true;
        }

        long now = System.currentTimeMillis();
        Map<String, Long> playerTimes =
                LAST_PLAYED.computeIfAbsent(
                        player.getUniqueId(),
                        ignored ->
                                new ConcurrentHashMap<>()
                );
        Long previous = playerTimes.get(path);

        if (previous != null
                && now - previous < minimumInterval) {
            return false;
        }

        playerTimes.put(path, now);
        return true;
    }

    private static int priority(String path) {
        String normalized = path.toLowerCase(
                Locale.ROOT
        );

        if (normalized.equals("gui.error")
                || normalized.endsWith(".error")
                || normalized.contains("blocked")) {
            return 100;
        }

        if (normalized.equals("gui.delete")
                || normalized.contains("disband")
                || normalized.endsWith(".delete")) {
            return 98;
        }

        if (normalized.equals("gui.cancel")
                || normalized.contains("cancelled")) {
            return 95;
        }

        if (normalized.equals("gui.confirm")
                || normalized.contains("complete")
                || normalized.contains("create")
                || normalized.contains("claim")
                || normalized.contains("receive")
                || normalized.contains("pay")
                || normalized.endsWith(".set")) {
            return 90;
        }

        if (normalized.startsWith("feature.")) {
            return 92;
        }

        if (normalized.contains("request")
                || normalized.contains("received")
                || normalized.contains("invite")) {
            return 80;
        }

        if (normalized.equals("gui.refresh")) {
            return 45;
        }

        if (normalized.equals("gui.search")
                || normalized.equals("gui.sort")
                || normalized.equals("gui.filter")) {
            return 40;
        }

        if (normalized.equals("gui.page")
                || normalized.equals("gui.back")) {
            return 35;
        }

        if (normalized.equals("gui.select")) {
            return 30;
        }

        if (normalized.startsWith("gui.")) {
            return 20;
        }

        if (normalized.contains("countdown")) {
            /*
             * A teleport countdown tick is the selection feedback for flows
             * such as TPA where the first number is announced immediately.
             * Let it replace a same-tick GUI selection sound so the player
             * hears one clean sound instead of two overlapping sounds.
             */
            return 70;
        }

        return 60;
    }

    private static String settingsPath(String path) {
        String normalized = path.toLowerCase(Locale.ROOT);

        if (normalized.equals("gui.click")
                || normalized.equals("gui.back")
                || normalized.equals("gui.page")
                || normalized.equals("gui.sort")
                || normalized.equals("gui.filter")
                || normalized.equals("gui.search")
                || normalized.equals("gui.refresh")
                || normalized.equals("gui.select")) {
            return "gui.select";
        }

        return path;
    }

    private static Sound resolveEnumSound(String input) {
        String cacheKey = input
                .trim()
                .toUpperCase(Locale.ROOT)
                .replace(':', '_')
                .replace('.', '_');
        Sound cached = SOUND_CACHE.get(cacheKey);

        if (cached != null) {
            return cached;
        }

        try {
            Sound sound = Sound.valueOf(cacheKey);
            SOUND_CACHE.put(cacheKey, sound);
            return sound;
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean isKeyStyle(String soundName) {
        return soundName.indexOf(':') >= 0
                || soundName.indexOf('.') >= 0;
    }

    private static void warnInvalidOnce(
            Core core,
            String path,
            String soundName
    ) {
        String warningKey = path + "=" + soundName;

        if (INVALID_SOUNDS.add(warningKey)) {
            core.getLogger().warning(
                    "Invalid sound configured at "
                            + path
                            + ".sound: "
                            + soundName
            );
        }
    }

    private static float nonNegative(double value) {
        if (!Double.isFinite(value)
                || value < 0.0D) {
            return 0.0F;
        }

        return (float) value;
    }

    public static void guiClick(
            Player player,
            Core core
    ) {
        play(player, core, "gui.click");
    }

    public static void guiBack(
            Player player,
            Core core
    ) {
        play(player, core, "gui.back");
    }

    public static void guiPage(
            Player player,
            Core core
    ) {
        play(player, core, "gui.page");
    }

    public static void guiSort(
            Player player,
            Core core
    ) {
        play(player, core, "gui.sort");
    }

    public static void guiFilter(
            Player player,
            Core core
    ) {
        play(player, core, "gui.filter");
    }

    public static void guiSearch(
            Player player,
            Core core
    ) {
        play(player, core, "gui.search");
    }

    public static void guiRefresh(
            Player player,
            Core core
    ) {
        play(player, core, "gui.refresh");
    }

    public static void guiSelect(
            Player player,
            Core core
    ) {
        play(player, core, "gui.select");
    }

    public static void guiConfirm(
            Player player,
            Core core
    ) {
        play(player, core, "gui.confirm");
    }

    public static void guiCancel(
            Player player,
            Core core
    ) {
        play(player, core, "gui.cancel");
    }

    public static void guiDelete(
            Player player,
            Core core
    ) {
        play(player, core, "gui.delete");
    }

    public static void guiError(
            Player player,
            Core core
    ) {
        play(player, core, "gui.error");
    }

    public static void guiUsage(
            Player player,
            Core core
    ) {
        play(player, core, "gui.usage");
    }

    public static void mineaclePlus(
            Player player,
            Core core
    ) {
        play(player, core, "mineacle-plus.blocked");
    }

    public static void teleportStart(
            Player player,
            Core core
    ) {
        play(player, core, "teleport.start");
    }

    public static void teleportCountdown(
            Player player,
            Core core
    ) {
        play(player, core, "teleport.countdown");
    }

    public static void teleportTick(
            Player player,
            Core core
    ) {
        teleportCountdown(player, core);
    }

    public static void teleportCancelled(
            Player player,
            Core core
    ) {
        play(player, core, "teleport.cancelled");
    }

    public static void teleportCancel(
            Player player,
            Core core
    ) {
        teleportCancelled(player, core);
    }

    public static void teleportComplete(
            Player player,
            Core core
    ) {
        play(player, core, "teleport.complete");
    }

    public static void teleportRequest(
            Player player,
            Core core
    ) {
        play(player, core, "teleport.request");
    }

    public static void teleportReceived(
            Player player,
            Core core
    ) {
        play(player, core, "teleport.received");
    }

    public static void portalFreeze(
            Player player,
            Core core
    ) {
        play(player, core, "portal.freeze");
    }

    public static void spawnOpen(
            Player player,
            Core core
    ) {
        play(player, core, "spawn.open");
    }

    public static void spawnArrive(
            Player player,
            Core core
    ) {
        play(player, core, "spawn.arrive");
    }

    public static void homeSet(
            Player player,
            Core core
    ) {
        play(player, core, "homes.set");
    }

    public static void homeDelete(
            Player player,
            Core core
    ) {
        play(player, core, "homes.delete");
    }

    public static void teamInvite(
            Player player,
            Core core
    ) {
        play(player, core, "teams.invite");
    }

    public static void teamCreate(
            Player player,
            Core core
    ) {
        play(player, core, "teams.create");
    }

    public static void teamDisband(
            Player player,
            Core core
    ) {
        play(player, core, "teams.disband");
    }

    public static void economyPay(
            Player player,
            Core core
    ) {
        play(player, core, "economy.pay");
    }

    public static void economyReceive(
            Player player,
            Core core
    ) {
        play(player, core, "economy.receive");
    }

    public static void economyBalance(
            Player player,
            Core core
    ) {
        play(player, core, "economy.balance");
    }

    public static void chatMessage(
            Player player,
            Core core
    ) {
        play(player, core, "chat.message");
    }

    public static void doubleJump(
            Player player,
            Core core
    ) {
        play(player, core, "double-jump.jump");
    }

    public static void doubleJumpCooldown(
            Player player,
            Core core
    ) {
        play(player, core, "double-jump.cooldown");
    }

    public static void featureEnable(
            Player player,
            Core core
    ) {
        play(player, core, "feature.enable");
    }

    public static void featureDisable(
            Player player,
            Core core
    ) {
        play(player, core, "feature.disable");
    }

    private record PendingPlayback(
            String path,
            int priority,
            BukkitTask task
    ) {
    }
}
