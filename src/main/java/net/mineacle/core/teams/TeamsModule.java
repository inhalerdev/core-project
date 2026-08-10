package net.mineacle.core.teams;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.homes.HomesModule;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.stats.PlayerStatisticsGui;
import net.mineacle.core.teams.command.TeamCommand;
import net.mineacle.core.teams.listener.TeamChatListener;
import net.mineacle.core.teams.listener.TeamCombatListener;
import net.mineacle.core.teams.listener.TeamDeathListener;
import net.mineacle.core.teams.listener.TeamJoinListener;
import net.mineacle.core.teams.listener.TeamsGuiListener;
import net.mineacle.core.teams.service.TeamGuiState;
import net.mineacle.core.teams.service.TeamHomeService;
import net.mineacle.core.teams.service.TeamInviteService;
import net.mineacle.core.teams.service.TeamService;
import org.bukkit.command.PluginCommand;

public final class TeamsModule extends Module {

    private static TeamService activeTeamService;

    private Core core;
    private TeamService teamService;
    private TeamInviteService inviteService;
    private TeamGuiState guiState;
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

        HomesModule homesModule = requireHomesModule(core);
        this.homeService = homesModule.homeService();
        this.teleportService = core.teleports();
        this.teamService = new TeamService(core);
        activeTeamService = this.teamService;
        this.inviteService = new TeamInviteService(core, teamService);
        this.guiState = new TeamGuiState();
        this.teamHomeService = new TeamHomeService(core);
        this.playerStatisticsGui = new PlayerStatisticsGui();

        if (homeService == null || teleportService == null) {
            throw new IllegalStateException("Required core services are not initialized");
        }

        TeamCommand command = new TeamCommand(
                core,
                teamService,
                inviteService,
                teamHomeService,
                teleportService,
                homeService,
                guiState
        );

        PluginCommand team = core.getCommand("team");

        if (team == null) {
            throw new IllegalStateException("Missing command in plugin.yml: team");
        }

        team.setExecutor(command);
        team.setTabCompleter(command);

        core.getServer().getPluginManager().registerEvents(
                new TeamsGuiListener(
                        core,
                        teamService,
                        inviteService,
                        teamHomeService,
                        homeService,
                        teleportService,
                        playerStatisticsGui,
                        guiState
                ),
                core
        );
        core.getServer().getPluginManager().registerEvents(guiState, core);
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
        core.getServer().getPluginManager().registerEvents(
                playerStatisticsGui,
                core
        );
    }

    @Override
    public void disable() {
        if (core != null) {
            core.saveTeamsFile();
        }

        activeTeamService = null;
        playerStatisticsGui = null;
        teleportService = null;
        homeService = null;
        teamHomeService = null;
        guiState = null;
        inviteService = null;
        teamService = null;
        core = null;
    }

    private HomesModule requireHomesModule(Core core) {
        for (Module module : core.modules()) {
            if (module instanceof HomesModule homesModule) {
                return homesModule;
            }
        }

        throw new IllegalStateException("Teams requires the Homes module");
    }
}
