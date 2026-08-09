package net.mineacle.core.stats;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.command.StatsCommand;
import net.mineacle.core.stats.listener.StatsListener;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.stats.service.StatsStorageService;
import org.bukkit.command.PluginCommand;
import org.bukkit.scheduler.BukkitTask;

public final class StatsModule extends Module {

    private static StatsService statsService;

    private StatsStorageService storageService;
    private PlayerStatisticsGui playerStatisticsGui;
    private BukkitTask autosaveTask;

    public static StatsService statsService() {
        return statsService;
    }

    @Override
    public String name() {
        return "Stats";
    }

    @Override
    public void enable(Core core) {
        EconomyService economyService = EconomyModule.economyService();

        this.storageService = new StatsStorageService(core);
        statsService = new StatsService(core, storageService, economyService);
        this.playerStatisticsGui = new PlayerStatisticsGui(statsService);

        StatsCommand command = new StatsCommand(playerStatisticsGui);
        PluginCommand stats = core.getCommand("stats");

        if (stats != null) {
            stats.setExecutor(command);
            stats.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: stats");
        }

        core.getServer().getPluginManager().registerEvents(playerStatisticsGui, core);
        core.getServer().getPluginManager().registerEvents(new StatsListener(statsService), core);

        long autosaveTicks = core.getConfig().getLong("stats.autosave-seconds", 60L) * 20L;
        if (autosaveTicks < 20L) {
            autosaveTicks = 1200L;
        }

        this.autosaveTask = core.getServer().getScheduler().runTaskTimer(
                core,
                () -> {
                    if (statsService != null) {
                        statsService.autosave();
                    }
                },
                autosaveTicks,
                autosaveTicks
        );
    }

    @Override
    public void disable() {
        if (autosaveTask != null) {
            autosaveTask.cancel();
            autosaveTask = null;
        }

        if (statsService != null) {
            statsService.save();
        }

        statsService = null;
        storageService = null;
        playerStatisticsGui = null;
    }
}
