package net.mineacle.core.links;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.links.command.LinksCommand;
import net.mineacle.core.links.listener.LinksCommandListener;
import org.bukkit.command.PluginCommand;

public final class LinksModule extends Module {

    private LinksCommand command;

    @Override
    public String name() {
        return "Links";
    }

    @Override
    public void enable(Core core) {
        command = new LinksCommand(core);

        register(core, "links");
        register(core, "guide");
        register(core, "rules");
        register(core, "discord");
        register(core, "store");
        register(core, "x");

        core.getServer().getPluginManager().registerEvents(new LinksCommandListener(command), core);
    }

    @Override
    public void disable() {
        command = null;
    }

    private void register(Core core, String commandName) {
        PluginCommand pluginCommand = core.getCommand(commandName);

        if (pluginCommand == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + commandName);
            return;
        }

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }
}
