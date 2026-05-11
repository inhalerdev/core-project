package net.mineacle.core.stats;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.stats.command.StatsCommand;
import org.bukkit.command.PluginCommand;

public final class StatsModule extends Module {

    private PlayerStatisticsGui playerStatisticsGui;

    @Override
    public String name() {
        return "Stats";
    }

    @Override
    public void enable(Core core) {
        this.playerStatisticsGui = new PlayerStatisticsGui();

        StatsCommand command = new StatsCommand(playerStatisticsGui);

        PluginCommand stats = core.getCommand("stats");
        if (stats != null) {
            stats.setExecutor(command);
            stats.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: stats");
        }

        core.getServer().getPluginManager().registerEvents(playerStatisticsGui, core);
    }

    @Override
    public void disable() {
    }
}