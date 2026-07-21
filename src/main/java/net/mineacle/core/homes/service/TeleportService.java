package net.mineacle.core.homes.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportMovement;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TeleportService {

    private static final String CANCELLED_MOVE_MESSAGE = "&cTeleport cancelled — you moved";
    private static final String COUNTDOWN_MESSAGE =
            "&#bbbbbbTeleporting to &d%target% &#bbbbbbin &d%seconds%s";

    private final Core core;
    private final Map<UUID, Location> teleportOrigins = new HashMap<>();
    private final Map<UUID, String> teleportContexts = new HashMap<>();
    private final Set<UUID> teleporting = new HashSet<>();

    public TeleportService(Core core) {
        this.core = core;
    }

    public boolean isTeleporting(UUID uuid) {
        return teleporting.contains(uuid);
    }

    public void cancel(UUID uuid) {
        teleporting.remove(uuid);
        teleportOrigins.remove(uuid);
        teleportContexts.remove(uuid);
    }

    public void begin(Player player, String targetName, Runnable action) {
        beginInternal(player, targetName, targetName, false, action);
    }

    public void beginTpa(Player player, String destinationPlayerName, Runnable action) {
        beginInternal(player, destinationPlayerName, "TPA", true, action);
    }

    private void beginInternal(
            Player player,
            String displayedTarget,
            String teleportContext,
            boolean tickInitialNumber,
            Runnable action
    ) {
        UUID uuid = player.getUniqueId();

        if (teleporting.contains(uuid)) {
            return;
        }

        String safeDisplayedTarget = displayedTarget == null || displayedTarget.isBlank()
                ? "destination"
                : displayedTarget;

        String safeContext = teleportContext == null ? "" : teleportContext;
        int delaySeconds = getDelaySeconds(player, safeContext);

        if (delaySeconds <= 0) {
            action.run();
            SoundService.teleportComplete(player, core);
            return;
        }

        teleporting.add(uuid);
        teleportOrigins.put(uuid, player.getLocation().clone());
        teleportContexts.put(uuid, safeContext);

        String startMessage = countdownMessage(
                safeDisplayedTarget,
                safeContext,
                delaySeconds
        );

        player.sendActionBar(actionBar(startMessage));

        if (tickInitialNumber) {
            SoundService.teleportCountdown(player, core);
        } else {
            SoundService.teleportStart(player, core);
        }

        new BukkitRunnable() {
            int countdown = delaySeconds;

            @Override
            public void run() {
                if (!teleporting.contains(uuid)) {
                    cancel();
                    return;
                }

                if (!player.isOnline()) {
                    TeleportService.this.cancel(uuid);
                    cancel();
                    return;
                }

                countdown--;

                if (countdown <= 0) {
                    TeleportService.this.cancel(uuid);
                    action.run();
                    SoundService.teleportComplete(player, core);
                    cancel();
                    return;
                }

                String message = countdownMessage(
                        safeDisplayedTarget,
                        safeContext,
                        countdown
                );

                player.sendActionBar(actionBar(message));
                SoundService.teleportCountdown(player, core);
            }
        }.runTaskTimer(core, 20L, 20L);
    }

    public void handleMove(Player player, Location to) {
        UUID uuid = player.getUniqueId();

        if (!teleporting.contains(uuid)) {
            return;
        }

        String teleportContext = teleportContexts.getOrDefault(uuid, "");

        if (!cancelOnMove(teleportContext)) {
            return;
        }

        Location origin = teleportOrigins.get(uuid);

        if (origin == null || to == null) {
            return;
        }

        if (TeleportMovement.movedTooFar(core, origin, to)) {
            cancel(uuid);
            String message = TextColor.color(CANCELLED_MOVE_MESSAGE);
            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.teleportCancelled(player, core);
        }
    }

    private int getDelaySeconds(Player player, String teleportContext) {
        if (teleportContext != null && teleportContext.equalsIgnoreCase("TPA")) {
            return teleportDelay(player, "tpa.teleport-delay-seconds", "tpa.plus-teleport-delay-seconds");
        }

        if (teleportContext != null && teleportContext.equalsIgnoreCase("Team Home")) {
            return teleportDelay(
                    player,
                    "homes.team-home.teleport-delay-seconds",
                    "homes.team-home.plus-teleport-delay-seconds"
            );
        }

        return teleportDelay(player, "homes.teleport.delay-seconds", "homes.teleport.plus-delay-seconds");
    }

    private int teleportDelay(Player player, String defaultPath, String plusPath) {
        int defaultDelay = Math.max(0, core.getConfig().getInt(
                defaultPath,
                core.getConfig().getInt("teleport-perks.default-delay-seconds", 5)
        ));

        int plusDelay = Math.max(0, core.getConfig().getInt(
                plusPath,
                core.getConfig().getInt("teleport-perks.plus-delay-seconds", 3)
        ));

        String plusPermission = core.getConfig().getString("teleport-perks.plus-permission", "mineacle.plus");

        if (player != null && player.hasPermission(plusPermission)) {
            return plusDelay;
        }

        return defaultDelay;
    }

    private boolean cancelOnMove(String teleportContext) {
        if (teleportContext != null && teleportContext.equalsIgnoreCase("TPA")) {
            return core.getConfig().getBoolean("tpa.cancel-on-move", true);
        }

        if (teleportContext != null && teleportContext.equalsIgnoreCase("Team Home")) {
            return core.getConfig().getBoolean("homes.team-home.cancel-on-move", true);
        }

        return core.getConfig().getBoolean("homes.teleport.cancel-on-move", true);
    }

    private String countdownMessage(
            String displayedTarget,
            String teleportContext,
            int seconds
    ) {
        String path = teleportContext.equalsIgnoreCase("TPA")
                ? "tpa.teleporting"
                : "homes.teleporting";
        String message = core.getMessagesConfig() == null
                ? COUNTDOWN_MESSAGE
                : core.getMessagesConfig().getString(
                        path,
                        COUNTDOWN_MESSAGE
                );

        if (message == null || message.isBlank()) {
            message = COUNTDOWN_MESSAGE;
        }

        return TextColor.color(message)
                .replace("%target%", displayedTarget)
                .replace("%seconds%", String.valueOf(seconds));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
