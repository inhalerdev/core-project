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
        this.warpService = new WarpService(core);
        this.warpService.reload();
        this.teleportService = new WarpTeleportService(core, warpService);

        WarpCommand warpCommand = new WarpCommand(warpService, teleportService);
        registerCommand(core, "warp", warpCommand);

        SetWarpCommand setWarpCommand = new SetWarpCommand(warpService);
        registerCommand(core, "setwarp", setWarpCommand);

        DelWarpCommand delWarpCommand = new DelWarpCommand(warpService);
        registerCommand(core, "delwarp", delWarpCommand);
    }

    @Override
    public void disable() {
        if (teleportService != null) {
            teleportService.cancelAll();
        }

        warpService = null;
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
