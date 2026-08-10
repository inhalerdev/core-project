package net.mineacle.core.tpa;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.tpa.command.TpaCommand;
import net.mineacle.core.tpa.command.TpaMenuCommand;
import net.mineacle.core.tpa.listener.TpaGuiListener;
import net.mineacle.core.tpa.listener.TpaLifecycleListener;
import net.mineacle.core.tpa.listener.TpaTargetMenuListener;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class TpaModule extends Module {

    private TpaService tpaService;
    private TeleportService teleportService;

    @Override
    public String name() {
        return "TPA";
    }

    @Override
    public void enable(Core core) {
        this.tpaService = new TpaService(core);
        this.teleportService = core.teleports();

        if (teleportService == null) {
            throw new IllegalStateException("Core TeleportService is not initialized");
        }

        TpaCommand command = new TpaCommand(core, tpaService, teleportService);
        TpaMenuCommand menuCommand = new TpaMenuCommand(core);

        registerCommand(core, "tpa", command);
        registerCommand(core, "tpahere", command);
        registerCommand(core, "tpaccept", command);
        registerCommand(core, "tpdeny", command);
        registerCommand(core, "tpacancel", command);
        registerCommand(core, "tpauto", command);
        registerCommand(core, "tpamenu", menuCommand);

        core.getServer().getPluginManager().registerEvents(
                new TpaGuiListener(core, tpaService, teleportService),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new TpaTargetMenuListener(core, tpaService),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new TpaLifecycleListener(tpaService),
                core
        );

        tpaService.start();
    }

    @Override
    public void disable() {
        if (tpaService != null) {
            tpaService.shutdown();
        }

        tpaService = null;
        teleportService = null;
    }

    private void registerCommand(Core core, String name, Object executor) {
        PluginCommand command = core.getCommand(name);

        if (command == null) {
            throw new IllegalStateException("Missing command in plugin.yml: " + name);
        }

        if (!(executor instanceof CommandExecutor commandExecutor)) {
            throw new IllegalArgumentException(
                    "Command executor does not implement CommandExecutor: " + name
            );
        }

        command.setExecutor(commandExecutor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
