package net.mineacle.core.nametag;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.scheduler.BukkitTask;

public final class NametagModule extends Module {

    private static NametagService service;
    private BukkitTask refreshTask;
    private BukkitTask cleanupTask;

    @Override
    public String name() {
        return "Nametags";
    }

    @Override
    public void enable(Core core) {
        service = new NametagService(core);

        NametagCommand command = new NametagCommand(core, service);
        register(core, "mineaclenametags", command);

        core.getServer().getPluginManager().registerEvents(new NametagListener(core, service), core);

        long interval = Math.max(2L, service.updateIntervalTicks());
        refreshTask = core.getServer().getScheduler().runTaskTimer(core, service::refreshAll, 5L, interval);
        cleanupTask = core.getServer().getScheduler().runTaskTimer(core, service::removeOrphanDisplays, 100L, 200L);

        service.refreshAll();
    }

    @Override
    public void disable() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }

        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }

        if (service != null) {
            service.clear();
        }

        service = null;
    }

    public static void refreshAll() {
        if (service != null) {
            service.refreshAll();
        }
    }

    private void register(Core core, String commandName, CommandExecutor executor) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + commandName);
            return;
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
