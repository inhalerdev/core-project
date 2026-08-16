package net.mineacle.core.hide;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.command.PluginCommand;

public final class HideModule extends Module {

    private static HideService service;
    private static VanishService vanishService;

    @Override
    public String name() {
        return "Hide";
    }

    @Override
    public void enable(Core core) {
        service = new HideService(core);
        vanishService = new VanishService(core);

        PluginCommand hideCommand = requiredCommand(
                core,
                "hide"
        );
        HideCommand hideExecutor =
                new HideCommand(core, service);
        hideCommand.setExecutor(hideExecutor);
        hideCommand.setTabCompleter(hideExecutor);

        PluginCommand vanishCommand = requiredCommand(
                core,
                "vanish"
        );
        VanishCommand vanishExecutor =
                new VanishCommand(
                        core,
                        vanishService
                );
        vanishCommand.setExecutor(vanishExecutor);
        vanishCommand.setTabCompleter(vanishExecutor);

        core.getServer().getPluginManager().registerEvents(
                new HideListener(core, service),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new VanishListener(
                        core,
                        vanishService
                ),
                core
        );

        service.start();
        vanishService.start();
        NametagModule.refreshAll();
    }

    @Override
    public void disable() {
        if (vanishService != null) {
            vanishService.stop();
            vanishService = null;
        }

        if (service != null) {
            service.showAll();
            service.stop();
            service = null;
        }
    }

    public static HideService service() {
        return service;
    }

    private PluginCommand requiredCommand(
            Core core,
            String name
    ) {
        PluginCommand command = core.getCommand(name);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: " + name
            );
        }

        return command;
    }
}
