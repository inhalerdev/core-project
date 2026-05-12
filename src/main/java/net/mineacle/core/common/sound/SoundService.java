package net.mineacle.core.common.sound;

import net.mineacle.core.Core;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class SoundService {

    private SoundService() {
    }

    public static void play(Player player, Core core, String path) {
        if (player == null || core == null || path == null || path.isBlank()) {
            return;
        }

        if (!core.getConfig().getBoolean("sounds.enabled", true)) {
            return;
        }

        String basePath = "sounds." + path;

        if (!core.getConfig().getBoolean(basePath + ".enabled", true)) {
            return;
        }

        String soundName = core.getConfig().getString(basePath + ".sound", "");

        if (soundName == null || soundName.isBlank()) {
            return;
        }

        Sound sound;

        try {
            sound = Sound.valueOf(soundName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            core.getLogger().warning("Invalid sound configured at " + basePath + ".sound: " + soundName);
            return;
        }

        float volume = (float) core.getConfig().getDouble(basePath + ".volume", 0.6D);
        float pitch = (float) core.getConfig().getDouble(basePath + ".pitch", 1.0D);

        try {
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (Exception ignored) {
        }
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

    public static void teleportCancelled(Player player, Core core) {
        play(player, core, "teleport.cancelled");
    }

    public static void teleportComplete(Player player, Core core) {
        play(player, core, "teleport.complete");
    }

    public static void teleportRequest(Player player, Core core) {
        play(player, core, "teleport.request");
    }

    public static void teleportReceived(Player player, Core core) {
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

    public static void economyReceive(Player player, Core core) {
        play(player, core, "economy.receive");
    }

    public static void economyBalance(Player player, Core core) {
        play(player, core, "economy.balance");
    }

    public static void chatMessage(Player player, Core core) {
        play(player, core, "chat.message");
    }

    public static void doubleJump(Player player, Core core) {
        play(player, core, "double-jump.jump");
    }

    public static void doubleJumpCooldown(Player player, Core core) {
        play(player, core, "double-jump.cooldown");
    }

    public static void featureEnable(Player player, Core core) {
        play(player, core, "feature.enable");
    }

    public static void featureDisable(Player player, Core core) {
        play(player, core, "feature.disable");
    }
}