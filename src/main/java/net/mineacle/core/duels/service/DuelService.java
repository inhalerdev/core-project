package net.mineacle.core.duels.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.duels.model.DuelInvite;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.logging.Level;

public final class DuelService {

    private static final int MAX_ZONE_ID_LENGTH = 32;

    private final Core core;
    private final File file;

    private final Map<UUID, DuelInvite> incomingByTarget =
            new HashMap<>();
    private final Map<UUID, DuelInvite> outgoingByChallenger =
            new HashMap<>();
    private final Map<UUID, Long> queuedAt =
            new LinkedHashMap<>();
    private final Map<UUID, PendingTeleport> pendingByPlayer =
            new HashMap<>();
    private final Set<PendingTeleport> pendingTeleports =
            new HashSet<>();

    private FileConfiguration config;
    private List<QueueZone> queueZones = List.of();
    private Set<Material> unsafeGround = Set.of();

    private boolean enabled;
    private int minimumPlayers;
    private int maximumPlayers;
    private int queueCountdownSeconds;
    private int defaultTeleportSeconds;
    private int plusTeleportSeconds;
    private String plusPermission;
    private double cancelDistanceSquared;
    private String destinationWorld;
    private double destinationCenterX;
    private double destinationCenterZ;
    private double destinationMinimumRadius;
    private double destinationMaximumRadius;
    private int locationAttempts;
    private double spawnSpacing;
    private int arrivalProtectionTicks;
    private int inviteTimeoutSeconds;

    private int zoneCountdown = -1;

    public DuelService(Core core) {
        this.core = core;
        this.file = new File(core.getDataFolder(), "duels.yml");
        reload();
    }

    public synchronized void reload() {
        config = YamlConfiguration.loadConfiguration(file);
        ensureDefaults();
        migrateLegacyWorld();
        loadSettings();
        save();
        refreshQueueAfterReload();
    }

