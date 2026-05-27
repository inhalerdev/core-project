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

    private WarpTeleportService teleportService;

    @Override
    public String name() {
        return "Warps";
    }

    @Override
    public void enable(Core core) {
        WarpService warpService = new WarpService(core);
        warpService.reload();

        teleportService = new WarpTeleportService(core, warpService);

        WarpCommand warpCommand = new WarpCommand(core, warpService, teleportService);
        SetWarpCommand setWarpCommand = new SetWarpCommand(warpService);
        DelWarpCommand delWarpCommand = new DelWarpCommand(warpService);

        register(core, "warp", warpCommand);
        register(core, "setwarp", setWarpCommand);
        register(core, "delwarp", delWarpCommand);

        core.getServer().getPluginManager().registerEvents(new WarpGuiListener(core, warpService, teleportService), core);
    }

    @Override
    public void disable() {
        if (teleportService != null) {
            teleportService.cancelAll();
        }
    }

    private void register(Core core, String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = core.getCommand(name);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + name);
            return;
        }

        command.setExecutor(executor);

        if (executor instanceof org.bukkit.command.TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
