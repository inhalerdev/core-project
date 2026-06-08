package net.mineacle.core.admininspect;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.command.EnderChestCommand;
import net.mineacle.core.admininspect.command.InvSeeCommand;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class AdminInspectModule extends Module {

    @Override
    public String name() {
        return "AdminInspect";
    }

    @Override
    public void enable(Core core) {
        register(core, "invsee", new InvSeeCommand(core));
        register(core, "echest", new EnderChestCommand(core));
    }

    @Override
    public void disable() {
    }

    private void register(Core core, String commandName, CommandExecutor executor) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + commandName);
            return;
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter tabCompleter) {
            command.setTabCompleter(tabCompleter);
        }
    }
}
