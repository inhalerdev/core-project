package net.mineacle.core.doublejump;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.doublejump.command.DoubleJumpCommand;
import net.mineacle.core.doublejump.command.FlyCommand;
import net.mineacle.core.doublejump.listener.DoubleJumpListener;
import org.bukkit.command.PluginCommand;

public final class DoubleJumpModule extends Module {

    private DoubleJumpListener listener;

    @Override
    public String name() {
        return "DoubleJump";
    }

    @Override
    public void enable(Core core) {
        this.listener = new DoubleJumpListener(core);

        core.getServer().getPluginManager().registerEvents(listener, core);

        DoubleJumpCommand doubleJumpCommand = new DoubleJumpCommand(core, listener);
        register(core, "doublejump", doubleJumpCommand);

        FlyCommand flyCommand = new FlyCommand(core, listener);
        register(core, "fly", flyCommand);
    }

    @Override
    public void disable() {
        if (listener != null) {
            listener.disableAll();
        }

        listener = null;
    }

    private void register(Core core, String commandName, Object executor) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + commandName);
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