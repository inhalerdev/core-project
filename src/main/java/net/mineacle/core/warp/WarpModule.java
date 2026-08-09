package net.mineacle.core.warp;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.warp.command.DelWarpCommand;
import net.mineacle.core.warp.command.SetWarpCommand;
import net.mineacle.core.warp.command.WarpCommand;
import net.mineacle.core.warp.service.WarpService;
import net.mineacle.core.warp.service.WarpTeleportService;
import org.bukkit.command.PluginCommand;

public final class WarpModule extends Module {

    private WarpService warpService;
    private WarpTeleportService teleportService;

    @Override
    public String name() {
        return "Warp";
    }

    @Override
    public void enable(Core core) {
        warpService = new WarpService(core);
        teleportService = new WarpTeleportService(core, warpService);

        registerCommand(core, "warp", new WarpCommand(warpService, teleportService));
        registerCommand(core, "setwarp", new SetWarpCommand(warpService));
        registerCommand(core, "delwarp", new DelWarpCommand(warpService));
    }

    @Override
    public void disable() {
        if (teleportService != null) {
            teleportService.cancelAll();
        }
    }

    private void registerCommand(Core core, String name, Object executor) {
        PluginCommand command = core.getCommand(name);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + name);
            return;
        }

        if (executor instanceof org.bukkit.command.CommandExecutor commandExecutor) {
            command.setExecutor(commandExecutor);
        }

        if (executor instanceof org.bukkit.command.TabCompleter tabCompleter) {
            command.setTabCompleter(tabCompleter);
        }
    }
}
