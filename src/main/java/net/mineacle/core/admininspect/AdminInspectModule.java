package net.mineacle.core.admininspect;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.command.EnderChestCommand;
import net.mineacle.core.admininspect.command.InvSeeCommand;
import net.mineacle.core.admininspect.listener.AdminInspectListener;
import net.mineacle.core.admininspect.service.AdminInspectService;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class AdminInspectModule
        extends Module {

    private AdminInspectService service;

    @Override
    public String name() {
        return "AdminInspect";
    }

    @Override
    public void enable(
            Core core
    ) {
        service =
                new AdminInspectService(core);

        register(
                core,
                "invsee",
                new InvSeeCommand(
                        core,
                        service
                )
        );
        register(
                core,
                "echest",
                new EnderChestCommand(
                        core,
                        service
                )
        );

        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new AdminInspectListener(
                                service
                        ),
                        core
                );
    }

    @Override
    public void disable() {
        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    /**
     * Admin inspection is a security-sensitive system. A missing command
     * declaration is treated as a startup error rather than leaving the
     * module half-enabled.
     */
    private void register(
            Core core,
            String commandName,
            CommandExecutor executor
    ) {
        PluginCommand command =
                core.getCommand(commandName);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: "
                            + commandName
            );
        }

        command.setExecutor(executor);

        if (executor
                instanceof TabCompleter tabCompleter) {
            command.setTabCompleter(
                    tabCompleter
            );
        }
    }
}
