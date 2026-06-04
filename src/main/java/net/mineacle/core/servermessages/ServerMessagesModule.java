package net.mineacle.core.servermessages;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.servermessages.command.ServerControlCommand;
import net.mineacle.core.servermessages.listener.ServerLoginListener;
import net.mineacle.core.servermessages.service.ServerMessageService;
import org.bukkit.command.PluginCommand;

public final class ServerMessagesModule extends Module {

    private ServerMessageService service;

    @Override
    public String name() {
        return "ServerMessages";
    }

    @Override
    public void enable(Core core) {
        core.saveResource("servermessages.yml", false);

        this.service = new ServerMessageService(core);
        ServerControlCommand command = new ServerControlCommand(core, service);

        register(core, "mineacleservermessages", command);
        register(core, "mineaclemaintenance", command);
        register(core, "mrestart", command);
        register(core, "mstop", command);

        core.getServer().getPluginManager().registerEvents(new ServerLoginListener(service), core);
    }

    @Override
    public void disable() {
        if (service != null) {
            service.save();
            service = null;
        }
    }

    private void register(Core core, String name, ServerControlCommand command) {
        PluginCommand pluginCommand = core.getCommand(name);
        if (pluginCommand == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + name);
            return;
        }

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
    }
}
