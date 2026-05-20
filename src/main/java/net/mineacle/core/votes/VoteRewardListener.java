package net.mineacle.core.votes;

import com.vexsoftware.votifier.model.Vote;
import com.vexsoftware.votifier.model.VotifierEvent;
import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public final class VoteRewardListener implements Listener {

    private final VoteRewardService service;

    public VoteRewardListener(Core core, VoteRewardService service) {
        this.service = service;
    }

    @EventHandler
    public void onVote(VotifierEvent event) {
        if (!service.enabled()) {
            return;
        }

        Vote vote = event.getVote();

        if (vote == null || vote.getUsername() == null || vote.getUsername().isBlank()) {
            return;
        }

        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(vote.getUsername());

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
}
