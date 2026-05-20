package net.mineacle.core.votes;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.lang.reflect.Method;

public final class VoteRewardListener implements Listener {

    private final VoteRewardService service;

    public VoteRewardListener(VoteRewardService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVote(Event event) {
        if (!service.enabled()) {
            return;
        }

        if (!event.getClass().getName().equals("com.vexsoftware.votifier.model.VotifierEvent")) {
            return;
        }

        String username = username(event);

        if (username == null || username.isBlank()) {
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(username);

        if (offlinePlayer.getName() == null) {
            return;
        }

        int votes = service.addVote(offlinePlayer.getUniqueId());
        Player online = offlinePlayer.getPlayer();

        if (online != null && online.isOnline()) {
            service.sendProgress(online, votes);
        }

        if (votes < service.requiredVotes()) {
            return;
        }

        service.resetVotes(offlinePlayer.getUniqueId());
        service.reward(offlinePlayer);

        if (online != null && online.isOnline()) {
            service.sendReward(online);
        }
    }

    private String username(Event event) {
        try {
            Method getVote = event.getClass().getMethod("getVote");
            Object vote = getVote.invoke(event);

            if (vote == null) {
                return null;
            }

            Method getUsername = vote.getClass().getMethod("getUsername");
            Object username = getUsername.invoke(vote);

            return username == null ? null : username.toString();
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }
}
