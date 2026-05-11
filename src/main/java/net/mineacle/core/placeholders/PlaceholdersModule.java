package net.mineacle.core.placeholders;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.teams.TeamsModule;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.Bukkit;

public final class PlaceholdersModule extends Module {

    private MineaclePlaceholderExpansion expansion;
    private MineacleTeamsPlaceholderExpansion teamsExpansion;

    @Override
    public String name() {
        return "Placeholders";
    }

    @Override
    public void enable(Core core) {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            core.getLogger().warning("PlaceholderAPI is not installed. Mineacle placeholders were not registered.");
            return;
        }

        EconomyService economyService = EconomyModule.economyService();
        TeamService teamService = TeamsModule.teamService();

        if (economyService == null) {
            core.getLogger().warning("Mineacle placeholders could not register because EconomyService is not loaded.");
            return;
        }

        if (teamService == null) {
            core.getLogger().warning("Mineacle placeholders could not register because TeamService is not loaded.");
            return;
        }

        this.expansion = new MineaclePlaceholderExpansion(core, economyService, teamService);
        this.expansion.register();

        this.teamsExpansion = new MineacleTeamsPlaceholderExpansion(core, teamService);
        this.teamsExpansion.register();

        core.getLogger().info("Registered Mineacle PlaceholderAPI expansions.");
    }

    @Override
    public void disable() {
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }

        if (teamsExpansion != null) {
            teamsExpansion.unregister();
            teamsExpansion = null;
        }
    }
}