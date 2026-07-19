package net.mineacle.core.common.sound;

import net.mineacle.core.Core;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings({"deprecation", "removal"})
public final class SoundService {

    private static final Map<String, Sound> SOUND_CACHE =
            new ConcurrentHashMap<>();

    private static final Set<String> INVALID_SOUNDS =
            ConcurrentHashMap.newKeySet();

    private SoundService() {
    }

    public static void play(
            Player player,
            Core core,
            String path
    ) {
        if (player == null
                || core == null
                || path == null
                || path.isBlank()
                || !player.isOnline()
                || !core.getConfig().getBoolean(
                "sounds.enabled",
                true
        )) {
            return;
        }

        String basePath = "sounds." + path;

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

        float volume = nonNegative(
                core.getConfig().getDouble(
                        basePath + ".volume",
                        0.6D
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

            warnInvalidOnce(core, basePath, soundName);
        } catch (IllegalArgumentException exception) {
            warnInvalidOnce(core, basePath, soundName);
        }
    }

    public static void clearCache() {
        SOUND_CACHE.clear();
        INVALID_SOUNDS.clear();
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
        if (!Double.isFinite(value) || value < 0.0D) {
            return 0.0F;
        }

        return (float) value;
    }

    public static void guiClick(Player player, Core core) {
        play(player, core, "gui.click");
    }

    public static void guiBack(Player player, Core core) {
        play(player, core, "gui.back");
    }

    public static void guiConfirm(Player player, Core core) {
        play(player, core, "gui.confirm");
    }

    public static void guiCancel(Player player, Core core) {
        play(player, core, "gui.cancel");
    }

    public static void guiError(Player player, Core core) {
        play(player, core, "gui.error");
    }

    public static void guiUsage(Player player, Core core) {
        play(player, core, "gui.usage");
    }

    public static void mineaclePlus(Player player, Core core) {
        play(player, core, "mineacle-plus.blocked");
    }

    public static void teleportStart(Player player, Core core) {
        play(player, core, "teleport.start");
    }

    public static void teleportCountdown(Player player, Core core) {
        play(player, core, "teleport.countdown");
    }

    public static void teleportTick(Player player, Core core) {
        teleportCountdown(player, core);
    }

    public static void teleportCancelled(
            Player player,
            Core core
    ) {
        play(player, core, "teleport.cancelled");
    }

    public static void teleportCancel(Player player, Core core) {
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

    public static void portalFreeze(Player player, Core core) {
        play(player, core, "portal.freeze");
    }

    public static void spawnOpen(Player player, Core core) {
        play(player, core, "spawn.open");
    }

    public static void spawnArrive(Player player, Core core) {
        play(player, core, "spawn.arrive");
    }

    public static void homeSet(Player player, Core core) {
        play(player, core, "homes.set");
    }

    public static void homeDelete(Player player, Core core) {
        play(player, core, "homes.delete");
    }

    public static void teamInvite(Player player, Core core) {
        play(player, core, "teams.invite");
    }

    public static void teamCreate(Player player, Core core) {
        play(player, core, "teams.create");
    }

    public static void teamDisband(Player player, Core core) {
        play(player, core, "teams.disband");
    }

    public static void economyPay(Player player, Core core) {
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

    public static void chatMessage(Player player, Core core) {
        play(player, core, "chat.message");
    }

    public static void doubleJump(Player player, Core core) {
        play(player, core, "double-jump.jump");
    }

    public static void doubleJumpCooldown(
            Player player,
            Core core
    ) {
        play(player, core, "double-jump.cooldown");
    }

    public static void featureEnable(Player player, Core core) {
        play(player, core, "feature.enable");
    }

    public static void featureDisable(Player player, Core core) {
        play(player, core, "feature.disable");
    }
}
