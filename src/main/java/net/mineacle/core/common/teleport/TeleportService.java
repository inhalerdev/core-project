package net.mineacle.core.common.teleport;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The single Mineacle player-teleport state machine.
 * All delayed teleports share one once-per-second ticker. Feature code may
 * prepare destinations independently (RTP does), but countdown ownership,
 * overlap prevention, movement cancellation, final Paper teleport execution,
 * result checking, sounds and user-facing status all live here.
 * Bukkit/Paper entity and Location access remains main-thread only.
 */
public final class TeleportService {

    public enum TeleportKind {
        HOME,
        TEAM_HOME,
        TPA,
        SPAWN,
        WARP,
        RTP,
        SAFETY
    }

    public enum FailureReason {
        DESTINATION_UNAVAILABLE,
        TELEPORT_REJECTED,
        EXCEPTION,
        CANCELLED,
        CANCELLED_MOVE
    }

    public static final String SUCCESS = "&a";
    public static final String ERROR = "&c";
    public static final String PRIMARY = "&#8436FE";
    public static final String SECONDARY = "&#B078FF";
    public static final String ACCENT = "&#D0AFFF";
    public static final String BODY = "&#bbbbbb";

    private static final String CANCELLED_MOVE =
            ERROR + "Teleport cancelled — you moved";
    private static final String ALREADY_ACTIVE =
            ERROR + "You already have a teleport in progress";
    private static final String FAILED =
            ERROR + "Teleport failed";

    private final Core core;
    private final Map<UUID, PendingTeleport> pending = new HashMap<>();
    private final Map<UUID, TeleportKind> reservations = new HashMap<>();
    private BukkitTask tickerTask;

    public TeleportService(Core core) {
        this.core = core;
    }

    public void start() {
        if (tickerTask != null) {
            return;
        }

        tickerTask = core.getServer().getScheduler().runTaskTimer(
                core,
                this::tickAll,
                20L,
                20L
        );
    }

    public boolean isActive(Player player) {
        return player != null && isActive(player.getUniqueId());
    }

    public boolean isActive(UUID playerId) {
        return playerId != null
                && (pending.containsKey(playerId)
                || reservations.containsKey(playerId));
    }

    public void beginLocation(
            Player player,
            String displayTarget,
            Location target,
            TeleportKind kind
    ) {
        beginLocation(
                player,
                displayTarget,
                target,
                kind,
                delaySeconds(player, kind),
                cancelOnMove(kind)
        );
    }

    public void beginLocation(
            Player player,
            String displayTarget,
            Location target,
            TeleportKind kind,
            int delaySeconds,
            boolean cancelOnMove
    ) {
        if (target == null || target.getWorld() == null) {
            failImmediate(player, displayTarget);
            return;
        }

        Location fixedTarget = target.clone();

        beginInternal(
                player,
                displayTarget,
                kind,
                Math.max(0, delaySeconds),
                cancelOnMove,
                fixedTarget::clone,
                Callbacks.NONE
        );
    }

    public boolean beginPlayer(
            Player traveler,
            Player destination
    ) {
        if (traveler == null
                || destination == null
                || !destination.isOnline()) {
            failImmediate(
                    traveler,
                    destination == null
                            ? "player"
                            : destination.getName()
            );
            return false;
        }

        return beginInternal(
                traveler,
                DisplayNames.displayName(destination),
                TeleportKind.TPA,
                delaySeconds(traveler, TeleportKind.TPA),
                cancelOnMove(TeleportKind.TPA),
                () -> destination.isOnline()
                        ? destination.getLocation().clone()
                        : null,
                Callbacks.NONE
        );
    }

