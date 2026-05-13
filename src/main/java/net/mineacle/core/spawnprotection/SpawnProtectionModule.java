package net.mineacle.core.spawnprotection;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.spawnprotection.listener.SpawnRestrictionListener;

public final class SpawnProtectionModule extends Module {

    @Override
    public String name() {
        return "SpawnProtection";
    }

    @Override
    public void enable(Core core) {
        core.getServer().getPluginManager().registerEvents(new SpawnRestrictionListener(core), core);
    }

    @Override
    public void disable() {
    }
}
