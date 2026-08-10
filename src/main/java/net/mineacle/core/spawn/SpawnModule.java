package net.mineacle.core.spawn;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.spawn.command.SpawnCommand;
import net.mineacle.core.spawn.listener.OriginsVoidListener;
import net.mineacle.core.spawn.listener.SpawnGuiListener;
import net.mineacle.core.spawn.listener.SpawnJoinQuitListener;
import net.mineacle.core.spawn.listener.SpawnVoidListener;
import net.mineacle.core.spawn.service.SpawnService;
import net.mineacle.core.spawn.service.SpawnTeleportService;
import org.bukkit.command.PluginCommand;

public final class SpawnModule extends Module {

    private SpawnService spawnService;
    private SpawnTeleportService teleportService;

    @Override
    public String name() {
        return "Spawn";
    }

    @Override
    public void enable(Core core) {
        TeleportService sharedTeleport = core.teleports();

        if (sharedTeleport == null) {
            throw new IllegalStateException(
                    "Shared TeleportService is not initialized"
            );
        }

        spawnService =
                new SpawnService(core);
        teleportService =
                new SpawnTeleportService(
                        spawnService,
                        sharedTeleport
                );

        SpawnCommand command =
                new SpawnCommand(
                        spawnService
                );

        register(
                core,
                "spawn",
                command
        );
        register(
                core,
                "lobby",
                command
        );

        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new SpawnGuiListener(
                                spawnService,
                                teleportService
                        ),
                        core
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new SpawnVoidListener(
                                spawnService,
                                teleportService
                        ),
                        core
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new OriginsVoidListener(
                                spawnService,
                                teleportService
                        ),
                        core
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new SpawnJoinQuitListener(
                                spawnService
                        ),
                        core
                );
    }

    @Override
    public void disable() {
        teleportService = null;
        spawnService = null;
    }

    private void register(
            Core core,
            String commandName,
            SpawnCommand executor
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
        command.setTabCompleter(executor);
    }

}
