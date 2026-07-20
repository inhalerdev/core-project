package net.mineacle.core.hide;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.command.PluginCommand;

public final class HideModule extends Module {

    private static HideService service;

    @Override
    public String name() {
        return "Hide";
    }

    @Override
    public void enable(Core core) {
        service = new HideService(core);

        PluginCommand command = core.getCommand("hide");

        if (command == null) {
            service = null;
            throw new IllegalStateException(
                    "Missing command in plugin.yml: hide"
            );
        }

        HideCommand executor = new HideCommand(core, service);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        core.getServer().getPluginManager().registerEvents(
                new HideListener(core, service),
                core
        );

        service.start();
        NametagModule.refreshAll();
    }

    @Override
    public void disable() {
        if (service != null) {
            service.showAll();
            service.stop();
            service = null;
        }
    }

    public static HideService service() {
        return service;
    }
}
