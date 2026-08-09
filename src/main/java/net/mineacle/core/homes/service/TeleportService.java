package net.mineacle.core.homes.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.teleport.TeleportMovement;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

public final class TeleportService {

    private static final String CANCELLED_MOVE_MESSAGE =
            "&cTeleport cancelled — you moved";
    private static final String ALREADY_PENDING_MESSAGE =
            "&eYou already have a teleport in progress";
    private static final String FAILED_MESSAGE =
            "&cTeleport failed";
    private static final String COUNTDOWN_MESSAGE =
            "&#bbbbbbTeleporting to &#8436FE%target% "
                    + "&#bbbbbbin &#8436FE%seconds%s";

    private final Core core;
    private final Map<UUID, PendingTeleport> pending =
            new HashMap<>();

    public TeleportService(Core core) {
        this.core = core;
    }

    public boolean isTeleporting(UUID uuid) {
        return uuid != null && pending.containsKey(uuid);
    }

    public void cancel(UUID uuid) {
        clear(uuid);
    }

    public void shutdown() {
        for (PendingTeleport teleport :
                new ArrayList<>(pending.values())) {
            teleport.cancelTask();
        }

        pending.clear();
    }

    public void begin(
            Player player,
            String targetName,
            Runnable action
    ) {
        TeleportContext context =
                targetName != null
                        && targetName.equalsIgnoreCase("Team Home")
                        ? TeleportContext.TEAM_HOME
                        : TeleportContext.HOME;

        beginInternal(
                player,
                targetName,
                context,
                false,
                action
        );
    }

    public void beginTeamHome(
            Player player,
            Runnable action
    ) {
        beginInternal(
                player,
                "Team Home",
                TeleportContext.TEAM_HOME,
                false,
                action
        );
    }

    public void beginTpa(
            Player player,
            String destinationPlayerName,
            Runnable action
    ) {
        beginInternal(
                player,
                destinationPlayerName,
                TeleportContext.TPA,
                true,
                action
        );
    }

