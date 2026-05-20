package net.mineacle.core.links;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class LinksModule extends Module {

    private LinksService service;

    @Override
    public String name() {
        return "Links";
    }

    @Override
    public void enable(Core core) {
        service = new LinksService(core);
        LinksCommand command = new LinksCommand(core, service);

        register(core, "links", command);
        register(core, "discord", command);
        register(core, "store", command);
        register(core, "appeal", command);

        core.getServer().getPluginManager().registerEvents(new LinksGuiListener(core, service), core);
    }

    @Override
    public void disable() {
        service = null;
    }

    private void register(Core core, String commandName, CommandExecutor executor) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + commandName);
            return;
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
