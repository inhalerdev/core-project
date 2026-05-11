package net.mineacle.core.spawn;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.spawn.command.SpawnCommand;
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
        this.spawnService = new SpawnService(core);
        this.teleportService = new SpawnTeleportService(spawnService);

        SpawnCommand command = new SpawnCommand(spawnService);

        PluginCommand spawn = core.getCommand("spawn");
        if (spawn != null) {
            spawn.setExecutor(command);
            spawn.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: spawn");
        }

        PluginCommand lobby = core.getCommand("lobby");
        if (lobby != null) {
            lobby.setExecutor(command);
            lobby.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: lobby");
        }

        core.getServer().getPluginManager().registerEvents(
                new SpawnGuiListener(spawnService, teleportService),
                core
        );

        core.getServer().getPluginManager().registerEvents(
                new SpawnVoidListener(spawnService),
                core
        );

        core.getServer().getPluginManager().registerEvents(
                new SpawnJoinQuitListener(spawnService),
                core
        );
    }

    @Override
    public void disable() {
    }
}