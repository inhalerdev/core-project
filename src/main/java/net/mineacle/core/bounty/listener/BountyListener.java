package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class BountyListener implements Listener {

    private final Core core;
    private final BountyService bountyService;

    public BountyListener(Core core, BountyService bountyService) {
        this.core = core;
        this.bountyService = bountyService;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player target = event.getEntity();
        Player killer = target.getKiller();

        if (killer == null) {
            return;
        }

        try {
            bountyService.claim(killer, target);
        } catch (Exception exception) {
            core.getLogger().severe("Bounty claim failed: " + exception.getMessage());
        }
    }
}