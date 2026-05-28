package net.mineacle.core.warps;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.warps.command.DelWarpCommand;
import net.mineacle.core.warps.command.SetWarpCommand;
import net.mineacle.core.warps.command.WarpCommand;
import net.mineacle.core.warps.service.WarpService;
import net.mineacle.core.warps.service.WarpTeleportService;
import org.bukkit.command.PluginCommand;

public final class WarpModule extends Module {

    private WarpService warpService;
    private WarpTeleportService teleportService;

    @Override
    public String name() {
        return "Warps";
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
