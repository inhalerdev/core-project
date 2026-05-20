package net.mineacle.core.hide;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class HideModule extends Module {

    private HideService service;

    @Override
    public String name() {
        return "Hide";
    }

    @Override
    public void enable(Core core) {
        service = new HideService(core);
        HideCommand command = new HideCommand(core, service);

        register(core, "hide", command);
        core.getServer().getPluginManager().registerEvents(new HideListener(core, service), core);

        service.applyAll();
        NametagModule.refreshAll();
    }

    @Override
    public void disable() {
        if (service != null) {
            service.showAll();
        }

        service = null;
    }

    public static HideService service() {
        for (Module module : Core.instance().modules()) {
            if (module instanceof HideModule hideModule) {
                return hideModule.service;
            }
        }

        return null;
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
