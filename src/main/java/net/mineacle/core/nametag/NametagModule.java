package net.mineacle.core.nametag;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

public final class NametagModule extends Module {

    private static NametagService service;

    private BukkitTask contentTask;
    private BukkitTask movementTask;
    private BukkitTask cleanupTask;

    @Override
    public String name() {
        return "Nametags";
    }

    @Override
    public void enable(Core core) {
        service = new NametagService(core);

        PluginCommand command =
                core.getCommand("mineaclenametags");

        if (command == null) {
            service.clear();
            service = null;
            throw new IllegalStateException(
                    "Missing command in plugin.yml: mineaclenametags"
            );
        }

        NametagCommand executor =
                new NametagCommand(core, service);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        core.getServer().getPluginManager().registerEvents(
                new NametagListener(core, service),
                core
        );

        contentTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        service::refreshAll,
                        5L,
                        service.contentUpdateTicks()
                );
        movementTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        service::tickPositions,
                        2L,
                        service.movementUpdateTicks()
                );
        cleanupTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        service::removeOrphanDisplays,
                        200L,
                        service.cleanupIntervalTicks()
                );

        service.refreshAll();
    }

    @Override
    public void disable() {
        cancel(contentTask);
        cancel(movementTask);
        cancel(cleanupTask);

        contentTask = null;
        movementTask = null;
        cleanupTask = null;

        if (service != null) {
            service.clear();
            service = null;
        }
    }

    public static void refreshAll() {
        if (service != null) {
            service.refreshAll();
        }
    }

    public static void refresh(Player player) {
        if (service != null && player != null) {
            service.refresh(player);
        }
    }

    public static void refreshViewer(Player viewer) {
        if (service != null && viewer != null) {
            service.refreshViewer(viewer);
        }
    }

    private void cancel(BukkitTask task) {
        if (task != null) {
            task.cancel();
        }
    }
}
