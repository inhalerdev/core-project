package net.mineacle.core.admininspect;

import net.mineacle.core.Core;
import net.mineacle.core.admininspect.command.EnderChestCommand;
import net.mineacle.core.admininspect.command.InvSeeCommand;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.PluginCommand;

public final class AdminInspectModule extends Module {

    @Override
    public String name() {
        return "AdminInspect";
    }

    @Override
    public void enable(Core core) {
        InvSeeCommand invSeeCommand = new InvSeeCommand(core);
        EnderChestCommand enderChestCommand = new EnderChestCommand(core);

        register(core, "invsee", invSeeCommand);
        register(core, "echest", enderChestCommand);
    }

    @Override
    public void disable() {
    }

    private void register(Core core, String name, org.bukkit.command.CommandExecutor executor) {
        PluginCommand command = core.getCommand(name);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + name);
            return;
        }

        command.setExecutor(executor);

        if (executor instanceof org.bukkit.command.TabCompleter tabCompleter) {
            command.setTabCompleter(tabCompleter);
        }
    }
}
