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
import java.util.function.BooleanSupplier;
import java.util.logging.Level;

public final class TeleportService {

    private static final String CANCELLED_MOVE_MESSAGE =
            "&cTeleport cancelled — you moved";
    private static final String ALREADY_PENDING_MESSAGE =
            "&eYou already have a teleport in progress";
    private static final String FAILED_MESSAGE =
            "&cTeleport failed";

    private static final String HOME_COUNTDOWN_MESSAGE =
            "&#bbbbbbTeleporting to &#8436FE%target% "
                    + "&#bbbbbbin &#B078FF%seconds%s";

    private static final String TPA_COUNTDOWN_MESSAGE =
            "&#bbbbbbTeleporting to &#B078FF%target% "
                    + "&#bbbbbbin &#B078FF%seconds%s";

    private final Core core;
    private final Map<UUID, PendingTeleport> pending =
            new HashMap<>();

    public TeleportService(Core core) {
        this.core = core;
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
                        && targetName.equalsIgnoreCase(
                        "Team Home"
                )
                        ? TeleportContext.TEAM_HOME
                        : TeleportContext.HOME;

        beginInternal(
                player,
                targetName,
                context,
                false,
                () -> {
                    action.run();
                    return true;
                }
        );
    }

    public void beginTpa(
            Player player,
            String destinationPlayerName,
            BooleanSupplier action
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
            BooleanSupplier action
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
                        : displayedTarget;
        int delaySeconds = getDelaySeconds(
                player,
                context
        );

        if (delaySeconds <= 0) {
            runTeleportAction(
                    player,
                    action
            );
            return;
        }

        PendingTeleport teleport =
                new PendingTeleport(
                        player.getLocation().clone(),
                        safeTarget,
                        context,
                        action,
                        delaySeconds
                );
        pending.put(uuid, teleport);

        player.sendActionBar(
                actionBar(
                        countdownMessage(
                                safeTarget,
                                context,
                                delaySeconds
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

        BukkitTask task = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        () -> tick(uuid),
                        20L,
                        20L
                );
        teleport.setTask(task);
    }

    public void handleMove(
            Player player,
            Location to
    ) {
        if (player == null || to == null) {
            return;
        }

        PendingTeleport teleport =
                pending.get(
                        player.getUniqueId()
                );

        if (teleport == null
                || !cancelOnMove(
                teleport.context()
        )) {
            return;
        }

        if (!TeleportMovement.movedTooFar(
                core,
                teleport.origin(),
                to
        )) {
            return;
        }

        clear(player.getUniqueId());

        String message = TextColor.color(
                CANCELLED_MOVE_MESSAGE
        );
        player.sendActionBar(
                actionBar(message)
        );
        player.sendMessage(message);
        SoundService.teleportCancelled(
                player,
                core
        );
    }

    private void tick(UUID uuid) {
        PendingTeleport teleport =
                pending.get(uuid);

        if (teleport == null) {
            return;
        }

        Player player = core.getServer()
                .getPlayer(uuid);

        if (player == null || !player.isOnline()) {
            clear(uuid);
            return;
        }

        if (cancelOnMove(
                teleport.context()
        ) && TeleportMovement.movedTooFar(
                core,
                teleport.origin(),
                player.getLocation()
        )) {
            handleMove(
                    player,
                    player.getLocation()
            );
            return;
        }

        int remaining =
                teleport.decrementSeconds();

        if (remaining <= 0) {
            pending.remove(uuid);
            teleport.cancelTask();
            runTeleportAction(
                    player,
                    teleport.action()
            );
            return;
        }

        player.sendActionBar(
                actionBar(
                        countdownMessage(
                                teleport.displayedTarget(),
                                teleport.context(),
                                remaining
                        )
                )
        );
        SoundService.teleportCountdown(
                player,
                core
        );
    }

    private void runTeleportAction(
            Player player,
            BooleanSupplier action
    ) {
        try {
            if (!action.getAsBoolean()) {
                sendFailure(player);
                return;
            }

            SoundService.teleportComplete(
                    player,
                    core
            );
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Teleport action failed for "
                            + player.getUniqueId(),
                    exception
            );
            sendFailure(player);
        }
    }

    private void sendFailure(Player player) {
        String message = TextColor.color(
                FAILED_MESSAGE
        );
        player.sendActionBar(
                actionBar(message)
        );
        player.sendMessage(message);
        SoundService.guiError(
                player,
                core
        );
    }

    private void clear(UUID uuid) {
        if (uuid == null) {
            return;
        }

        PendingTeleport teleport =
                pending.remove(uuid);

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
        String plusPermission =
                core.getConfig().getString(
                        "teleport-perks.plus-permission",
                        "mineacle.plus"
                );

        if (!plusPermission.isBlank()
                && player.hasPermission(
                plusPermission
        )) {
            return plusDelay;
        }

        return defaultDelay;
    }

    private boolean cancelOnMove(
            TeleportContext context
    ) {
        return switch (context) {
            case TPA -> core.getConfig()
                    .getBoolean(
                            "tpa.cancel-on-move",
                            true
                    );
            case TEAM_HOME -> core.getConfig()
                    .getBoolean(
                            "homes.team-home.cancel-on-move",
                            true
                    );
            case HOME -> core.getConfig()
                    .getBoolean(
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
        String path =
                context == TeleportContext.TPA
                        ? "tpa.teleporting"
                        : "homes.teleporting";

        String fallback =
                context == TeleportContext.TPA
                        ? TPA_COUNTDOWN_MESSAGE
                        : HOME_COUNTDOWN_MESSAGE;

        String message =
                core.getMessagesConfig() == null
                        ? fallback
                        : core.getMessagesConfig()
                        .getString(
                                path,
                                fallback
                        );

        if (message.isBlank()) {
            message = fallback;
        }

        message = normalizeCountdownPalette(
                message,
                context
        );

        return TextColor.color(message)
                .replace(
                        "%target%",
                        displayedTarget
                )
                .replace(
                        "%seconds%",
                        String.valueOf(seconds)
                );
    }

    private String normalizeCountdownPalette(
            String message,
            TeleportContext context
    ) {
        String targetColor =
                context == TeleportContext.TPA
                        ? "&#B078FF"
                        : "&#8436FE";

        return message
                .replace(
                        "&#8436FE%target%",
                        targetColor + "%target%"
                )
                .replace(
                        "&#B078FF%target%",
                        targetColor + "%target%"
                )
                .replace(
                        "&#D0AFFF%target%",
                        targetColor + "%target%"
                )
                .replace(
                        "&#ff55ff%target%",
                        targetColor + "%target%"
                )
                .replace(
                        "&#ff88ff%target%",
                        targetColor + "%target%"
                )
                .replace(
                        "&d%target%",
                        targetColor + "%target%"
                )
                .replace(
                        "&#8436FE%seconds%",
                        "&#B078FF%seconds%"
                )
                .replace(
                        "&#D0AFFF%seconds%",
                        "&#B078FF%seconds%"
                )
                .replace(
                        "&#ff55ff%seconds%",
                        "&#B078FF%seconds%"
                )
                .replace(
                        "&#ff88ff%seconds%",
                        "&#B078FF%seconds%"
                )
                .replace(
                        "&d%seconds%",
                        "&#B078FF%seconds%"
                );
    }

    private Component actionBar(
            String message
    ) {
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

        private final Location origin;
        private final String displayedTarget;
        private final TeleportContext context;
        private final BooleanSupplier action;
        private int secondsRemaining;
        private BukkitTask task;

        private PendingTeleport(
                Location origin,
                String displayedTarget,
                TeleportContext context,
                BooleanSupplier action,
                int secondsRemaining
        ) {
            this.origin = origin;
            this.displayedTarget = displayedTarget;
            this.context = context;
            this.action = action;
            this.secondsRemaining =
                    secondsRemaining;
        }

        private Location origin() {
            return origin;
        }

        private String displayedTarget() {
            return displayedTarget;
        }

        private TeleportContext context() {
            return context;
        }

        private BooleanSupplier action() {
            return action;
        }

        private int decrementSeconds() {
            secondsRemaining--;
            return secondsRemaining;
        }

        private void setTask(
                BukkitTask task
        ) {
            this.task = task;
        }

        private void cancelTask() {
            if (task != null) {
                task.cancel();
                task = null;
            }
        }
    }
}
