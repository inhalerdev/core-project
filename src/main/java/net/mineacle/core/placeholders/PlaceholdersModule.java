package net.mineacle.core.placeholders;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.stats.StatsModule;
import net.mineacle.core.stats.service.StatsService;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;
import org.bukkit.scheduler.BukkitTask;

public final class PlaceholdersModule extends Module {

    private MineaclePlaceholderExpansion expansion;
    private MineacleTeamsPlaceholderExpansion teamsExpansion;
    private PlaceholderSnapshotService snapshots;
    private BukkitTask refreshTask;

    @Override
    public String name() {
        return "Placeholders";
    }

    @Override
    public void enable(Core core) {
        if (Bukkit.getPluginManager()
                .getPlugin("PlaceholderAPI") == null) {
            core.getLogger().warning(
                    "PlaceholderAPI is not installed — "
                            + "Mineacle placeholders were not registered"
            );
            return;
        }

        EconomyService economyService =
                EconomyModule.economyService();
        TeamService teamService =
                TeamsModule.teamService();
        StatsService statsService =
                StatsModule.statsService();

        snapshots = new PlaceholderSnapshotService(
                core,
                economyService,
                statsService
        );
        snapshots.refresh();

        expansion = new MineaclePlaceholderExpansion(
                core,
                economyService,
                teamService,
                statsService,
                snapshots
        );

        if (!expansion.register()) {
            clear();
            throw new IllegalStateException(
                    "Could not register %mineacle_*% placeholders"
            );
        }

        teamsExpansion =
                new MineacleTeamsPlaceholderExpansion(
                        core,
                        teamService
                );

        if (!teamsExpansion.register()) {
            expansion.unregister();
            clear();
            throw new IllegalStateException(
                    "Could not register %mineacleteams_*% placeholders"
            );
        }

        long refreshTicks = Math.max(
                20L,
                core.getConfig().getLong(
                        "placeholders.cache.refresh-ticks",
                        600L
                )
        );

        refreshTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        snapshots::refresh,
                        refreshTicks,
                        refreshTicks
                );

        core.getLogger().info(
                "Registered Mineacle PlaceholderAPI expansions"
        );
    }

    @Override
    public void disable() {
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }

        if (expansion != null) {
            expansion.unregister();
        }

        if (teamsExpansion != null) {
            teamsExpansion.unregister();
        }

        clear();
    }

    private void clear() {
        expansion = null;
        teamsExpansion = null;
        snapshots = null;
    }
}
