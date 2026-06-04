package net.mineacle.core.worldmaintenance;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.worldmaintenance.command.WorldMaintenanceCommand;
import net.mineacle.core.worldmaintenance.listener.WorldMaintenanceListener;
import net.mineacle.core.worldmaintenance.service.WorldMaintenanceService;
import org.bukkit.command.PluginCommand;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;

public final class WorldMaintenanceModule extends Module {

    private WorldMaintenanceService service;
    private BukkitTask task;

    @Override
    public String name() {
        return "WorldMaintenance";
    }

    @Override
    public void enable(Core core) {
        File file = new File(core.getDataFolder(), "worldmaintenance.yml");

        if (!file.exists()) {
            try {
                core.saveResource("worldmaintenance.yml", false);
            } catch (IllegalArgumentException ignored) {
                core.getLogger().warning("worldmaintenance.yml was not embedded in the jar, creating empty runtime file");
            }
        }

        this.service = new WorldMaintenanceService(core);

        WorldMaintenanceCommand command = new WorldMaintenanceCommand(service);
        register(core, "mineacleworldmaintenance", command);

        core.getServer().getPluginManager().registerEvents(new WorldMaintenanceListener(core, service), core);

        this.task = core.getServer().getScheduler().runTaskTimer(
                core,
                () -> {
                    if (service != null) {
                        service.tickOnlinePlayers();
                    }
                },
                100L,
                Math.max(20L, service.notifyIntervalSeconds() * 20L)
        );
    }

    @Override
    public void disable() {
        if (task != null) {
            task.cancel();
            task = null;
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
