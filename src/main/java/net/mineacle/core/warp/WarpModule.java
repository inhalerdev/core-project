package net.mineacle.core.warp;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.warp.command.DelWarpCommand;
import net.mineacle.core.warp.command.SetWarpCommand;
import net.mineacle.core.warp.command.WarpCommand;
import net.mineacle.core.warp.service.WarpService;
import net.mineacle.core.warp.service.WarpTeleportService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class WarpModule extends Module {

    private WarpService warpService;
    private WarpTeleportService teleportService;

    @Override
    public String name() {
        return "Warp";
    }

    @Override
    public void enable(Core core) {
        TeleportService sharedTeleport = core.teleports();

        if (sharedTeleport == null) {
            throw new IllegalStateException(
                    "Shared TeleportService is not initialized"
            );
        }

        warpService =
                new WarpService(core);
        teleportService =
                new WarpTeleportService(
                        warpService,
                        sharedTeleport
                );

        registerCommand(
                core,
                "warp",
                new WarpCommand(
                        warpService,
                        teleportService
                )
        );
        registerCommand(
                core,
                "setwarp",
                new SetWarpCommand(
                        warpService
                )
        );
        registerCommand(
                core,
                "delwarp",
                new DelWarpCommand(
                        warpService
                )
        );
    }

    @Override
    public void disable() {
        teleportService = null;
        warpService = null;
    }

    private void registerCommand(
            Core core,
            String name,
            Object executor
    ) {
        PluginCommand command =
                core.getCommand(name);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: "
                            + name
            );
        }

        if (executor
                instanceof CommandExecutor commandExecutor) {
            command.setExecutor(
                    commandExecutor
            );
        }

        if (executor
                instanceof TabCompleter tabCompleter) {
            command.setTabCompleter(
                    tabCompleter
            );
        }
    }

}
