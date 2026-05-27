package net.mineacle.core.warps;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.warps.command.DelWarpCommand;
import net.mineacle.core.warps.command.SetWarpCommand;
import net.mineacle.core.warps.command.WarpCommand;
import net.mineacle.core.warps.listener.WarpGuiListener;
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
        this.teleportService = new WarpTeleportService(warpService);

        WarpCommand warpCommand = new WarpCommand(warpService, teleportService);
        PluginCommand warp = core.getCommand("warp");
        if (warp != null) {
            warp.setExecutor(warpCommand);
            warp.setTabCompleter(warpCommand);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: warp");
        }

        SetWarpCommand setWarpCommand = new SetWarpCommand(warpService);
        PluginCommand setWarp = core.getCommand("setwarp");
        if (setWarp != null) {
            setWarp.setExecutor(setWarpCommand);
            setWarp.setTabCompleter(setWarpCommand);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: setwarp");
        }

        DelWarpCommand delWarpCommand = new DelWarpCommand(warpService);
        PluginCommand delWarp = core.getCommand("delwarp");
        if (delWarp != null) {
            delWarp.setExecutor(delWarpCommand);
            delWarp.setTabCompleter(delWarpCommand);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: delwarp");
        }

        core.getServer().getPluginManager().registerEvents(new WarpGuiListener(warpService, teleportService), core);
    }

    @Override
    public void disable() {
    }
}
