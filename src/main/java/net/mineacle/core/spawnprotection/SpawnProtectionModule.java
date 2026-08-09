package net.mineacle.core.spawnprotection;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.spawnprotection.listener.SpawnCollisionListener;
import net.mineacle.core.spawnprotection.listener.SpawnRestrictionListener;

public final class SpawnProtectionModule extends Module {

    private SpawnCollisionListener collisionListener;

    @Override
    public String name() {
        return "SpawnProtection";
    }

    @Override
    public void enable(Core core) {
        core.getServer().getPluginManager().registerEvents(new SpawnRestrictionListener(core), core);

        collisionListener = new SpawnCollisionListener(core);
        core.getServer().getPluginManager().registerEvents(collisionListener, core);
        collisionListener.applyToOnlinePlayers();
    }

    @Override
    public void disable() {
        if (collisionListener != null) {
            collisionListener.restoreOnlinePlayers();
            collisionListener = null;
        }
    }
}
