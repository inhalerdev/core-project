package net.mineacle.core.homes;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.homes.command.HomeCommand;
import net.mineacle.core.homes.listener.HomesGuiListener;
import net.mineacle.core.homes.service.HomeGuiState;
import net.mineacle.core.homes.service.HomeService;
import org.bukkit.command.PluginCommand;

public final class HomesModule extends Module {

    private Core core;
    private HomeService homeService;
    private HomeGuiState guiState;
    private TeleportService teleportService;

    @Override
    public String name() {
        return "Homes";
    }

    @Override
    public void enable(Core core) {
        this.core = core;
        homeService = new HomeService(core);
        guiState = new HomeGuiState();
        teleportService = core.teleports();

        if (teleportService == null) {
            throw new IllegalStateException(
                    "Core TeleportService is not initialized"
            );
        }

        HomeCommand homeCommand = new HomeCommand(
                core,
                homeService,
                guiState
        );

        registerCommand("home", homeCommand);
        registerCommand("sethome", homeCommand);
        registerCommand("delhome", homeCommand);
        registerCommand("renamehome", homeCommand);
        registerCommand("mineaclehomes", homeCommand);

        core.getServer().getPluginManager().registerEvents(
                new HomesGuiListener(
                        core,
                        homeService,
                        teleportService,
                        guiState
                ),
                core
        );
        core.getServer().getPluginManager().registerEvents(guiState, core);
    }

    @Override
    public void disable() {
        if (core != null) {
            core.saveHomesFile();
        }

        teleportService = null;
        guiState = null;
        homeService = null;
        core = null;
    }

    private void registerCommand(
            String name,
            HomeCommand executor
    ) {
        PluginCommand command = core.getCommand(name);
        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: " + name
            );
        }

        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    public HomeService homeService() {
        return homeService;
    }

}