    public synchronized void shutdown() {
        for (PendingTeleport pending
                : new HashSet<>(pendingTeleports)) {
            cancelPendingTeleport(
                    pending,
                    "&cDuel teleport cancelled"
            );
        }

        queuedAt.clear();
        incomingByTarget.clear();
        outgoingByChallenger.clear();
        pendingByPlayer.clear();
        pendingTeleports.clear();
        zoneCountdown = -1;
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean queued(Player player) {
        return player != null
                && queuedAt.containsKey(player.getUniqueId());
    }

    public boolean pendingTeleport(Player player) {
        return player != null
                && pendingByPlayer.containsKey(
                player.getUniqueId()
        );
    }

    public void challenge(
            Player challenger,
            Player target
    ) {
        if (!enabled) {
            error(
                    challenger,
                    text(
                            "messages.disabled",
                            "&cDuels are disabled"
                    )
            );
            return;
        }

        if (challenger == null || target == null) {
            return;
        }

        UUID challengerId = challenger.getUniqueId();
        UUID targetId = target.getUniqueId();

        if (challengerId.equals(targetId)) {
            error(
                    challenger,
                    text(
                            "messages.self",
                            "&cYou cannot duel yourself"
                    )
            );
            return;
        }

        if (!validPlayer(challenger)
                || !validPlayer(target)) {
            error(
                    challenger,
                    text(
                            "messages.invalid-player",
                            "&cThat player cannot duel right now"
                    )
            );
            return;
        }

        if (pendingTeleport(challenger)
                || pendingTeleport(target)) {
            error(
                    challenger,
                    text(
                            "messages.already-teleporting",
                            "&cThat player is already preparing for a duel"
                    )
            );
            return;
        }

        cleanupInvites();

        if (hasInvite(challengerId)
                || hasInvite(targetId)) {
            error(
                    challenger,
                    text(
                            "messages.invite-busy",
                            "&cOne of you already has a pending duel request"
                    )
            );
            return;
        }

        long expiresAt = System.currentTimeMillis()
                + (inviteTimeoutSeconds * 1000L);
        DuelInvite invite = new DuelInvite(
                challengerId,
                targetId,
                expiresAt
        );

        outgoingByChallenger.put(challengerId, invite);
        incomingByTarget.put(targetId, invite);

        String targetName = DisplayNames.displayName(target);

        challenger.sendMessage(legacy(
                text(
                        "messages.invite-sent",
                        "&#bbbbbbDuel request sent to &#bbbbbb%target%"
                ).replace("%target%", targetName)
        ));
        SoundService.teleportRequest(challenger, core);

        String challengerName =
                DisplayNames.displayName(challenger);
        String mainLine = text(
                "messages.invite-received-header",
                "&#bbbbbb%player% sent you a duel request"
        ).replace("%player%", challengerName);

        target.sendActionBar(legacy(mainLine));
        target.sendMessage(legacy(mainLine));

        Component accept = legacy(text(
                "messages.accept-button",
                "&a[Accept]"
        ))
                .clickEvent(
                        ClickEvent.runCommand("/duel accept")
                )
                .hoverEvent(
                        HoverEvent.showText(
                                legacy(text(
                                        "messages.accept-hover",
                                        "&#bbbbbbAccept duel request"
                                ))
                        )
                );

        Component deny = legacy(text(
                "messages.deny-button",
                "&c[Deny]"
        ))
                .clickEvent(
                        ClickEvent.runCommand("/duel deny")
                )
                .hoverEvent(
                        HoverEvent.showText(
                                legacy(text(
                                        "messages.deny-hover",
                                        "&#bbbbbbDeny duel request"
                                ))
                        )
                );

        target.sendMessage(
                legacy(text(
                        "messages.respond-prefix",
                        "&#bbbbbbRespond "
                ))
                        .append(accept)
                        .append(Component.space())
                        .append(deny)
        );
        SoundService.teleportReceived(target, core);
    }

    public void accept(Player target) {
        DuelInvite invite = incomingByTarget.get(
                target.getUniqueId()
        );

        if (invite == null || invite.expired()) {
            if (invite != null) {
                removeInvite(invite);
            }

            error(
                    target,
                    text(
                            "messages.no-invite",
                            "&cYou do not have a pending duel request"
                    )
            );
            return;
        }

        removeInvite(invite);

        Player challenger = Bukkit.getPlayer(
                invite.challengerId()
        );

        if (challenger == null || !challenger.isOnline()) {
            error(
                    target,
                    text(
                            "messages.inviter-offline",
                            "&cThat player is no longer online"
                    )
            );
            return;
        }

        if (!validPlayer(challenger)
                || !validPlayer(target)
                || pendingTeleport(challenger)
                || pendingTeleport(target)) {
            error(
                    target,
                    text(
                            "messages.invalid-player",
                            "&cThat player cannot duel right now"
                    )
            );
            return;
        }

        target.sendMessage(legacy(text(
                "messages.accepted",
                "&#bbbbbbDuel request accepted"
        )));
        challenger.sendMessage(legacy(
                text(
                        "messages.invite-accepted",
                        "&#bbbbbb%player% accepted your duel request"
                ).replace(
                        "%player%",
                        DisplayNames.displayName(target)
                )
        ));

        SoundService.guiConfirm(target, core);
        SoundService.guiConfirm(challenger, core);

        startTeleportCountdown(
                List.of(challenger, target),
                2
        );
    }

    public void deny(Player target) {
        DuelInvite invite = incomingByTarget.get(
                target.getUniqueId()
        );

        if (invite == null || invite.expired()) {
            if (invite != null) {
                removeInvite(invite);
            }

            error(
                    target,
                    text(
                            "messages.no-invite",
                            "&cYou do not have a pending duel request"
                    )
            );
            return;
        }

        removeInvite(invite);

        Player challenger = Bukkit.getPlayer(
                invite.challengerId()
        );

        if (challenger != null && challenger.isOnline()) {
            challenger.sendMessage(legacy(
                    text(
                            "messages.invite-denied",
                            "&#bbbbbb%player% denied your duel request"
                    ).replace(
                            "%player%",
                            DisplayNames.displayName(target)
                    )
            ));
            SoundService.guiCancel(challenger, core);
        }

        target.sendMessage(legacy(text(
                "messages.denied",
                "&#bbbbbbDuel request denied"
        )));
        SoundService.guiCancel(target, core);
    }

    public void cancel(Player challenger) {
        UUID challengerId = challenger.getUniqueId();
        DuelInvite invite = outgoingByChallenger.get(
                challengerId
        );

        if (invite != null) {
            removeInvite(invite);

            Player target = Bukkit.getPlayer(invite.targetId());

            if (target != null && target.isOnline()) {
                target.sendMessage(legacy(
                        text(
                                "messages.invite-cancelled-target",
                                "&#bbbbbb%player% cancelled the duel request"
                        ).replace(
                                "%player%",
                                DisplayNames.displayName(challenger)
                        )
                ));
                SoundService.guiCancel(target, core);
            }

            challenger.sendMessage(legacy(text(
                    "messages.cancelled",
                    "&#bbbbbbDuel request cancelled"
            )));
            SoundService.guiCancel(challenger, core);
            return;
        }

        PendingTeleport pending = pendingByPlayer.get(
                challengerId
        );

        if (pending != null) {
            cancelPendingTeleport(
                    pending,
                    text(
                            "messages.teleport-cancelled",
                            "&cTeleport cancelled"
                    )
            );
            return;
        }

        error(
                challenger,
                text(
                        "messages.no-outgoing",
                        "&cYou do not have an outgoing duel request"
                )
        );
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        queuedAt.remove(playerId);

        PendingTeleport pending = pendingByPlayer.get(playerId);

        if (pending != null) {
            cancelPendingTeleportWithActor(
                    pending,
                    player,
                    text(
                            "messages.teleport-cancelled-left",
                            "&cDuel cancelled — %player% left"
                    )
            );
        }

        removePlayerInvites(playerId, player, true);

        if (queuedAt.size() < minimumPlayers) {
            zoneCountdown = -1;
        }
    }

    public void handleWorldChange(Player player) {
        if (player == null) {
            return;
        }

        queuedAt.remove(player.getUniqueId());

        PendingTeleport pending = pendingByPlayer.get(
                player.getUniqueId()
        );

        if (pending != null) {
            cancelPendingTeleportMoved(pending, player);
        }

        if (queuedAt.size() < minimumPlayers) {
            zoneCountdown = -1;
        }
    }

    public void removeFromQueue(Player player) {
        if (player == null) {
            return;
        }

        if (queuedAt.remove(player.getUniqueId()) != null) {
            sendActionBar(
                    player,
                    text(
                            "messages.queue-left-actionbar",
                            "&cNo longer in the duels queue"
                    )
            );
        }

        if (queuedAt.size() < minimumPlayers) {
            zoneCountdown = -1;
        }
    }

    public void tick() {
        if (!enabled) {
            if (!queuedAt.isEmpty()) {
                queuedAt.clear();
                zoneCountdown = -1;
            }

            return;
        }

        cleanupInvites();
        updateZoneQueue();
        tickQueue();
        tickPendingTeleports();
    }

    private void tickQueue() {
        List<Player> players = queuedPlayers();

        if (players.size() < minimumPlayers) {
            zoneCountdown = -1;
            broadcastQueueActionBar(players);
            return;
        }

        if (zoneCountdown < 0) {
            zoneCountdown = queueCountdownSeconds;
        }

        broadcastQueueActionBar(players);

        if (zoneCountdown <= 0) {
            List<Player> selected = players
                    .stream()
                    .limit(maximumPlayers)
                    .toList();

            for (Player player : selected) {
                queuedAt.remove(player.getUniqueId());
            }

            startTeleportCountdown(selected, minimumPlayers);

            zoneCountdown = queuedPlayers().size()
                    >= minimumPlayers
                    ? queueCountdownSeconds
                    : -1;
            return;
        }

        zoneCountdown--;
    }

    private void tickPendingTeleports() {
        for (PendingTeleport pending
                : new HashSet<>(pendingTeleports)) {
            if (!pending.active()) {
                clearPendingTeleport(pending);
                continue;
            }

            List<Player> players = pending.onlinePlayers();

            if (players.size() < pending.minimumPlayers()) {
                cancelPendingTeleport(
                        pending,
                        text(
                                "messages.teleport-cancelled",
                                "&cTeleport cancelled"
                        )
                );
                continue;
            }

            Player moved = pending.movedPlayer(
                    cancelDistanceSquared
            );

            if (moved != null) {
                cancelPendingTeleportMoved(pending, moved);
                continue;
            }

            if (pending.searching()) {
                continue;
            }

            int seconds = pending.secondsRemaining();

            if (seconds <= 0) {
                beginLocationSearch(pending);
                continue;
            }

            if (pending.markAnnounced(seconds)) {
                String message = text(
                        "messages.teleport-countdown-actionbar",
                        "&#bbbbbbDuel teleport in &d%seconds%s"
                ).replace(
                        "%seconds%",
                        String.valueOf(seconds)
                );

                for (Player player : players) {
                    sendActionBar(player, message);
                    SoundService.teleportCountdown(player, core);
                }
            }
        }
    }

    private void startTeleportCountdown(
            Collection<Player> rawPlayers,
            int requiredPlayers
    ) {
        List<Player> players = rawPlayers.stream()
                .filter(this::validPlayer)
                .filter(player -> !pendingTeleport(player))
                .distinct()
                .limit(maximumPlayers)
                .toList();

        int minimum = Math.max(2, requiredPlayers);

        if (players.size() < minimum) {
            for (Player player : players) {
                error(
                        player,
                        text(
                                "messages.not-enough-players",
                                "&cNot enough players are ready"
                        )
                );
            }
            return;
        }

        int countdown = teleportCountdown(players);
        PendingTeleport pending = new PendingTeleport(
                players,
                minimum,
                countdown
        );

        pendingTeleports.add(pending);

        for (Player player : players) {
            UUID playerId = player.getUniqueId();

            queuedAt.remove(playerId);
            removePlayerInvites(playerId, player, false);
            pendingByPlayer.put(playerId, pending);

            player.sendMessage(legacy(text(
                    "messages.teleport-started",
                    "&#bbbbbbDuel teleport started — do not move"
            )));
            sendActionBar(
                    player,
                    text(
                            "messages.teleport-countdown-actionbar",
                            "&#bbbbbbDuel teleport in &d%seconds%s"
                    ).replace(
                            "%seconds%",
                            String.valueOf(countdown)
                    )
            );
            SoundService.teleportStart(player, core);
        }
    }

    private int teleportCountdown(List<Player> players) {
        boolean allPlus = players.stream().allMatch(
                player -> player.hasPermission(plusPermission)
                        || player.hasPermission(
                        "mineacleduels.admin"
                )
        );

        return allPlus
                ? plusTeleportSeconds
                : defaultTeleportSeconds;
    }

    private void beginLocationSearch(
            PendingTeleport pending
    ) {
        if (!pending.beginSearching()) {
            return;
        }

        List<Player> players = pending.onlinePlayers();

        for (Player player : players) {
            sendActionBar(
                    player,
                    text(
                            "messages.finding-location-actionbar",
                            "&#bbbbbbFinding a safe duel location"
                    )
            );
        }

        findDuelLocationsAsync(
                pending.playerCount(),
                0,
                locations -> finishLocationSearch(
                        pending,
                        locations
                )
        );
    }

    private void finishLocationSearch(
            PendingTeleport pending,
            List<Location> locations
    ) {
        if (!pending.active()) {
            return;
        }

        List<Player> players = pending.onlinePlayers();

        if (players.size() < pending.minimumPlayers()) {
            cancelPendingTeleport(
                    pending,
                    text(
                            "messages.teleport-cancelled",
                            "&cTeleport cancelled"
                    )
            );
            return;
        }

        Player moved = pending.movedPlayer(
                cancelDistanceSquared
        );

        if (moved != null) {
            cancelPendingTeleportMoved(pending, moved);
            return;
        }

        if (locations == null
                || locations.size() < players.size()) {
            clearPendingTeleport(pending);

            for (Player player : players) {
                error(
                        player,
                        text(
                                "messages.no-arena",
                                "&cCould not find a safe duel location"
                        )
                );
            }
            return;
        }

        clearPendingTeleport(pending);

        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            Location destination = locations.get(index);

            player.closeInventory();
            player.leaveVehicle();
            player.setVelocity(new Vector());
            player.setFallDistance(0.0F);

            boolean teleported = player.teleport(
                    destination,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );

            if (!teleported) {
                error(
                        player,
                        text(
                                "messages.teleport-failed",
                                "&cDuel teleport failed"
                        )
                );
                continue;
            }

            player.setNoDamageTicks(
                    Math.max(
                            player.getNoDamageTicks(),
                            arrivalProtectionTicks
                    )
            );
            sendActionBar(
                    player,
                    text(
                            "messages.teleported-actionbar",
                            "&#bbbbbbDuel started in &dOverworld"
                    )
            );
            SoundService.teleportComplete(player, core);
        }
    }

