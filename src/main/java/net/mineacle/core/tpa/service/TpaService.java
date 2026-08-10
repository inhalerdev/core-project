package net.mineacle.core.tpa.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory TPA request registry with indexed outgoing requests and one shared
 * expiry sweep. No per-request Bukkit tasks are created.
 */
public final class TpaService {

    public enum CreateResult {
        SUCCESS,
        INVALID_REQUEST
    }

    private final Core core;
    private final Map<UUID, TpaRequest> requestsByTarget = new HashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> targetsByRequester = new HashMap<>();
    private final Set<UUID> autoAccept = new HashSet<>();
    private final Map<UUID, UUID> menuTargets = new HashMap<>();
    private BukkitTask expiryTask;

    public TpaService(Core core) {
        this.core = core;
    }

    public void start() {
        if (expiryTask != null) {
            return;
        }

        expiryTask = core.getServer().getScheduler().runTaskTimer(
                core,
                this::expireRequests,
                20L,
                20L
        );
    }

    public void shutdown() {
        if (expiryTask != null) {
            expiryTask.cancel();
            expiryTask = null;
        }

        requestsByTarget.clear();
        targetsByRequester.clear();
        autoAccept.clear();
        menuTargets.clear();
    }

    public int timeoutSeconds() {
        return Math.max(
                5,
                core.getConfig().getInt(
                        "tpa.request-timeout-seconds",
                        60
                )
        );
    }

    public CreateResult createRequest(
            Player requester,
            Player target,
            TpaRequestType type
    ) {
        if (requester == null || target == null || type == null) {
            return CreateResult.INVALID_REQUEST;
        }

        UUID requesterId = requester.getUniqueId();
        UUID targetId = target.getUniqueId();

        if (requesterId.equals(targetId)) {
            return CreateResult.INVALID_REQUEST;
        }

        // A target has one actionable incoming request at a time. Replacing it
        // also removes the old requester's outgoing index entry.
        removeRequestInternal(targetId);

        TpaRequest request = new TpaRequest(
                requesterId,
                targetId,
                type,
                System.currentTimeMillis()
        );
        requestsByTarget.put(targetId, request);
        targetsByRequester
                .computeIfAbsent(
                        requesterId,
                        ignored -> new LinkedHashSet<>()
                )
                .add(targetId);
        return CreateResult.SUCCESS;
    }

    public TpaRequest getRequest(UUID targetId) {
        if (targetId == null) {
            return null;
        }

        TpaRequest request = requestsByTarget.get(targetId);

        if (request == null) {
            return null;
        }

        if (isExpired(request)) {
            removeRequestInternal(targetId);
            return null;
        }

        return request;
    }

    public TpaRequest removeRequest(UUID targetId) {
        return removeRequestInternal(targetId);
    }

    public TpaRequest removeOutgoing(UUID requesterId) {
        if (requesterId == null) {
            return null;
        }

        LinkedHashSet<UUID> targets = targetsByRequester.get(requesterId);

        if (targets == null || targets.isEmpty()) {
            return null;
        }

        for (UUID targetId : new ArrayList<>(targets)) {
            TpaRequest request = requestsByTarget.get(targetId);

            if (request == null) {
                removeOutgoingIndex(requesterId, targetId);
                continue;
            }

            if (isExpired(request)) {
                removeRequestInternal(targetId);
                continue;
            }

            return removeRequestInternal(targetId);
        }

        return null;
    }

    public void selectMenuTarget(UUID viewerId, UUID targetId) {
        if (viewerId == null || targetId == null) {
            return;
        }

        menuTargets.put(viewerId, targetId);
    }

    public Player menuTarget(UUID viewerId) {
        if (viewerId == null) {
            return null;
        }

        UUID targetId = menuTargets.get(viewerId);
        return targetId == null ? null : Bukkit.getPlayer(targetId);
    }

    public void clearMenuTarget(UUID viewerId) {
        if (viewerId != null) {
            menuTargets.remove(viewerId);
        }
    }

    public boolean toggleAutoAccept(UUID playerId) {
        if (playerId == null) {
            return false;
        }

        if (autoAccept.remove(playerId)) {
            return false;
        }

        autoAccept.add(playerId);
        return true;
    }

    public boolean isAutoAccepting(UUID playerId) {
        return playerId != null && autoAccept.contains(playerId);
    }

    public void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }

        removeRequestInternal(playerId);

        LinkedHashSet<UUID> outgoing = targetsByRequester.get(playerId);
        if (outgoing != null) {
            for (UUID targetId : new ArrayList<>(outgoing)) {
                removeRequestInternal(targetId);
            }
        }

        autoAccept.remove(playerId);
        menuTargets.remove(playerId);
        menuTargets.values().removeIf(playerId::equals);
    }

    public Player requester(TpaRequest request) {
        return request == null
                ? null
                : Bukkit.getPlayer(request.requesterId());
    }

    public Player target(TpaRequest request) {
        return request == null
                ? null
                : Bukkit.getPlayer(request.targetId());
    }

    private void expireRequests() {
        if (requestsByTarget.isEmpty()) {
            return;
        }

        long now = System.currentTimeMillis();
        long timeoutMillis = timeoutSeconds() * 1_000L;
        List<TpaRequest> expired = new ArrayList<>();

        for (TpaRequest request : requestsByTarget.values()) {
            if (now - request.createdAt() > timeoutMillis) {
                expired.add(request);
            }
        }

        for (TpaRequest request : expired) {
            TpaRequest removed = removeRequestInternal(request.targetId());

            if (removed == null) {
                continue;
            }

            notifyExpired(removed.requesterId());
            notifyExpired(removed.targetId());
        }
    }

    private void notifyExpired(UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);

        if (player == null || !player.isOnline()) {
            return;
        }

        Component message = expiredMessage();
        player.sendMessage(message);
        player.sendActionBar(message);
        SoundService.guiError(player, core);
    }

    private TpaRequest removeRequestInternal(UUID targetId) {
        if (targetId == null) {
            return null;
        }

        TpaRequest removed = requestsByTarget.remove(targetId);

        if (removed != null) {
            removeOutgoingIndex(
                    removed.requesterId(),
                    removed.targetId()
            );
        }

        return removed;
    }

    private void removeOutgoingIndex(UUID requesterId, UUID targetId) {
        LinkedHashSet<UUID> targets = targetsByRequester.get(requesterId);

        if (targets == null) {
            return;
        }

        targets.remove(targetId);

        if (targets.isEmpty()) {
            targetsByRequester.remove(requesterId);
        }
    }

    private boolean isExpired(TpaRequest request) {
        long age = System.currentTimeMillis() - request.createdAt();
        return age > timeoutSeconds() * 1_000L;
    }

    private Component expiredMessage() {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(TextColor.color("&cTeleport request expired"));
    }
}
