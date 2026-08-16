package net.mineacle.core.admininspect;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.command.EnderChestCommand;
import net.mineacle.core.admininspect.command.InvSeeCommand;
import net.mineacle.core.admininspect.listener.AdminInspectListener;
import net.mineacle.core.admininspect.listener.OfflineInspectListener;
import net.mineacle.core.admininspect.service.AdminInspectService;
import net.mineacle.core.admininspect.service.OfflineInspectService;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class AdminInspectModule extends Module {

    private AdminInspectService service;
    private OfflineInspectService offlineService;

    @Override
    public String name() {
        return "AdminInspect";
    }

    @Override
    public void enable(Core core) {
        service = new AdminInspectService(core);
        offlineService = new OfflineInspectService(core);

        register(
                core,
                "invsee",
                new InvSeeCommand(
                        core,
                        service,
                        offlineService
                )
        );
        register(
                core,
                "echest",
                new EnderChestCommand(
                        core,
                        service,
                        offlineService
                )
        );

        core.getServer().getPluginManager().registerEvents(
                new AdminInspectListener(service),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new OfflineInspectListener(
                        core,
                        offlineService
                ),
                core
        );

        offlineService.start();
    }

    @Override
    public void disable() {
        if (offlineService != null) {
            offlineService.shutdown();
            offlineService = null;
        }

        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    private void register(
            Core core,
            String name,
            CommandExecutor executor
    ) {
        PluginCommand command = core.getCommand(name);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: " + name
            );
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
