package net.mineacle.core.doublejump;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.doublejump.command.DoubleJumpCommand;
import net.mineacle.core.doublejump.command.FlyCommand;
import net.mineacle.core.doublejump.command.SpeedCommand;
import net.mineacle.core.doublejump.listener.DoubleJumpListener;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class DoubleJumpModule extends Module {

    private DoubleJumpListener listener;

    @Override
    public String name() {
        return "DoubleJump";
    }

    @Override
    public void enable(Core core) {
        listener = new DoubleJumpListener(core);

        core.getServer().getPluginManager().registerEvents(
                listener,
                core
        );

        register(
                core,
                "doublejump",
                new DoubleJumpCommand(core, listener)
        );
        register(
                core,
                "fly",
                new FlyCommand(core, listener)
        );
        register(
                core,
                "speed",
                new SpeedCommand(core)
        );
    }

    @Override
    public void disable() {
        if (listener != null) {
            listener.disableAll();
            listener = null;
        }
    }

    private void register(
            Core core,
            String commandName,
            CommandExecutor executor
    ) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: " + commandName
            );
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