    /**
     * Reserves the player's single teleport slot while an external system
     * prepares a destination. RTP uses this during its asynchronous search.
     */
    public boolean reserve(Player player, TeleportKind kind) {
        if (player == null || !player.isOnline() || kind == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();

        if (isActive(playerId)) {
            return false;
        }

        reservations.put(playerId, kind);
        return true;
    }

    public void releaseReservation(UUID playerId, TeleportKind kind) {
        if (playerId != null && kind != null) {
            reservations.remove(playerId, kind);
        }
    }

    public boolean beginReservedLocation(
            Player player,
            String displayTarget,
            Supplier<Location> destinationSupplier,
            TeleportKind kind,
            int delaySeconds,
            boolean cancelOnMove,
            Runnable onSuccess,
            Consumer<FailureReason> onFailure
    ) {
        if (player == null || destinationSupplier == null || kind == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();

        if (reservations.get(playerId) != kind) {
            return false;
        }

        reservations.remove(playerId);

        return beginInternal(
                player,
                displayTarget,
                kind,
                Math.max(0, delaySeconds),
                cancelOnMove,
                destinationSupplier,
                new Callbacks(onSuccess, onFailure)
        );
    }

    /** Emergency teleport. Replaces any convenience teleport and has no delay. */
    public boolean forceLocation(
            Player player,
            String displayTarget,
            Location target
    ) {
        if (player == null) {
            return false;
        }

        cancel(player.getUniqueId(), false);

        if (target == null || target.getWorld() == null) {
            failImmediate(player, displayTarget);
            return false;
        }

        return performTeleport(
                player,
                safeDisplayTarget(displayTarget),
                TeleportKind.SAFETY,
                target.clone(),
                Callbacks.NONE
        );
    }

    public void cancel(UUID playerId) {
        cancel(playerId, false);
    }

    public void cancel(UUID playerId, boolean notify) {
        if (playerId == null) {
            return;
        }

        reservations.remove(playerId);
        PendingTeleport teleport = pending.remove(playerId);

        if (teleport == null) {
            return;
        }

        if (notify) {
            Player player = core.getServer().getPlayer(playerId);

            if (player != null && player.isOnline()) {
                notifyMovementCancellation(player, teleport);
                return;
            }

            teleport.callbacks().failure(FailureReason.CANCELLED_MOVE);
            return;
        }

        teleport.callbacks().failure(FailureReason.CANCELLED);
    }

    public void handleMove(Player player, Location to) {
        if (player == null || to == null) {
            return;
        }

        PendingTeleport teleport = pending.get(player.getUniqueId());

        if (teleport == null || !teleport.cancelOnMove()) {
            return;
        }

        if (TeleportMovement.movedTooFar(core, teleport.origin(), to)) {
            cancel(player.getUniqueId(), true);
        }
    }

    public void handleExternalTeleport(Player player, Location to) {
        handleMove(player, to);
    }

    public void shutdown() {
        if (tickerTask != null) {
            tickerTask.cancel();
            tickerTask = null;
        }

        pending.clear();
        reservations.clear();
    }

    private boolean beginInternal(
            Player player,
            String displayTarget,
            TeleportKind kind,
            int delaySeconds,
            boolean cancelOnMove,
            Supplier<Location> destinationSupplier,
            Callbacks callbacks
    ) {
        if (player == null
                || !player.isOnline()
                || kind == null
                || destinationSupplier == null) {
            return false;
        }

        UUID playerId = player.getUniqueId();

        if (isActive(playerId)) {
            sendActionBar(player, ALREADY_ACTIVE);
            SoundService.guiError(player, core);
            return false;
        }

        String safeTarget = safeDisplayTarget(displayTarget);
        Callbacks safeCallbacks = callbacks == null
                ? Callbacks.NONE
                : callbacks;

        if (delaySeconds <= 0) {
            Location destination = resolveDestination(destinationSupplier);

            if (destination == null) {
                fail(
                        player,
                        safeTarget,
                        kind,
                        FailureReason.DESTINATION_UNAVAILABLE,
                        safeCallbacks
                );
                return false;
            }

            return performTeleport(
                    player,
                    safeTarget,
                    kind,
                    destination,
                    safeCallbacks
            );
        }

        PendingTeleport teleport = new PendingTeleport(
                player.getLocation().clone(),
                safeTarget,
                kind,
                delaySeconds,
                cancelOnMove,
                destinationSupplier,
                safeCallbacks
        );
        pending.put(playerId, teleport);

        sendCountdown(player, teleport);
        SoundService.teleportCountdown(player, core);
        return true;
    }

    /** Single shared once-per-second countdown pass for every teleport kind. */
    private void tickAll() {
        if (pending.isEmpty()) {
            return;
        }

        // Snapshot IDs so callbacks may safely start/reserve another teleport.
        List<UUID> playerIds = new ArrayList<>(pending.keySet());

        for (UUID playerId : playerIds) {
            PendingTeleport teleport = pending.get(playerId);

            if (teleport == null) {
                continue;
            }

            Player player = core.getServer().getPlayer(playerId);

            if (player == null || !player.isOnline()) {
                pending.remove(playerId, teleport);
                continue;
            }

            if (teleport.cancelOnMove()
                    && TeleportMovement.movedTooFar(
                    core,
                    teleport.origin(),
                    player.getLocation()
            )) {
                if (pending.remove(playerId, teleport)) {
                    notifyMovementCancellation(player, teleport);
                }
                continue;
            }

            int remaining = teleport.decrementSeconds();

            if (remaining > 0) {
                sendCountdown(player, teleport);
                SoundService.teleportCountdown(player, core);
                continue;
            }

            if (!pending.remove(playerId, teleport)) {
                continue;
            }

            Location destination = resolveDestination(
                    teleport.destinationSupplier()
            );

            if (destination == null) {
                fail(
                        player,
                        teleport.displayTarget(),
                        teleport.kind(),
                        FailureReason.DESTINATION_UNAVAILABLE,
                        teleport.callbacks()
                );
                continue;
            }

            performTeleport(
                    player,
                    teleport.displayTarget(),
                    teleport.kind(),
                    destination,
                    teleport.callbacks()
            );
        }
    }

    private boolean performTeleport(
            Player player,
            String displayTarget,
            TeleportKind kind,
            Location destination,
            Callbacks callbacks
    ) {
        if (player == null
                || !player.isOnline()
                || destination == null
                || destination.getWorld() == null) {
            fail(
                    player,
                    displayTarget,
                    kind,
                    FailureReason.DESTINATION_UNAVAILABLE,
                    callbacks
            );
            return false;
        }

        if (!Bukkit.isPrimaryThread()) {
            core.getLogger().severe(
                    "Rejected off-main-thread teleport for "
                            + player.getUniqueId()
                            + " context=" + kind
            );
            fail(player, displayTarget, kind, FailureReason.EXCEPTION, callbacks);
            return false;
        }

        try {
            boolean teleported = player.teleport(
                    destination,
                    PlayerTeleportEvent.TeleportCause.COMMAND
            );

            if (!teleported) {
                logRejected(player, destination, kind);
                fail(
                        player,
                        displayTarget,
                        kind,
                        FailureReason.TELEPORT_REJECTED,
                        callbacks
                );
                return false;
            }

            sendBoth(
                    player,
                    SUCCESS + "Teleported "
                            + BODY + "to "
                            + targetColor(kind)
                            + safeDisplayTarget(displayTarget)
            );
            SoundService.teleportComplete(player, core);
            callbacks.success();
            return true;
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Teleport execution failed for "
                            + player.getUniqueId()
                            + " context=" + kind,
                    exception
            );
            fail(player, displayTarget, kind, FailureReason.EXCEPTION, callbacks);
            return false;
        }
    }

    private void notifyMovementCancellation(
            Player player,
            PendingTeleport teleport
    ) {
        sendBoth(player, CANCELLED_MOVE);
        SoundService.teleportCancelled(player, core);
        teleport.callbacks().failure(FailureReason.CANCELLED_MOVE);
    }

    private void failImmediate(Player player, String displayTarget) {
        fail(
                player,
                safeDisplayTarget(displayTarget),
                TeleportKind.SAFETY,
                FailureReason.DESTINATION_UNAVAILABLE,
                Callbacks.NONE
        );
    }

    private void fail(
            Player player,
            String displayTarget,
            TeleportKind kind,
            FailureReason reason,
            Callbacks callbacks
    ) {
        if (player != null && player.isOnline()) {
            String message = reason == FailureReason.DESTINATION_UNAVAILABLE
                    ? ERROR + "Teleport failed — destination unavailable"
                    : FAILED;
            sendBoth(player, message);
            SoundService.guiError(player, core);
        }

        if (callbacks != null) {
            callbacks.failure(reason);
        }

        if (player != null) {
            core.getLogger().fine(
                    "Teleport failure player=" + player.getUniqueId()
                            + " context=" + kind
                            + " target=" + safeDisplayTarget(displayTarget)
                            + " reason=" + reason
            );
        }
    }

    private void sendCountdown(Player player, PendingTeleport teleport) {
        sendActionBar(
                player,
                BODY + "Teleporting to "
                        + targetColor(teleport.kind())
                        + teleport.displayTarget() + " "
                        + BODY + "in "
                        + SECONDARY
                        + teleport.secondsRemaining() + "s"
        );
    }

    private String targetColor(TeleportKind kind) {
        return kind == TeleportKind.TPA ? SECONDARY : PRIMARY;
    }

    private void sendBoth(Player player, String message) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(actionBar(colored));
    }

