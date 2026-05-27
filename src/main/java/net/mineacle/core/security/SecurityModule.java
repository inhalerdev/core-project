package net.mineacle.core.security;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.security.command.SecurityCommand;
import net.mineacle.core.security.listener.SecurityListener;
import net.mineacle.core.security.service.SecurityService;
import org.bukkit.command.PluginCommand;

public final class SecurityModule extends Module {

    private SecurityService service;

    @Override
    public String name() {
        return "Security";
    }

    @Override
    public void enable(Core core) {
        service = new SecurityService(core);

        SecurityCommand command = new SecurityCommand(service);
        PluginCommand pluginCommand = core.getCommand("mineaclesecurity");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: mineaclesecurity");
        }

        core.getServer().getPluginManager().registerEvents(new SecurityListener(service), core);
    }

    @Override
    public void disable() {
        service = null;
    }
}