    private void beginInternal(
            Player player,
            String displayedTarget,
            TeleportContext context,
            boolean tickInitialNumber,
            Runnable action
    ) {
        if (player == null
                || !player.isOnline()
                || action == null) {
            return;
        }

        UUID uuid = player.getUniqueId();

        if (pending.containsKey(uuid)) {
            player.sendActionBar(
                    actionBar(ALREADY_PENDING_MESSAGE)
            );
            return;
        }

        String safeTarget =
                displayedTarget == null
                        || displayedTarget.isBlank()
                        ? "destination"
                        : displayedTarget.trim();
        int delaySeconds =
                getDelaySeconds(player, context);

        if (delaySeconds <= 0) {
            runTeleportAction(player, action);
            return;
        }

        PendingTeleport teleport = new PendingTeleport(
                uuid,
                player.getLocation().clone(),
                safeTarget,
                context,
                delaySeconds,
                action
        );

        pending.put(uuid, teleport);

        player.sendActionBar(
                actionBar(
                        countdownMessage(
                                teleport.displayedTarget,
                                teleport.context,
                                teleport.secondsRemaining
                        )
                )
        );

        if (tickInitialNumber) {
            SoundService.teleportCountdown(
                    player,
                    core
            );
        } else {
            SoundService.teleportStart(
                    player,
                    core
            );
        }

        teleport.task = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        () -> tick(player, teleport),
                        20L,
                        20L
                );
    }

    private void tick(
            Player player,
            PendingTeleport teleport
    ) {
        PendingTeleport current = pending.get(
                teleport.playerId
        );

        if (current != teleport) {
            teleport.cancelTask();
            return;
        }

        if (!player.isOnline()) {
            clear(teleport.playerId);
            return;
        }

        if (cancelOnMove(teleport.context)
                && TeleportMovement.movedTooFar(
                        core,
                        teleport.origin,
                        player.getLocation()
                )) {
            cancelForMovement(player);
            return;
        }

        teleport.secondsRemaining--;

        if (teleport.secondsRemaining <= 0) {
            Runnable action = teleport.action;
            clear(teleport.playerId);
            runTeleportAction(player, action);
            return;
        }

        player.sendActionBar(
                actionBar(
                        countdownMessage(
                                teleport.displayedTarget,
                                teleport.context,
                                teleport.secondsRemaining
                        )
                )
        );
        SoundService.teleportCountdown(player, core);
    }

    public void handleMove(
            Player player,
            Location to
    ) {
        if (player == null || to == null) {
            return;
        }

        PendingTeleport teleport = pending.get(
                player.getUniqueId()
        );

        if (teleport == null
                || !cancelOnMove(teleport.context)) {
            return;
        }

        if (TeleportMovement.movedTooFar(
                core,
                teleport.origin,
                to
        )) {
            cancelForMovement(player);
        }
    }

    private void cancelForMovement(Player player) {
        clear(player.getUniqueId());

        String message = TextColor.color(
                CANCELLED_MOVE_MESSAGE
        );

        player.sendActionBar(actionBar(message));
        player.sendMessage(message);
        SoundService.teleportCancelled(player, core);
    }

    private void clear(UUID uuid) {
        if (uuid == null) {
            return;
        }

        PendingTeleport teleport = pending.remove(uuid);

        if (teleport != null) {
            teleport.cancelTask();
        }
    }

    private int getDelaySeconds(
            Player player,
            TeleportContext context
    ) {
        return switch (context) {
            case TPA -> teleportDelay(
                    player,
                    "tpa.teleport-delay-seconds",
                    "tpa.plus-teleport-delay-seconds"
            );
            case TEAM_HOME -> teleportDelay(
                    player,
                    "homes.team-home.teleport-delay-seconds",
                    "homes.team-home.plus-teleport-delay-seconds"
            );
            case HOME -> teleportDelay(
                    player,
                    "homes.teleport.delay-seconds",
                    "homes.teleport.plus-delay-seconds"
            );
        };
    }

    private int teleportDelay(
            Player player,
            String defaultPath,
            String plusPath
    ) {
        int defaultDelay = Math.max(
                0,
                core.getConfig().getInt(
                        defaultPath,
                        core.getConfig().getInt(
                                "teleport-perks.default-delay-seconds",
                                5
                        )
                )
        );

        int plusDelay = Math.max(
                0,
                core.getConfig().getInt(
                        plusPath,
                        core.getConfig().getInt(
                                "teleport-perks.plus-delay-seconds",
                                3
                        )
                )
        );

        String configuredPermission =
                core.getConfig().getString(
                        "teleport-perks.plus-permission",
                        "mineacle.plus"
                );
        String plusPermission =
                configuredPermission == null
                        || configuredPermission.isBlank()
                        ? "mineacle.plus"
                        : configuredPermission.trim();

        return player.hasPermission(plusPermission)
                ? plusDelay
                : defaultDelay;
    }

    private boolean cancelOnMove(
            TeleportContext context
    ) {
        return switch (context) {
            case TPA -> core.getConfig().getBoolean(
                    "tpa.cancel-on-move",
                    true
            );
            case TEAM_HOME -> core.getConfig().getBoolean(
                    "homes.team-home.cancel-on-move",
                    true
            );
            case HOME -> core.getConfig().getBoolean(
                    "homes.teleport.cancel-on-move",
                    true
            );
        };
    }

    private String countdownMessage(
            String displayedTarget,
            TeleportContext context,
            int seconds
    ) {
        String path = context == TeleportContext.TPA
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
                .replace(
                        "%seconds%",
                        String.valueOf(seconds)
                );
    }

    private void runTeleportAction(
            Player player,
            Runnable action
    ) {
        try {
            action.run();
            SoundService.teleportComplete(player, core);
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Teleport action failed for "
                            + player.getUniqueId(),
                    exception
            );

            String message = TextColor.color(
                    FAILED_MESSAGE
            );
            player.sendActionBar(actionBar(message));
            player.sendMessage(message);
            SoundService.guiError(player, core);
        }
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }

    private enum TeleportContext {
        HOME,
        TEAM_HOME,
        TPA
    }

    private static final class PendingTeleport {

        private final UUID playerId;
        private final Location origin;
        private final String displayedTarget;
        private final TeleportContext context;
        private final Runnable action;

        private int secondsRemaining;
        private BukkitTask task;

        private PendingTeleport(
                UUID playerId,
                Location origin,
                String displayedTarget,
                TeleportContext context,
                int secondsRemaining,
                Runnable action
        ) {
            this.playerId = playerId;
            this.origin = origin;
            this.displayedTarget = displayedTarget;
            this.context = context;
            this.secondsRemaining = secondsRemaining;
            this.action = action;
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }
}
