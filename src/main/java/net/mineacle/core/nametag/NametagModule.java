package net.mineacle.core.nametag;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class NametagModule extends Module {

    private static NametagService service;

    @Override
    public String name() {
        return "Nametags";
    }

    @Override
    public void enable(Core core) {
        service = new NametagService(core);

        NametagCommand command = new NametagCommand(core, service);
        register(core, "mineaclenametags", command);

        core.getServer().getPluginManager().registerEvents(new NametagListener(core, service), core);

        long interval = Math.max(20L, service.updateIntervalSeconds() * 20L);
        core.getServer().getScheduler().runTaskTimer(core, service::refreshAll, 20L, interval);
        service.refreshAll();
    }

    @Override
    public void disable() {
        if (service != null) {
            service.clear();
        }

        service = null;
    }

    public static void refreshAll() {
        if (service != null) {
            service.refreshAll();
        }
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
