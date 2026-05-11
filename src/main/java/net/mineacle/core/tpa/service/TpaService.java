package net.mineacle.core.tpa.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class TpaService {

    private final Core core;
    private final Map<UUID, TpaRequest> requestsByTarget = new HashMap<>();

    public TpaService(Core core) {
        this.core = core;
    }

    public int timeoutSeconds() {
        return Math.max(5, core.getConfig().getInt("tpa.request-timeout-seconds", 60));
    }

    public boolean createRequest(Player requester, Player target, TpaRequestType type) {
        if (requester == null || target == null) {
            return false;
        }

        if (requester.getUniqueId().equals(target.getUniqueId())) {
            return false;
        }

        requestsByTarget.put(
                target.getUniqueId(),
                new TpaRequest(
                        requester.getUniqueId(),
                        target.getUniqueId(),
                        type,
                        System.currentTimeMillis()
                )
        );

        return true;
    }

    public TpaRequest getRequest(UUID targetId) {
        TpaRequest request = requestsByTarget.get(targetId);

        if (request == null) {
            return null;
        }

        if (isExpired(request)) {
            requestsByTarget.remove(targetId);
            return null;
        }

        return request;
    }

    public boolean hasRequest(UUID targetId) {
        return getRequest(targetId) != null;
    }

    public TpaRequest removeRequest(UUID targetId) {
        return requestsByTarget.remove(targetId);
    }

    public void clear(UUID playerId) {
        requestsByTarget.remove(playerId);

        requestsByTarget.entrySet().removeIf(entry ->
                entry.getValue().requesterId().equals(playerId)
                        || entry.getValue().targetId().equals(playerId)
        );
    }

    public Player requester(TpaRequest request) {
        if (request == null) {
            return null;
        }

        return Bukkit.getPlayer(request.requesterId());
    }

    public Player target(TpaRequest request) {
        if (request == null) {
            return null;
        }

        return Bukkit.getPlayer(request.targetId());
    }

    private boolean isExpired(TpaRequest request) {
        long age = System.currentTimeMillis() - request.createdAt();
        return age > timeoutSeconds() * 1000L;
    }
}