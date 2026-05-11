package net.mineacle.core.teams;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.homes.service.TeleportService;
import net.mineacle.core.stats.PlayerStatisticsGui;
import net.mineacle.core.teams.command.TeamCommand;
import net.mineacle.core.teams.listener.TeamChatListener;
import net.mineacle.core.teams.listener.TeamCombatListener;
import net.mineacle.core.teams.listener.TeamDeathListener;
import net.mineacle.core.teams.listener.TeamJoinListener;
import net.mineacle.core.teams.listener.TeamsGuiListener;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.command.PluginCommand;

public final class TeamsModule extends Module {

    private static TeamService activeTeamService;

    private Core core;
    private TeamService teamService;
    private TeamInviteService inviteService;
    private TeamHomeService teamHomeService;
    private HomeService homeService;
    private TeleportService teleportService;
    private PlayerStatisticsGui playerStatisticsGui;

    public static TeamService teamService() {
        return activeTeamService;
    }

    @Override
    public String name() {
        return "Teams";
    }

    @Override
    public void enable(Core core) {
        this.core = core;

        this.teamService = new TeamService(core);
        activeTeamService = this.teamService;

        this.inviteService = new TeamInviteService(core, teamService);
        this.teamHomeService = new TeamHomeService(core, teamService);
        this.homeService = new HomeService(core);
        this.teleportService = new TeleportService(core);
        this.playerStatisticsGui = new PlayerStatisticsGui();

        TeamCommand command = new TeamCommand(
                core,
                teamService,
                inviteService,
                teamHomeService,
                teleportService
        );

        PluginCommand team = core.getCommand("team");
        if (team != null) {
            team.setExecutor(command);
            team.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: team");
        }

        core.getServer().getPluginManager().registerEvents(
                new TeamsGuiListener(
                        core,
                        teamService,
                        inviteService,
                        teamHomeService,
                        homeService,
                        teleportService,
                        playerStatisticsGui
                ),
                core
        );

        core.getServer().getPluginManager().registerEvents(
                new TeamCombatListener(teamService),
                core
        );

        core.getServer().getPluginManager().registerEvents(
                new TeamChatListener(core, teamService),
                core
        );

        core.getServer().getPluginManager().registerEvents(
                new TeamDeathListener(core, teamService),
                core
        );

        core.getServer().getPluginManager().registerEvents(
                new TeamJoinListener(core, teamService),
                core
        );

        core.getServer().getPluginManager().registerEvents(playerStatisticsGui, core);
    }

    @Override
    public void disable() {
        if (core != null) {
            core.saveTeamsFile();
        }

        activeTeamService = null;
    }
}