    private void findDuelLocationsAsync(
            int playerCount,
            int attempt,
            Consumer<List<Location>> callback
    ) {
        if (!core.isEnabled()) {
            callback.accept(null);
            return;
        }

        World world = Bukkit.getWorld(destinationWorld);

        if (world == null || attempt >= locationAttempts) {
            callback.accept(null);
            return;
        }

        Candidate candidate = randomCandidate(world, playerCount);

        if (candidate == null) {
            callback.accept(null);
            return;
        }

        List<CompletableFuture<Chunk>> chunkLoads =
                new ArrayList<>();
        Set<Long> loadedCoordinates = new HashSet<>();

        for (BlockCoordinate coordinate
                : candidate.coordinates()) {
            int chunkX = coordinate.x() >> 4;
            int chunkZ = coordinate.z() >> 4;
            long key = (((long) chunkX) << 32)
                    ^ (chunkZ & 0xffffffffL);

            if (loadedCoordinates.add(key)) {
                chunkLoads.add(
                        world.getChunkAtAsync(
                                chunkX,
                                chunkZ,
                                true
                        )
                );
            }
        }

        CompletableFuture.allOf(
                chunkLoads.toArray(CompletableFuture[]::new)
        ).whenComplete(
                (ignored, throwable) ->
                        core.getServer().getScheduler().runTask(
                                core,
                                () -> {
                                    if (throwable != null) {
                                        findDuelLocationsAsync(
                                                playerCount,
                                                attempt + 1,
                                                callback
                                        );
                                        return;
                                    }

                                    List<Location> locations =
                                            validateCandidate(
                                                    candidate
                                            );

                                    if (locations != null) {
                                        callback.accept(locations);
                                        return;
                                    }

                                    findDuelLocationsAsync(
                                            playerCount,
                                            attempt + 1,
                                            callback
                                    );
                                }
                        )
        );
    }

