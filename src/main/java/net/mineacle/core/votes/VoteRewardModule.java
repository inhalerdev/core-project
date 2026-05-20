package net.mineacle.core.votes;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.plugin.Plugin;

public final class VoteRewardModule extends Module {

    private VoteRewardService service;

    @Override
    public String name() {
        return "VoteRewards";
    }

    @Override
    public void enable(Core core) {
        service = new VoteRewardService(core);

        Plugin nuVotifier = core.getServer().getPluginManager().getPlugin("NuVotifier");
        Plugin votifier = core.getServer().getPluginManager().getPlugin("Votifier");

        if ((nuVotifier == null || !nuVotifier.isEnabled()) && (votifier == null || !votifier.isEnabled())) {
            core.getLogger().warning("NuVotifier/Votifier not found; vote reward listener may not receive vote events");
        }

        core.getServer().getPluginManager().registerEvents(new VoteRewardListener(core, service), core);
    }

    @Override
    public void disable() {
        if (service != null) {
            service.save();
        }

        service = null;
    }
}