    private void sendActionBar(Player player, String message) {
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }

    private Location resolveDestination(Supplier<Location> supplier) {
        try {
            Location location = supplier.get();
            return location == null || location.getWorld() == null
                    ? null
                    : location.clone();
        } catch (RuntimeException exception) {
            core.getLogger().log(
                    Level.WARNING,
                    "Teleport destination supplier failed",
                    exception
            );
            return null;
        }
    }

    private String safeDisplayTarget(String value) {
        if (value == null || value.isBlank()) {
            return "destination";
        }

        String stripped = TextColor.strip(TextColor.color(value));
        return stripped.isBlank()
                ? "destination"
                : stripped;
    }

    private int delaySeconds(Player player, TeleportKind kind) {
        return switch (kind) {
            case TPA -> configuredDelay(
                    player,
                    "tpa.teleport-delay-seconds",
                    "tpa.plus-teleport-delay-seconds"
            );
            case TEAM_HOME -> configuredDelay(
                    player,
                    "homes.team-home.teleport-delay-seconds",
                    "homes.team-home.plus-teleport-delay-seconds"
            );
            case HOME -> configuredDelay(
                    player,
                    "homes.teleport.delay-seconds",
                    "homes.teleport.plus-delay-seconds"
            );
            case SPAWN, WARP, RTP, SAFETY -> 0;
        };
    }

