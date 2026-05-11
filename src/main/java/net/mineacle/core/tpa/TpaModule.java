package net.mineacle.core.tpa;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.homes.service.TeleportService;
import net.mineacle.core.tpa.command.TpaCommand;
import net.mineacle.core.tpa.command.TpaMenuCommand;
import net.mineacle.core.tpa.listener.TpaGuiListener;
import net.mineacle.core.tpa.listener.TpaTargetMenuListener;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.command.PluginCommand;

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
        this.teleportService = new TeleportService(core);

        TpaCommand command = new TpaCommand(core, tpaService, teleportService);
        TpaMenuCommand menuCommand = new TpaMenuCommand(core);

        registerCommand(core, "tpa", command);
        registerCommand(core, "tpahere", command);
        registerCommand(core, "tpaccept", command);
        registerCommand(core, "tpdeny", command);
        registerCommand(core, "tpamenu", menuCommand);

        core.getServer().getPluginManager().registerEvents(
                new TpaGuiListener(core, tpaService, teleportService),
                core
        );

        core.getServer().getPluginManager().registerEvents(
                new TpaTargetMenuListener(core, tpaService),
                core
        );
    }

    @Override
    public void disable() {
        tpaService = null;
        teleportService = null;
    }

    private void registerCommand(Core core, String name, Object commandExecutor) {
        PluginCommand pluginCommand = core.getCommand(name);

        if (pluginCommand == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + name);
            return;
        }

        if (commandExecutor instanceof org.bukkit.command.CommandExecutor executor) {
            pluginCommand.setExecutor(executor);
        }

        if (commandExecutor instanceof org.bukkit.command.TabCompleter completer) {
            pluginCommand.setTabCompleter(completer);
        }
    }
}