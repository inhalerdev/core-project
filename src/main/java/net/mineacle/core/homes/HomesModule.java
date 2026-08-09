package net.mineacle.core.homes;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.homes.command.HomeCommand;
import net.mineacle.core.homes.listener.HomesGuiListener;
import net.mineacle.core.homes.service.HomeService;
import net.mineacle.core.homes.service.HomeWorldRules;
import net.mineacle.core.homes.service.TeleportService;
import org.bukkit.command.PluginCommand;

public final class HomesModule extends Module {

    private Core core;
    private HomeService homeService;
    private HomeWorldRules worldRules;
    private TeleportService teleportService;

    @Override
    public String name() {
        return "Homes";
    }

    @Override
    public void enable(Core core) {
        this.core = core;
        this.homeService = new HomeService(core);
        this.worldRules = new HomeWorldRules(core);
        this.teleportService = core.teleportService();

        HomeCommand homeCommand = new HomeCommand(
                core,
                homeService,
                worldRules,
                teleportService
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
                        worldRules,
                        teleportService
                ),
                core
        );
    }

    @Override
    public void disable() {
        if (core != null) {
            core.saveHomesFile();
        }

        teleportService = null;
        worldRules = null;
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

    public HomeWorldRules worldRules() {
        return worldRules;
    }

    public TeleportService teleportService() {
        return teleportService;
    }
}