    private Candidate randomCandidate(
            World world,
            int playerCount
    ) {
        WorldBorder border = world.getWorldBorder();
        double borderRadius = Math.max(
                16.0D,
                border.getSize() / 2.0D - 32.0D
        );
        double maximumRadius = Math.min(
                borderRadius,
                destinationMaximumRadius > 0.0D
                        ? destinationMaximumRadius
                        : borderRadius
        );
        double minimumRadius = Math.min(
                Math.max(0.0D, destinationMinimumRadius),
                Math.max(0.0D, maximumRadius - 1.0D)
        );

        if (maximumRadius <= 0.0D) {
            return null;
        }

        double distance = minimumRadius
                + ThreadLocalRandom.current().nextDouble(
                Math.max(1.0D, maximumRadius - minimumRadius)
        );
        double angle = ThreadLocalRandom.current().nextDouble(
                Math.PI * 2.0D
        );
        int centerX = (int) Math.round(
                destinationCenterX
                        + Math.cos(angle) * distance
        );
        int centerZ = (int) Math.round(
                destinationCenterZ
                        + Math.sin(angle) * distance
        );

        List<BlockCoordinate> coordinates = new ArrayList<>();

        for (int index = 0; index < playerCount; index++) {
            double playerAngle = playerCount <= 1
                    ? 0.0D
                    : (Math.PI * 2.0D / playerCount) * index;
            int x = centerX + (int) Math.round(
                    Math.cos(playerAngle) * spawnSpacing
            );
            int z = centerZ + (int) Math.round(
                    Math.sin(playerAngle) * spawnSpacing
            );

            if (!insideBorder(world, x, z)) {
                return null;
            }

            coordinates.add(new BlockCoordinate(x, z));
        }

        return new Candidate(
                world,
                centerX,
                centerZ,
                List.copyOf(coordinates)
        );
    }

    private List<Location> validateCandidate(
            Candidate candidate
    ) {
        List<Location> locations = new ArrayList<>();

        for (BlockCoordinate coordinate
                : candidate.coordinates()) {
            Location safe = safeLocation(
                    candidate.world(),
                    coordinate.x(),
                    coordinate.z()
            );

            if (safe == null) {
                return null;
            }

            Vector facing = new Vector(
                    candidate.centerX() + 0.5D
                            - safe.getX(),
                    0.0D,
                    candidate.centerZ() + 0.5D
                            - safe.getZ()
            );

            if (facing.lengthSquared() > 0.0001D) {
                safe.setDirection(facing);
            }

            locations.add(safe);
        }

        return List.copyOf(locations);
    }

    private Location safeLocation(
            World world,
            int x,
            int z
    ) {
        int y = world.getHighestBlockYAt(
                x,
                z,
                HeightMap.MOTION_BLOCKING_NO_LEAVES
        );
        Block ground = world.getBlockAt(x, y, z);
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);