    private int configuredDelay(
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
        String plusPermission = core.getConfig().getString(
                "teleport-perks.plus-permission",
                "mineacle.plus"
        );

        return player != null
                && !plusPermission.isBlank()
                && player.hasPermission(plusPermission)
                ? plusDelay
                : defaultDelay;
    }

    private boolean cancelOnMove(TeleportKind kind) {
        return switch (kind) {
            case TPA -> core.getConfig().getBoolean("tpa.cancel-on-move", true);
            case TEAM_HOME -> core.getConfig().getBoolean(
                    "homes.team-home.cancel-on-move",
                    true
            );
            case HOME -> core.getConfig().getBoolean(
                    "homes.teleport.cancel-on-move",
                    true
            );
            case SPAWN, WARP, RTP -> true;
            case SAFETY -> false;
        };
    }

    private void logRejected(
            Player player,
            Location destination,
            TeleportKind kind
    ) {
        core.getLogger().warning(
                "Paper rejected Mineacle teleport player="
                        + player.getUniqueId()
                        + " context=" + kind
                        + " from=" + locationSummary(player.getLocation())
                        + " to=" + locationSummary(destination)
        );
    }

    private String locationSummary(Location location) {
        if (location == null || location.getWorld() == null) {
            return "unknown";
        }

        return location.getWorld().getName()
                + ":" + location.getBlockX()
                + "," + location.getBlockY()
                + "," + location.getBlockZ();
    }

    private static final class PendingTeleport {
        private final Location origin;
        private final String displayTarget;
        private final TeleportKind kind;
        private final boolean cancelOnMove;
        private final Supplier<Location> destinationSupplier;
        private final Callbacks callbacks;
        private int secondsRemaining;

        private PendingTeleport(
                Location origin,
                String displayTarget,
                TeleportKind kind,
                int secondsRemaining,
                boolean cancelOnMove,
                Supplier<Location> destinationSupplier,
                Callbacks callbacks
        ) {
            this.origin = origin;
            this.displayTarget = displayTarget;
            this.kind = kind;
            this.secondsRemaining = secondsRemaining;
            this.cancelOnMove = cancelOnMove;
            this.destinationSupplier = destinationSupplier;
            this.callbacks = callbacks;
        }

        private Location origin() { return origin; }
        private String displayTarget() { return displayTarget; }
        private TeleportKind kind() { return kind; }
        private boolean cancelOnMove() { return cancelOnMove; }
        private Supplier<Location> destinationSupplier() { return destinationSupplier; }
        private Callbacks callbacks() { return callbacks; }
        private int secondsRemaining() { return secondsRemaining; }
        private int decrementSeconds() { return --secondsRemaining; }
    }

    private record Callbacks(
            Runnable onSuccess,
            Consumer<FailureReason> onFailure
    ) {
        private static final Callbacks NONE = new Callbacks(null, null);

        private void success() {
            if (onSuccess != null) {
                onSuccess.run();
            }
        }

        private void failure(FailureReason reason) {
            if (onFailure != null) {
                onFailure.accept(reason);
            }
        }
    }
}
