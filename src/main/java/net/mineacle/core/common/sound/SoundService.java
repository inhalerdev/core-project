package net.mineacle.core.common.sound;

import net.mineacle.core.Core;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SoundService {

    private static final Map<String, Sound> SOUND_CACHE =
            new ConcurrentHashMap<>();
    private static final Set<String> INVALID_SOUNDS =
            ConcurrentHashMap.newKeySet();
    private static final Map<UUID, Map<String, Long>> LAST_PLAYED =
            new ConcurrentHashMap<>();
    private static final Map<UUID, PendingPlayback> PENDING =
            new ConcurrentHashMap<>();

    private static volatile boolean registryAliasesLoaded;

    private SoundService() {
    }

    /**
     * Shared server-wide semantic sound entry point.
     * Feature code may keep descriptive paths such as homes.delete,
     * teleport.complete, teams.disband, or gui.error. SoundService resolves
     * every request into one central feedback intent, so individual systems
     * cannot invent their own success/error/delete/cancel sound identity.
     */
    public static void play(
            Player player,
            Core core,
            String path
    ) {
        if (invalidRequest(player, core, path)) {
            return;
        }

        SoundIntent intent = intentFor(path);

        if (intent == SoundIntent.SILENT
                || disabled(core, intent)) {
            return;
        }

        String configured = configuredSound(core, intent);

        if (configured.isBlank()) {
            return;
        }

        queue(
                player,
                core,
                intent
        );
    }

    /**
     * Explicit semantic API for new systems.
     */
    public static void navigate(
            Player player,
            Core core
    ) {
        playIntent(player, core, SoundIntent.NAVIGATE);
    }

    public static void tick(
            Player player,
            Core core
    ) {
        playIntent(player, core, SoundIntent.TICK);
    }

    public static void jump(
            Player player,
            Core core
    ) {
        playIntent(player, core, SoundIntent.JUMP);
    }

    public static void notice(
            Player player,
            Core core
    ) {
        playIntent(player, core, SoundIntent.NOTICE);
    }

    public static void success(
            Player player,
            Core core
    ) {
        playIntent(player, core, SoundIntent.SUCCESS);
    }

    public static void negative(
            Player player,
            Core core
    ) {
        playIntent(player, core, SoundIntent.NEGATIVE);
    }

    public static void clearPlayer(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        LAST_PLAYED.remove(playerId);

        PendingPlayback pending = PENDING.remove(playerId);

        if (pending != null
                && pending.task() != null) {
            pending.task().cancel();
        }
    }

    public static void clearCache() {
        SOUND_CACHE.clear();
        registryAliasesLoaded = false;
        INVALID_SOUNDS.clear();
        LAST_PLAYED.clear();

        for (PendingPlayback pending : PENDING.values()) {
            if (pending.task() != null) {
                pending.task().cancel();
            }
        }

        PENDING.clear();
    }

    private static void playIntent(
            Player player,
            Core core,
            SoundIntent intent
    ) {
        if (invalidRequest(
                player,
                core,
                intent.settingsPath()
        )
                || intent == SoundIntent.SILENT
                || disabled(core, intent)
                || configuredSound(
                core,
                intent
        ).isBlank()) {
            return;
        }

        queue(
                player,
                core,
                intent
        );
    }

    private static boolean invalidRequest(
            Player player,
            Core core,
            String path
    ) {
        return player == null
                || core == null
                || path == null
                || path.isBlank()
                || !player.isOnline()
                || !core.isEnabled()
                || !core.getConfig().getBoolean(
                "sounds.enabled",
                true
        );
    }

    private static void queue(
            Player player,
            Core core,
            SoundIntent intent
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
                        intent,
                        task
                );
            }

            if (intent.priority()
                    > existing.intent().priority()) {
                return new PendingPlayback(
                        intent,
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
                || invalidRequest(
                player,
                core,
                pending.intent().settingsPath()
        )) {
            return;
        }

        playNow(
                player,
                core,
                pending.intent()
        );
    }

    private static void playNow(
            Player player,
            Core core,
            SoundIntent intent
    ) {
        if (intent == SoundIntent.SILENT
                || disabled(core, intent)
                || !claimPlayback(
                player,
                core,
                intent
        )) {
            return;
        }

        String soundName =
                configuredSound(
                        core,
                        intent
                ).trim();

        if (soundName.isBlank()) {
            return;
        }

        float volume = nonNegative(
                core.getConfig().getDouble(
                        basePath(intent) + ".volume",
                        intent.defaultVolume()
                )
        );
        float pitch = nonNegative(
                core.getConfig().getDouble(
                        basePath(intent) + ".pitch",
                        intent.defaultPitch()
                )
        );
        Sound sound =
                resolveRegisteredSound(
                        soundName
                );

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
                        soundName.toLowerCase(
                                Locale.ROOT
                        ),
                        volume,
                        pitch
                );
                return;
            }

            warnInvalidOnce(
                    core,
                    basePath(intent),
                    soundName
            );
        } catch (IllegalArgumentException exception) {
            warnInvalidOnce(
                    core,
                    basePath(intent),
                    soundName
            );
        }
    }

    private static boolean claimPlayback(
            Player player,
            Core core,
            SoundIntent intent
    ) {
        long fallback = Math.max(
                0L,
                core.getConfig().getLong(
                        "sounds.feedback.minimum-interval-millis",
                        55L
                )
        );
        long minimumInterval = Math.max(
                0L,
                core.getConfig().getLong(
                        basePath(intent)
                                + ".minimum-interval-millis",
                        intent.defaultMinimumIntervalMillis()
                                >= 0L
                                ? intent.defaultMinimumIntervalMillis()
                                : fallback
                )
        );

        if (minimumInterval <= 0L) {
            return true;
        }

        long now = System.currentTimeMillis();
        String key = intent.settingsPath();
        Map<String, Long> playerTimes =
                LAST_PLAYED.computeIfAbsent(
                        player.getUniqueId(),
                        ignored ->
                                new ConcurrentHashMap<>()
                );
        Long previous =
                playerTimes.get(key);

        if (previous != null
                && now - previous < minimumInterval) {
            return false;
        }

        playerTimes.put(key, now);
        return true;
    }

    /**
     * Central semantic router.
     * Negative always wins conceptually over success:
     * cancel, deny, delete, disband, invalid, blocked, failed, no-permission,
     * disable and other "red" outcomes all resolve to feedback.negative.
     * Teleport countdown is a neutral tick and successful completion is the
     * shared success orb. Request/start sounds remain intentionally silent.
     */
    private static SoundIntent intentFor(
            String path
    ) {
        String normalized = normalize(path);

        if (normalized.isBlank()) {
            return SoundIntent.SILENT;
        }

        if (normalized.startsWith("feedback.")) {
            return switch (normalized) {
                case "feedback.navigate" ->
                        SoundIntent.NAVIGATE;
                case "feedback.jump" ->
                        SoundIntent.JUMP;
                case "feedback.tick" ->
                        SoundIntent.TICK;
                case "feedback.notice" ->
                        SoundIntent.NOTICE;
                case "feedback.success" ->
                        SoundIntent.SUCCESS;
                case "feedback.negative" ->
                        SoundIntent.NEGATIVE;
                default ->
                        SoundIntent.SILENT;
            };
        }

        if (normalized.equals("teleport.start")
                || normalized.equals("teleport.request")
                || normalized.equals("teleport.received")
                || normalized.equals("spawn.open")
                || normalized.equals("double-jump.cooldown")) {
            return SoundIntent.SILENT;
        }

        if (normalized.equals("double-jump.jump")) {
            return SoundIntent.JUMP;
        }

        if (normalized.contains("countdown")) {
            return SoundIntent.TICK;
        }

        if (negativePath(normalized)) {
            return SoundIntent.NEGATIVE;
        }

        if (successPath(normalized)) {
            return SoundIntent.SUCCESS;
        }

        if (noticePath(normalized)) {
            return SoundIntent.NOTICE;
        }

        return SoundIntent.NAVIGATE;
    }

    private static boolean negativePath(
            String path
    ) {
        return path.equals("gui.cancel")
                || path.equals("gui.delete")
                || path.equals("gui.error")
                || path.equals("gui.usage")
                || path.contains("cancel")
                || path.contains("delete")
                || path.contains("disband")
                || path.contains("deny")
                || path.contains("denied")
                || path.contains("reject")
                || path.contains("remove")
                || path.contains("blocked")
                || path.contains("invalid")
                || path.contains("failed")
                || path.contains("failure")
                || path.contains("error")
                || path.contains("no-permission")
                || path.contains("nopermission")
                || path.contains("permission-denied")
                || path.endsWith(".disable")
                || path.endsWith(".disabled")
                || path.equals("mineacle-plus.blocked");
    }

    private static boolean successPath(
            String path
    ) {
        return path.equals("gui.confirm")
                || path.contains("complete")
                || path.contains("arrive")
                || path.contains("success")
                || path.contains("create")
                || path.contains("claim")
                || path.contains("purchase")
                || path.contains("sold")
                || path.contains("accept")
                || path.contains("invite")
                || path.contains("receive")
                || path.contains("pay")
                || path.endsWith(".set")
                || path.endsWith(".enable")
                || path.endsWith(".enabled");
    }

    private static boolean noticePath(
            String path
    ) {
        return path.contains("message")
                || path.contains("notification")
                || path.contains("notify");
    }

    private static boolean disabled(
            Core core,
            SoundIntent intent
    ) {
        return !core.getConfig().getBoolean(
                basePath(intent) + ".enabled",
                intent.defaultEnabled()
        );
    }

    private static String configuredSound(
            Core core,
            SoundIntent intent
    ) {
        return core.getConfig().getString(
                basePath(intent) + ".sound",
                intent.defaultSound()
        );
    }

    private static String basePath(
            SoundIntent intent
    ) {
        return "sounds." + intent.settingsPath();
    }

    private static String normalize(
            String value
    ) {
        return value == null
                ? ""
                : value.trim()
                .toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves both modern registry keys and the Bukkit-style names used by
     * Mineacle configuration without guessing where underscores belong in
     * Mojang sound keys.
     *
     * <p>For example, {@code ENTITY_EXPERIENCE_ORB_PICKUP} maps to the real
     * registry key {@code minecraft:entity.experience_orb.pickup}. A blind
     * underscore-to-dot conversion would incorrectly produce
     * {@code entity.experience.orb.pickup} and break the sound.</p>
     */
    private static Sound resolveRegisteredSound(
            String input
    ) {
        if (input.isBlank()) {
            return null;
        }

        String trimmed = input.trim();
        String normalized =
                trimmed.toLowerCase(
                        Locale.ROOT
                );

        Sound cached =
                SOUND_CACHE.get(normalized);

        if (cached != null) {
            return cached;
        }

        NamespacedKey directKey =
                directSoundKey(normalized);

        if (directKey != null) {
            Sound direct =
                    Registry.SOUND_EVENT.get(
                            directKey
                    );

            if (direct != null) {
                cacheSoundAliases(
                        directKey,
                        direct
                );
                SOUND_CACHE.putIfAbsent(
                        normalized,
                        direct
                );
                return direct;
            }
        }

        ensureRegistryAliases();

        Sound alias =
                SOUND_CACHE.get(
                        trimmed.toUpperCase(
                                Locale.ROOT
                        )
                );

        if (alias != null) {
            SOUND_CACHE.putIfAbsent(
                    normalized,
                    alias
            );
        }

        return alias;
    }

    private static NamespacedKey directSoundKey(
            String normalized
    ) {
        if (normalized.isBlank()) {
            return null;
        }

        if (normalized.indexOf(':') >= 0) {
            return NamespacedKey.fromString(
                    normalized
            );
        }

        if (normalized.indexOf('.') >= 0) {
            return NamespacedKey.minecraft(
                    normalized
            );
        }

        return null;
    }

    private static void ensureRegistryAliases() {
        if (registryAliasesLoaded) {
            return;
        }

        synchronized (SoundService.class) {
            if (registryAliasesLoaded) {
                return;
            }

            Registry.SOUND_EVENT.keyStream()
                    .forEach(key -> {
                        Sound sound =
                                Registry.SOUND_EVENT.get(
                                        key
                                );

                        if (sound != null) {
                            cacheSoundAliases(
                                    key,
                                    sound
                            );
                        }
                    });

            registryAliasesLoaded = true;
        }
    }

    private static void cacheSoundAliases(
            NamespacedKey key,
            Sound sound
    ) {
        String fullKey =
                key.asString()
                        .toLowerCase(
                                Locale.ROOT
                        );
        String path =
                key.getKey()
                        .toLowerCase(
                                Locale.ROOT
                        );
        String bukkitStyle =
                path.toUpperCase(
                                Locale.ROOT
                        )
                        .replace('.', '_')
                        .replace('/', '_');

        SOUND_CACHE.putIfAbsent(
                fullKey,
                sound
        );
        SOUND_CACHE.putIfAbsent(
                path,
                sound
        );
        SOUND_CACHE.putIfAbsent(
                bukkitStyle,
                sound
        );
    }

    private static boolean isKeyStyle(
            String soundName
    ) {
        return soundName.indexOf(':') >= 0
                || soundName.indexOf('.') >= 0;
    }

    private static void warnInvalidOnce(
            Core core,
            String path,
            String soundName
    ) {
        String warningKey =
                path + "=" + soundName;

        if (INVALID_SOUNDS.add(warningKey)) {
            core.getLogger().warning(
                    "Invalid sound configured at "
                            + path
                            + ".sound: "
                            + soundName
            );
        }
    }

    private static float nonNegative(
            double value
    ) {
        if (!Double.isFinite(value)
                || value < 0.0D) {
            return 0.0F;
        }

        return (float) value;
    }

    /*
     * Compatibility API.
     *
     * Existing modules keep using these descriptive calls. They all delegate
     * to the same central semantic feedback palette above.
     */

    public static void guiClick(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void guiBack(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void guiPage(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void guiSort(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void guiFilter(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void guiSearch(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void guiRefresh(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void guiSelect(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void guiConfirm(
            Player player,
            Core core
    ) {
        success(player, core);
    }

    public static void guiCancel(
            Player player,
            Core core
    ) {
        negative(player, core);
    }

    public static void guiDelete(
            Player player,
            Core core
    ) {
        negative(player, core);
    }

    public static void guiError(
            Player player,
            Core core
    ) {
        negative(player, core);
    }

    public static void mineaclePlus(
            Player player,
            Core core
    ) {
        negative(player, core);
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
        tick(player, core);
    }

    public static void teleportCancelled(
            Player player,
            Core core
    ) {
        negative(player, core);
    }

    public static void teleportComplete(
            Player player,
            Core core
    ) {
        success(player, core);
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

    public static void homeSet(
            Player player,
            Core core
    ) {
        success(player, core);
    }

    public static void homeDelete(
            Player player,
            Core core
    ) {
        negative(player, core);
    }

    public static void teamInvite(
            Player player,
            Core core
    ) {
        success(player, core);
    }

    public static void teamCreate(
            Player player,
            Core core
    ) {
        success(player, core);
    }

    public static void teamDisband(
            Player player,
            Core core
    ) {
        negative(player, core);
    }

    public static void economyPay(
            Player player,
            Core core
    ) {
        success(player, core);
    }

    public static void economyReceive(
            Player player,
            Core core
    ) {
        success(player, core);
    }

    public static void economyBalance(
            Player player,
            Core core
    ) {
        navigate(player, core);
    }

    public static void chatMessage(
            Player player,
            Core core
    ) {
        notice(player, core);
    }

    public static void doubleJump(
            Player player,
            Core core
    ) {
        jump(player, core);
    }

    public static void doubleJumpCooldown(
            Player player,
            Core core
    ) {
        play(
                player,
                core,
                "double-jump.cooldown"
        );
    }

    public static void featureEnable(
            Player player,
            Core core
    ) {
        success(player, core);
    }

    public static void featureDisable(
            Player player,
            Core core
    ) {
        negative(player, core);
    }

    private enum SoundIntent {
        SILENT(
                "feedback.silent",
                0,
                false,
                "",
                0.0D,
                1.0D,
                0L
        ),
        NAVIGATE(
                "feedback.navigate",
                20,
                true,
                "UI_BUTTON_CLICK",
                0.35D,
                1.0D,
                -1L
        ),
        JUMP(
                "feedback.jump",
                60,
                true,
                "BLOCK_WOODEN_BUTTON_CLICK_ON",
                0.80D,
                1.0D,
                80L
        ),
        TICK(
                "feedback.tick",
                70,
                true,
                "BLOCK_NOTE_BLOCK_HAT",
                0.80D,
                1.20D,
                700L
        ),
        NOTICE(
                "feedback.notice",
                80,
                true,
                "UI_BUTTON_CLICK",
                0.30D,
                1.05D,
                120L
        ),
        SUCCESS(
                "feedback.success",
                90,
                true,
                "ENTITY_EXPERIENCE_ORB_PICKUP",
                0.45D,
                1.0D,
                -1L
        ),
        NEGATIVE(
                "feedback.negative",
                100,
                true,
                "BLOCK_NOTE_BLOCK_BASS",
                0.45D,
                0.80D,
                100L
        );

        private final String settingsPath;
        private final int priority;
        private final boolean defaultEnabled;
        private final String defaultSound;
        private final double defaultVolume;
        private final double defaultPitch;
        private final long defaultMinimumIntervalMillis;

        SoundIntent(
                String settingsPath,
                int priority,
                boolean defaultEnabled,
                String defaultSound,
                double defaultVolume,
                double defaultPitch,
                long defaultMinimumIntervalMillis
        ) {
            this.settingsPath = settingsPath;
            this.priority = priority;
            this.defaultEnabled = defaultEnabled;
            this.defaultSound = defaultSound;
            this.defaultVolume = defaultVolume;
            this.defaultPitch = defaultPitch;
            this.defaultMinimumIntervalMillis =
                    defaultMinimumIntervalMillis;
        }

        private String settingsPath() {
            return settingsPath;
        }

        private int priority() {
            return priority;
        }

        private boolean defaultEnabled() {
            return defaultEnabled;
        }

        private String defaultSound() {
            return defaultSound;
        }

        private double defaultVolume() {
            return defaultVolume;
        }

        private double defaultPitch() {
            return defaultPitch;
        }

        private long defaultMinimumIntervalMillis() {
            return defaultMinimumIntervalMillis;
        }
    }

    private record PendingPlayback(
            SoundIntent intent,
            BukkitTask task
    ) {
    }
}
