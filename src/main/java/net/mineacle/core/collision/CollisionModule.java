package net.mineacle.core.collision;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.scheduler.BukkitTask;

public final class CollisionModule extends Module {

    private static PlayerCollisionService activeService;

    private PlayerCollisionService service;
    private BukkitTask enforcementTask;

    public static PlayerCollisionService service() {
        return activeService;
    }

    @Override
    public String name() {
        return "Collision";
    }

    @Override
    public void enable(Core core) {
        service = new PlayerCollisionService(core);
        activeService = service;

        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new PlayerCollisionListener(service),
                        core
                );

        service.applyAll();

        long interval = Math.max(
                20L,
                core.getConfig().getLong(
                        "player-collision.enforce-every-ticks",
                        100L
                )
        );

        enforcementTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        service::applyAll,
                        interval,
                        interval
                );
    }

    @Override
    public void disable() {
        if (enforcementTask != null) {
            enforcementTask.cancel();
            enforcementTask = null;
        }

        if (service != null) {
            service.restoreAll();
        }

        if (activeService == service) {
            activeService = null;
        }

        service = null;
    }
}
