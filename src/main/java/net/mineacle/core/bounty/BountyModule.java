package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;

public final class BountyModule extends Module {

    private Core core;

    @Override
    public String name() {
        return "Bounty";
    }

    @Override
    public void enable(Core core) {
        this.core = core;
        core.getLogger().warning("Bounty module is temporarily disabled while database-backed storage is removed.");
    }

    @Override
    public void disable() {
        if (core != null) {
            core.getLogger().info("Bounty module unloaded.");
        }
    }
}