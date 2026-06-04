package net.mineacle.core.servermessages;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.servermessages.command.ServerControlCommand;
import net.mineacle.core.servermessages.command.WorldMaintenanceCommand;
import net.mineacle.core.servermessages.listener.ServerLoginListener;
import net.mineacle.core.servermessages.listener.WorldMaintenanceListener;
import net.mineacle.core.servermessages.service.ServerMessageService;
import net.mineacle.core.servermessages.service.WorldMaintenanceService;
import org.bukkit.command.PluginCommand;
import org.bukkit.scheduler.BukkitTask;

public final class ServerMessagesModule extends Module {

    private ServerMessageService service;
    private WorldMaintenanceService worldMaintenanceService;
    private BukkitTask worldMaintenanceTask;

    @Override
    public String name() {
        return "ServerMessages";
    }

    @Override
    public void enable(Core core) {
        core.saveResource("servermessages.yml", false);

        this.service = new ServerMessageService(core);
        this.worldMaintenanceService = new WorldMaintenanceService(core, service);

        ServerControlCommand command = new ServerControlCommand(core, service);
        WorldMaintenanceCommand worldCommand = new WorldMaintenanceCommand(worldMaintenanceService);

        register(core, "mineacleservermessages", command);
        register(core, "mineaclemaintenance", command);
        register(core, "mrestart", command);
        register(core, "mstop", command);
        register(core, "mineacleworldmaintenance", worldCommand);

        core.getServer().getPluginManager().registerEvents(new ServerLoginListener(service), core);
        core.getServer().getPluginManager().registerEvents(new WorldMaintenanceListener(worldMaintenanceService), core);

        this.worldMaintenanceTask = core.getServer().getScheduler().runTaskTimer(
                core,
                () -> {
                    if (worldMaintenanceService != null) {
                        worldMaintenanceService.tickOnlinePlayers();
                    }
                },
                100L,
                Math.max(20L, worldMaintenanceService.notifyIntervalSeconds() * 20L)
        );
    }

    @Override
    public void disable() {
        if (worldMaintenanceTask != null) {
            worldMaintenanceTask.cancel();
            worldMaintenanceTask = null;
        }

        if (worldMaintenanceService != null) {
            worldMaintenanceService.save();
            worldMaintenanceService = null;
        }

        if (service != null) {
            service.save();
            service = null;
        }
    }

    private void register(Core core, String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand pluginCommand = core.getCommand(name);
        if (pluginCommand == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + name);
            return;
        }

        pluginCommand.setExecutor(executor);

        if (executor instanceof org.bukkit.command.TabCompleter completer) {
            pluginCommand.setTabCompleter(completer);
        }
    }
}