        if (!ground.getType().isSolid()
                || unsafeGround.contains(ground.getType())
                || !feet.isPassable()
                || !head.isPassable()
                || feet.isLiquid()
                || head.isLiquid()) {
            return null;
        }

        return new Location(
                world,
                x + 0.5D,
                y + 1.0D,
                z + 0.5D
        );
    }

    private void updateZoneQueue() {
        Set<UUID> found = new LinkedHashSet<>();
        long now = System.nanoTime();

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.hasPermission("mineacleduels.use")
                    || !validPlayer(player)
                    || pendingTeleport(player)
                    || hasInvite(player.getUniqueId())
                    || !insideAnyQueueZone(player)) {
                continue;
            }

            UUID playerId = player.getUniqueId();
            found.add(playerId);
            queuedAt.putIfAbsent(playerId, now);
        }

        for (UUID old : new HashSet<>(queuedAt.keySet())) {
            if (found.contains(old)) {
                continue;
            }

            queuedAt.remove(old);
            Player player = Bukkit.getPlayer(old);

            if (player != null && player.isOnline()) {
                sendActionBar(
                        player,
                        text(
                                "messages.queue-left-actionbar",
                                "&cNo longer in the duels queue"
                        )
                );
            }
        }
    }

    private boolean insideAnyQueueZone(Player player) {
        Location location = player.getLocation();

        for (QueueZone zone : queueZones) {
            if (!zone.worldName().equalsIgnoreCase(
                    player.getWorld().getName()
            )
                    || location.getY() < zone.minimumY()
                    || location.getY() > zone.maximumY()) {
                continue;
            }

            double x = location.getX() - zone.x();
            double z = location.getZ() - zone.z();

            if ((x * x) + (z * z)
                    <= zone.radiusSquared()) {
                return true;
            }
        }

        return false;
    }

    private List<Player> queuedPlayers() {
        return queuedAt.entrySet()
                .stream()
                .sorted(
                        Map.Entry
                                .<UUID, Long>comparingByValue()
                                .thenComparing(
                                        entry -> entry.getKey()
                                                .toString()
                                )
                )
                .map(entry -> Bukkit.getPlayer(entry.getKey()))
                .filter(this::validPlayer)
                .filter(player -> !pendingTeleport(player))
                .toList();
    }

    private void broadcastQueueActionBar(
            List<Player> players
    ) {
        int queued = players.size();
        String raw;

        if (queued < minimumPlayers) {
            raw = text(
                    "messages.queue-waiting-actionbar",
                    "&#ff55ffQueue&#bbbbbb: Waiting for an opponent"
            );
        } else {
            raw = text(
                    "messages.queue-actionbar",
                    "&#ff55ffQueue&#bbbbbb: "
                            + "&#ff88ff%queued%&#bbbbbb/"
                            + "&#ff55ff%max% &#bbbbbbteleporting "
                            + "in &d%seconds%s"
            )
                    .replace(
                            "%seconds%",
                            String.valueOf(
                                    Math.max(0, zoneCountdown)
                            )
                    )
                    .replace(
                            "%queued%",
                            String.valueOf(queued)
                    )
                    .replace(
                            "%min%",
                            String.valueOf(minimumPlayers)
                    )
                    .replace(
                            "%max%",
                            String.valueOf(maximumPlayers)
                    );
        }

        for (Player player : players) {
            sendActionBar(player, raw);
        }
    }

    public String setQueueZone(
            String id,
            Player player,
            double requestedRadius
    ) {
        String key = normalizeZoneId(id);
        String path = "queue-zones." + key;
        Location location = player.getLocation();
        double radius = finiteClamped(
                requestedRadius,
                1.0D,
                128.0D,
                9.0D
        );

        config.set(path + ".world", location.getWorld().getName());
        config.set(path + ".x", round(location.getX()));
        config.set(path + ".y", round(location.getY()));
        config.set(path + ".z", round(location.getZ()));
        config.set(path + ".radius", radius);
        config.set(path + ".min-y", round(location.getY() - 3.0D));
        config.set(path + ".max-y", round(location.getY() + 5.0D));

        save();
        loadQueueZones();
        return key;
    }

    public boolean removeQueueZone(String id) {
        String key = normalizeZoneId(id);
        String path = "queue-zones." + key;

        if (!config.isConfigurationSection(path)) {
            return false;
        }

        config.set(path, null);
        save();
        loadQueueZones();
        return true;
    }

    public List<String> queueZoneIds() {
        return queueZones.stream()
                .map(QueueZone::id)
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    public String queueZoneInfo(String id) {
        String key = normalizeZoneId(id);

        return queueZones.stream()
                .filter(zone -> zone.id().equalsIgnoreCase(key))
                .findFirst()
                .map(zone -> zone.id()
                        + " &#bbbbbb- "
                        + zone.worldName()
                        + " &#ff88ff"
                        + trimNumber(zone.x())
                        + " "
                        + trimNumber(zone.y())
                        + " "
                        + trimNumber(zone.z())
                        + " &#bbbbbbradius &#ff88ff"
                        + trimNumber(Math.sqrt(
                        zone.radiusSquared()
                )))
                .orElse(null);
    }

    public String trimNumber(double value) {
        double rounded = round(value);

        if (Math.abs(rounded - Math.rint(rounded)) < 0.001D) {
            return String.valueOf((long) rounded);
        }

        return String.valueOf(rounded);
    }

    private void cancelPendingTeleportMoved(
            PendingTeleport pending,
            Player moved
    ) {
        cancelPendingTeleportWithActor(
                pending,
                moved,
                text(
                        "messages.teleport-cancelled-other-moved",
                        "&cDuel cancelled — %player% moved"
                )
        );
    }

    private void cancelPendingTeleportWithActor(
            PendingTeleport pending,
            Player actor,
            String otherTemplate
    ) {
        List<Player> players = pending.onlinePlayers();
        clearPendingTeleport(pending);

        String actorName = DisplayNames.displayName(actor);
        String actorMessage = text(
                "messages.teleport-cancelled-moved",
                "&cTeleport cancelled — you moved"
        );
        String otherMessage = otherTemplate.replace(
                "%player%",
                actorName
        );

        for (Player player : players) {
            String message = player.getUniqueId().equals(
                    actor.getUniqueId()
            )
                    ? actorMessage
                    : otherMessage;

            player.sendMessage(legacy(message));
            sendActionBar(player, message);
            SoundService.teleportCancelled(player, core);
        }
    }

    private void cancelPendingTeleport(
            PendingTeleport pending,
            String rawMessage
    ) {
        List<Player> players = pending.onlinePlayers();
        clearPendingTeleport(pending);

        for (Player player : players) {
            player.sendMessage(legacy(rawMessage));
            sendActionBar(player, rawMessage);
            SoundService.teleportCancelled(player, core);
        }
    }

    private void clearPendingTeleport(
            PendingTeleport pending
    ) {
        pending.deactivate();
        pendingTeleports.remove(pending);

        for (UUID playerId : pending.playerIds()) {
            pendingByPlayer.remove(playerId, pending);
        }
    }

    private void cleanupInvites() {
        long now = System.currentTimeMillis();

        for (DuelInvite invite
                : new HashSet<>(incomingByTarget.values())) {
            if (invite.expiresAt() >= now) {
                continue;
            }

            removeInvite(invite);

            Player challenger = Bukkit.getPlayer(
                    invite.challengerId()
            );
            Player target = Bukkit.getPlayer(invite.targetId());

            if (challenger != null && challenger.isOnline()) {
                error(
                        challenger,
                        text(
                                "messages.invite-expired",
                                "&cDuel request expired"
                        )
                );
            }

            if (target != null && target.isOnline()) {
                error(
                        target,
                        text(
                                "messages.invite-expired",
                                "&cDuel request expired"
                        )
                );
            }
        }
    }

    private void removePlayerInvites(
            UUID playerId,
            Player actor,
            boolean notifyOthers
    ) {
        DuelInvite incoming = incomingByTarget.get(playerId);
        DuelInvite outgoing = outgoingByChallenger.get(playerId);

        if (incoming != null) {
            removeInvite(incoming);

            if (notifyOthers) {
                Player challenger = Bukkit.getPlayer(
                        incoming.challengerId()
                );

                if (challenger != null && challenger.isOnline()) {
                    challenger.sendMessage(legacy(
                            text(
                                    "messages.invite-cancelled-left",
                                    "&cDuel request cancelled — %player% left"
                            ).replace(
                                    "%player%",
                                    DisplayNames.displayName(actor)
                            )
                    ));
                    SoundService.guiCancel(challenger, core);
                }
            }
        }

        if (outgoing != null && outgoing != incoming) {
            removeInvite(outgoing);

            if (notifyOthers) {
                Player target = Bukkit.getPlayer(
                        outgoing.targetId()
                );

                if (target != null && target.isOnline()) {
                    target.sendMessage(legacy(
                            text(
                                    "messages.invite-cancelled-left",
                                    "&cDuel request cancelled — %player% left"
                            ).replace(
                                    "%player%",
                                    DisplayNames.displayName(actor)
                            )
                    ));
                    SoundService.guiCancel(target, core);
                }
            }
        }
    }

    private boolean hasInvite(UUID playerId) {
        return incomingByTarget.containsKey(playerId)
                || outgoingByChallenger.containsKey(playerId);
    }

    private void removeInvite(DuelInvite invite) {
        incomingByTarget.remove(invite.targetId(), invite);
        outgoingByChallenger.remove(
                invite.challengerId(),
                invite
        );
    }

    private boolean validPlayer(Player player) {
        if (player == null
                || !player.isOnline()
                || player.isDead()
                || player.isInsideVehicle()) {
            return false;
        }

        GameMode gameMode = player.getGameMode();

        return gameMode == GameMode.SURVIVAL
                || gameMode == GameMode.ADVENTURE;
    }

    private boolean insideBorder(World world, int x, int z) {
        WorldBorder border = world.getWorldBorder();
        Location center = border.getCenter();
        double half = Math.max(0.0D, border.getSize() / 2.0D - 2.0D);

        return Math.abs((x + 0.5D) - center.getX()) <= half
                && Math.abs((z + 0.5D) - center.getZ()) <= half;
    }

    private void loadSettings() {
        enabled = config.getBoolean("enabled", true);
        minimumPlayers = Math.max(
                2,
                config.getInt("queue.min-players", 2)
        );
        maximumPlayers = Math.max(
                minimumPlayers,
                config.getInt("queue.max-players", 8)
        );
        queueCountdownSeconds = Math.max(
                3,
                config.getInt("queue.countdown-seconds", 30)
        );
        defaultTeleportSeconds = Math.max(
                1,
                config.getInt(
                        "teleport.default-countdown-seconds",
                        5
                )
        );
        plusTeleportSeconds = Math.max(
                1,
                config.getInt(
                        "teleport.plus-countdown-seconds",
                        3
                )
        );
        plusPermission = config.getString(
                "teleport.plus-permission",
                "mineacle.plus"
        );

        if (plusPermission == null || plusPermission.isBlank()) {
            plusPermission = "mineacle.plus";
        }

        double cancelDistance = finiteClamped(
                config.getDouble(
                        "teleport.cancel-distance",
                        2.0D
                ),
                0.1D,
                64.0D,
                2.0D
        );
        cancelDistanceSquared = cancelDistance * cancelDistance;

        destinationWorld = config.getString(
                "destination.world",
                "overworld"
        );

        if (destinationWorld == null
                || destinationWorld.isBlank()) {
            destinationWorld = "overworld";
        }

        destinationCenterX = finite(
                config.getDouble("destination.center-x", 0.0D),
                0.0D
        );
        destinationCenterZ = finite(
                config.getDouble("destination.center-z", 1.0D),
                1.0D
        );
        destinationMinimumRadius = finiteClamped(
                config.getDouble(
                        "destination.min-radius",
                        5_000.0D
                ),
                0.0D,
                30_000_000.0D,
                5_000.0D
        );
        destinationMaximumRadius = finiteClamped(
                config.getDouble(
                        "destination.max-radius",
                        48_000.0D
                ),
                1.0D,
                30_000_000.0D,
                48_000.0D
        );
        locationAttempts = Math.max(
                10,
                Math.min(
                        500,
                        config.getInt(
                                "destination.find-location-attempts",
                                120
                        )
                )
        );
        spawnSpacing = finiteClamped(
                config.getDouble(
                        "destination.spawn-spacing",
                        8.0D
                ),
                3.0D,
                32.0D,
                8.0D
        );
        arrivalProtectionTicks = Math.max(
                0,
                Math.min(
                        200,
                        config.getInt(
                                "destination.arrival-protection-ticks",
                                40
                        )
                )
        );
        inviteTimeoutSeconds = Math.max(
                10,
                config.getInt(
                        "duel-request.timeout-seconds",
                        60
                )
        );

        loadQueueZones();
        loadUnsafeGround();
    }

    private void loadQueueZones() {
        ConfigurationSection section =
                config.getConfigurationSection("queue-zones");
        List<QueueZone> loaded = new ArrayList<>();

        if (section != null) {
            for (String id : section.getKeys(false)) {
                String path = "queue-zones." + id;
                String world = config.getString(
                        path + ".world",
                        ""
                );

                if (world == null || world.isBlank()) {
                    continue;
                }

                double x = finite(
                        config.getDouble(path + ".x"),
                        0.0D
                );
                double y = finite(
                        config.getDouble(path + ".y"),
                        64.0D
                );
                double z = finite(
                        config.getDouble(path + ".z"),
                        0.0D
                );
                double radius = finiteClamped(
                        config.getDouble(path + ".radius", 9.0D),
                        1.0D,
                        128.0D,
                        9.0D
                );
                double minimumY = finite(
                        config.getDouble(
                                path + ".min-y",
                                y - 3.0D
                        ),
                        y - 3.0D
                );
                double maximumY = finite(
                        config.getDouble(
                                path + ".max-y",
                                y + 5.0D
                        ),
                        y + 5.0D
                );

                loaded.add(new QueueZone(
                        normalizeZoneId(id),
                        world,
                        x,
                        y,
                        z,
                        radius * radius,
                        Math.min(minimumY, maximumY),
                        Math.max(minimumY, maximumY)
                ));
            }
        }

        loaded.sort(
                Comparator.comparing(
                        QueueZone::id,
                        String.CASE_INSENSITIVE_ORDER
                )
        );
        queueZones = List.copyOf(loaded);
    }

    private void loadUnsafeGround() {
        Set<Material> loaded = new HashSet<>();

        for (String raw : config.getStringList(
                "destination.unsafe-ground"
        )) {
            if (raw == null || raw.isBlank()) {
                continue;
            }

            Material material = Material.matchMaterial(raw);

            if (material != null) {
                loaded.add(material);
            }
        }

        if (loaded.isEmpty()) {
            loaded.addAll(Set.of(
                    Material.LAVA,
                    Material.WATER,
                    Material.MAGMA_BLOCK,
                    Material.CACTUS,
                    Material.POWDER_SNOW,
                    Material.FIRE,
                    Material.SOUL_FIRE
            ));
        }

        unsafeGround = Set.copyOf(loaded);
    }

    private void refreshQueueAfterReload() {
        queuedAt.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            return player == null
                    || !player.isOnline()
                    || !insideAnyQueueZone(player);
        });

        if (!enabled || queuedAt.size() < minimumPlayers) {
            zoneCountdown = -1;
        }
    }

    private void ensureDefaults() {
        config.addDefault("enabled", true);
        config.addDefault("queue.min-players", 2);
        config.addDefault("queue.max-players", 8);
        config.addDefault("queue.countdown-seconds", 30);
        config.addDefault(
                "teleport.default-countdown-seconds",
                5
        );
        config.addDefault(
                "teleport.plus-countdown-seconds",
                3
        );
        config.addDefault(
                "teleport.plus-permission",
                "mineacle.plus"
        );
        config.addDefault("teleport.cancel-distance", 2.0D);
        config.addDefault(
                "duel-request.timeout-seconds",
                60
        );
        config.addDefault("destination.world", "overworld");
        config.addDefault("destination.center-x", 0.0D);
        config.addDefault("destination.center-z", 1.0D);
        config.addDefault(
                "destination.min-radius",
                5_000.0D
        );
        config.addDefault(
                "destination.max-radius",
                48_000.0D
        );
        config.addDefault(
                "destination.find-location-attempts",
                120
        );
        config.addDefault(
                "destination.spawn-spacing",
                8.0D
        );
        config.addDefault(
                "destination.arrival-protection-ticks",
                40
        );
        config.options().copyDefaults(true);
    }

    private void migrateLegacyWorld() {
        String configured = config.getString(
                "destination.world",
                "overworld"
        );

        if (configured == null) {
            return;
        }

        String migrated = switch (
                configured.toLowerCase(Locale.ROOT)
        ) {
            case "origins" -> "overworld";
            case "origins_nether", "world_nether" ->
                    "overworld_nether";
            case "origins_the_end", "world_the_end" ->
                    "overworld_the_end";
            default -> configured;
        };

        if (!configured.equals(migrated)) {
            config.set("destination.world", migrated);
        }
    }

    private void save() {
        try {
            File temporary = new File(
                    file.getParentFile(),
                    file.getName() + ".tmp"
            );

            config.save(temporary);

            try {
                Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            } finally {
                Files.deleteIfExists(temporary.toPath());
            }
        } catch (IOException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not save duels.yml",
                    exception
            );
        }
    }

    private String text(String path, String fallback) {
        return config.getString(path, fallback);
    }

    private Component legacy(String input) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(input));
    }

    private void sendActionBar(Player player, String input) {
        if (player != null && player.isOnline()) {
            player.sendActionBar(legacy(input));
        }
    }

    private void error(Player player, String message) {
        if (player == null || !player.isOnline()) {
            return;
        }

        player.sendMessage(legacy(message));
        SoundService.guiError(player, core);
    }

    private String normalizeZoneId(String input) {
        String normalized = input == null
                ? ""
                : input.toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .replace('-', '_')
                .replaceAll("[^a-z0-9_]", "");

        if (normalized.length() > MAX_ZONE_ID_LENGTH) {
            normalized = normalized.substring(
                    0,
                    MAX_ZONE_ID_LENGTH
            );
        }

        return normalized.isBlank() ? "zone" : normalized;
    }

    private double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private double finite(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }

    private double finiteClamped(
            double value,
            double minimum,
            double maximum,
            double fallback
    ) {
        if (!Double.isFinite(value)) {
            return fallback;
        }

        return Math.max(minimum, Math.min(maximum, value));
    }

    private record QueueZone(
            String id,
            String worldName,
            double x,
            double y,
            double z,
            double radiusSquared,
            double minimumY,
            double maximumY
    ) {
    }

    private record BlockCoordinate(int x, int z) {
    }

    private record Candidate(
            World world,
            int centerX,
            int centerZ,
            List<BlockCoordinate> coordinates
    ) {
    }

    private final class PendingTeleport {

        private final List<UUID> playerIds;
        private final Map<UUID, Location> startLocations =
                new HashMap<>();
        private final int minimumPlayers;
        private final long completeAtNanos;

        private boolean active = true;
        private boolean searching;
        private int lastAnnouncedSeconds = Integer.MIN_VALUE;

        private PendingTeleport(
                List<Player> players,
                int minimumPlayers,
                int countdownSeconds
        ) {
            this.playerIds = players.stream()
                    .map(Player::getUniqueId)
                    .toList();
            this.minimumPlayers = minimumPlayers;
            this.completeAtNanos = System.nanoTime()
                    + countdownSeconds * 1_000_000_000L;

            for (Player player : players) {
                startLocations.put(
                        player.getUniqueId(),
                        player.getLocation().clone()
                );
            }
        }

        private List<UUID> playerIds() {
            return playerIds;
        }

        private int playerCount() {
            return playerIds.size();
        }

        private int minimumPlayers() {
            return minimumPlayers;
        }

        private boolean active() {
            return active;
        }

        private void deactivate() {
            active = false;
        }

        private boolean searching() {
            return searching;
        }

        private boolean beginSearching() {
            if (!active || searching) {
                return false;
            }

            searching = true;
            return true;
        }

        private int secondsRemaining() {
            long remaining = completeAtNanos - System.nanoTime();

            if (remaining <= 0L) {
                return 0;
            }

            return (int) Math.ceil(
                    remaining / 1_000_000_000.0D
            );
        }

        private boolean markAnnounced(int seconds) {
            if (lastAnnouncedSeconds == seconds) {
                return false;
            }

            lastAnnouncedSeconds = seconds;
            return true;
        }

        private List<Player> onlinePlayers() {
            List<Player> players = new ArrayList<>();

            for (UUID playerId : playerIds) {
                Player player = Bukkit.getPlayer(playerId);

                if (validPlayer(player)) {
                    players.add(player);
                }
            }

            return players;
        }

        private Player movedPlayer(double distanceSquared) {
            for (Player player : onlinePlayers()) {
                Location start = startLocations.get(
                        player.getUniqueId()
                );

                if (start == null
                        || start.getWorld() == null
                        || !start.getWorld().equals(
                        player.getWorld()
                )
                        || start.distanceSquared(
                        player.getLocation()
                ) > distanceSquared) {
                    return player;
                }
            }

            return null;
        }
    }
}
