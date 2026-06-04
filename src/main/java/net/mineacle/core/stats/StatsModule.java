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

public final class StatsModule extends Module {

    private static StatsService statsService;

    private StatsStorageService storageService;
    private PlayerStatisticsGui playerStatisticsGui;

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
    }

    @Override
    public void disable() {
        if (statsService != null) {
            statsService.save();
        }

        statsService = null;
        storageService = null;
        playerStatisticsGui = null;
    }
}
