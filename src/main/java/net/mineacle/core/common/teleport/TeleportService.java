package net.mineacle.core.common.teleport;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * The single Mineacle player-teleport state machine.
 * All delayed teleports share one lightweight ticker while each pending
 * teleport owns its own one-second countdown deadline. Feature code may
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

    public static final String ERROR = "&c";
    public static final String SECONDARY = "&#B078FF";
    public static final String ACCENT = "&#D0AFFF";
    public static final String BODY = "&#bbbbbb";

    private static final String CANCELLED_MOVE =
            ERROR + "Teleport cancelled — you moved";
    private static final String ALREADY_ACTIVE =
            ERROR + "You already have a teleport in progress";
    private static final String FAILED =
            ERROR + "Teleport failed";

    private static final long COUNTDOWN_STEP_NANOS =
            1_000_000_000L;
    private static final long TICKER_PERIOD_TICKS =
            1L;

    private final Core core;
    private final Map<UUID, PendingTeleport> pending = new HashMap<>();
    private final Map<UUID, TeleportKind> reservations = new HashMap<>();
    private final Map<UUID, InFlightTeleport> inFlight = new HashMap<>();
    private final TeleportAttachmentGuard attachmentGuard;
    private BukkitTask tickerTask;

    public TeleportService(Core core) {
        this.core = core;
        this.attachmentGuard = new TeleportAttachmentGuard(core);
    }

    public void start() {
        if (tickerTask != null) {
            return;
        }

        tickerTask = core.getServer().getScheduler().runTaskTimer(
                core,
                this::tickAll,
                TICKER_PERIOD_TICKS,
                TICKER_PERIOD_TICKS
        );
    }

    public boolean isActive(Player player) {
        return player != null && isActive(player.getUniqueId());
    }

    public boolean isActive(UUID playerId) {
        return playerId != null
                && (pending.containsKey(playerId)
                || reservations.containsKey(playerId)
                || inFlight.containsKey(playerId));
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
                normalizeExplicitDelay(
                        player,
                        kind,
                        delaySeconds
                ),
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
        inFlight.clear();
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

    /**
     * One shared lightweight countdown pass for every teleport kind.
     *
     * <p>The scheduler frequency is intentionally independent from the
     * countdown frequency. Each PendingTeleport advances only after its own
     * one-second deadline, so a teleport can never inherit the phase of a
     * global once-per-second timer. This guarantees a full audible/displayed
     * second for every number.</p>
     */
    private void tickAll() {
        if (pending.isEmpty()) {
            return;
        }

        long now = System.nanoTime();

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

            if (!teleport.countdownDue(now)) {
                continue;
            }

            int remaining = teleport.decrementSeconds();

            if (remaining > 0) {
                teleport.scheduleNextCountdown(now);
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
            fail(
                    player,
                    displayTarget,
                    kind,
                    FailureReason.EXCEPTION,
                    callbacks
            );
            return false;
        }

        UUID playerId = player.getUniqueId();
        InFlightTeleport execution = new InFlightTeleport(
                safeDisplayTarget(displayTarget),
                kind,
                destination.clone(),
                callbacks == null ? Callbacks.NONE : callbacks
        );

        if (inFlight.putIfAbsent(playerId, execution) != null) {
            sendActionBar(player, ALREADY_ACTIVE);
            SoundService.guiError(player, core);
            return false;
        }

        /*
         * Do not use teleportAsync directly here.
         *
         * Mineacle's nametag is a TextDisplay passenger. Paper 1.21.10+
         * retains passengers by default and rejects cross-world Player
         * teleports while passengers are mounted. We therefore prepare the
         * destination chunk asynchronously first, then detach attachments for
         * only the short synchronous execution window.
         */
        try {
            CompletableFuture<Chunk> chunkFuture =
                    execution.destination()
                            .getWorld()
                            .getChunkAtAsync(
                                    execution.destination(),
                                    true
                            );

            chunkFuture.whenComplete(
                    (chunk, throwable) ->
                            executePreparedTeleport(
                                    playerId,
                                    execution,
                                    throwable
                            )
            );
            return true;
        } catch (RuntimeException exception) {
            inFlight.remove(playerId, execution);
            core.getLogger().log(
                    Level.WARNING,
                    "Teleport chunk preparation could not start for "
                            + playerId
                            + " context=" + kind,
                    exception
            );
            fail(
                    player,
                    execution.displayTarget(),
                    kind,
                    FailureReason.EXCEPTION,
                    execution.callbacks()
            );
            return false;
        }
    }

    private void executePreparedTeleport(
            UUID playerId,
            InFlightTeleport execution,
            Throwable preparationFailure
    ) {
        Runnable task = () -> {
            if (inFlight.get(playerId) != execution) {
                return;
            }

            Player player = core.getServer().getPlayer(playerId);

            if (preparationFailure != null) {
                inFlight.remove(playerId, execution);
                core.getLogger().log(
                        Level.WARNING,
                        "Teleport chunk preparation failed for "
                                + playerId
                                + " context=" + execution.kind(),
                        preparationFailure
                );
                fail(
                        player,
                        execution.displayTarget(),
                        execution.kind(),
                        FailureReason.EXCEPTION,
                        execution.callbacks()
                );
                return;
            }

            if (player == null || !player.isOnline()) {
                inFlight.remove(playerId, execution);
                execution.callbacks().failure(
                        FailureReason.CANCELLED
                );
                return;
            }

            TeleportAttachmentGuard.Snapshot attachments =
                    attachmentGuard.suspendFor(
                            player,
                            execution.destination()
                    );

            boolean teleported;

            try {
                teleported = player.teleport(
                        execution.destination(),
                        PlayerTeleportEvent.TeleportCause.PLUGIN
                );
            } catch (RuntimeException exception) {
                attachmentGuard.restoreAfterFailure(
                        player,
                        attachments
                );
                inFlight.remove(playerId, execution);
                core.getLogger().log(
                        Level.WARNING,
                        "Teleport execution failed for "
                                + playerId
                                + " context=" + execution.kind(),
                        exception
                );
                fail(
                        player,
                        execution.displayTarget(),
                        execution.kind(),
                        FailureReason.EXCEPTION,
                        execution.callbacks()
                );
                return;
            }

            inFlight.remove(playerId, execution);

            if (!teleported) {
                attachmentGuard.restoreAfterFailure(
                        player,
                        attachments
                );
                logRejected(
                        player,
                        execution.destination(),
                        execution.kind(),
                        attachments
                );
                fail(
                        player,
                        execution.displayTarget(),
                        execution.kind(),
                        FailureReason.TELEPORT_REJECTED,
                        execution.callbacks()
                );
                return;
            }

            attachmentGuard.completeSuccess(
                    playerId,
                    attachments
            );

            if (!player.isOnline()) {
                execution.callbacks().failure(
                        FailureReason.CANCELLED
                );
                return;
            }

            sendBoth(
                    player,
                    BODY + "Teleported to "
                            + SECONDARY
                            + execution.displayTarget()
            );
            SoundService.teleportComplete(player, core);
            execution.callbacks().success();
        };

        if (!core.isEnabled()) {
            inFlight.remove(playerId, execution);
            return;
        }

        if (Bukkit.isPrimaryThread()) {
            task.run();
        } else {
            core.getServer()
                    .getScheduler()
                    .runTask(core, task);
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
                        + SECONDARY
                        + teleport.displayTarget() + " "
                        + BODY + "in "
                        + ACCENT
                        + teleport.secondsRemaining() + "s"
        );
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

    private int normalizeExplicitDelay(
            Player player,
            TeleportKind kind,
            int requestedDelay
    ) {
        int safeDelay = Math.max(
                0,
                requestedDelay
        );

        if (kind != TeleportKind.WARP
                || safeDelay > 0) {
            return safeDelay;
        }

        return standardDelay(player);
    }

    private int standardDelay(Player player) {
        int defaultDelay = Math.max(
                0,
                core.getConfig().getInt(
                        "teleport-perks.default-delay-seconds",
                        5
                )
        );
        int plusDelay = Math.max(
                0,
                core.getConfig().getInt(
                        "teleport-perks.plus-delay-seconds",
                        3
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
            TeleportKind kind,
            TeleportAttachmentGuard.Snapshot attachments
    ) {
        core.getLogger().warning(
                "Paper rejected Mineacle teleport player="
                        + player.getUniqueId()
                        + " context=" + kind
                        + " cause=PLUGIN"
                        + " from=" + locationSummary(player.getLocation())
                        + " to=" + locationSummary(destination)
                        + " detachedPassengers="
                        + attachments.detachedPassengerCount()
                        + " mineacleNametags="
                        + attachments.mineacleNametags()
                        + " vehicleDetached="
                        + (attachments.vehicle() != null)
                        + " remainingPassengers="
                        + player.getPassengers().size()
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

    private record InFlightTeleport(
            String displayTarget,
            TeleportKind kind,
            Location destination,
            Callbacks callbacks
    ) {
    }

    private static final class PendingTeleport {
        private final Location origin;
        private final String displayTarget;
        private final TeleportKind kind;
        private final boolean cancelOnMove;
        private final Supplier<Location> destinationSupplier;
        private final Callbacks callbacks;
        private int secondsRemaining;
        private long nextCountdownAtNanos;

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
            this.nextCountdownAtNanos =
                    System.nanoTime()
                            + COUNTDOWN_STEP_NANOS;
        }

        private Location origin() { return origin; }
        private String displayTarget() { return displayTarget; }
        private TeleportKind kind() { return kind; }
        private boolean cancelOnMove() { return cancelOnMove; }
        private Supplier<Location> destinationSupplier() { return destinationSupplier; }
        private Callbacks callbacks() { return callbacks; }
        private int secondsRemaining() { return secondsRemaining; }
        private int decrementSeconds() { return --secondsRemaining; }

        private boolean countdownDue(long now) {
            return now >= nextCountdownAtNanos;
        }

        private void scheduleNextCountdown(long now) {
            nextCountdownAtNanos =
                    now + COUNTDOWN_STEP_NANOS;
        }
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
