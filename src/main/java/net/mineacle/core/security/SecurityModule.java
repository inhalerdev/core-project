package net.mineacle.core.security;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.event.EventSubscription;
import net.luckperms.api.event.user.UserDataRecalculateEvent;
import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.security.command.SecurityCommand;
import net.mineacle.core.security.listener.SecurityListener;
import net.mineacle.core.security.service.SecurityService;
import org.bukkit.command.PluginCommand;

public final class SecurityModule extends Module {

    private SecurityService service;
    private EventSubscription<UserDataRecalculateEvent>
            permissionRefreshSubscription;

    @Override
    public String name() {
        return "Security";
    }

    @Override
    public void enable(Core core) {
        service = new SecurityService(core);

        SecurityCommand command = new SecurityCommand(service);
        PluginCommand pluginCommand = core.getCommand("mineaclesecurity");

        if (pluginCommand == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: mineaclesecurity"
            );
        }

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        core.getServer().getPluginManager().registerEvents(
                new SecurityListener(service),
                core
        );

        LuckPerms luckPerms = core.getServer()
                .getServicesManager()
                .load(LuckPerms.class);

        if (luckPerms == null) {
            throw new IllegalStateException(
                    "LuckPerms API unavailable despite hard dependency"
            );
        }

        SecurityService securityService = service;

        permissionRefreshSubscription = luckPerms
                .getEventBus()
                .subscribe(
                        core,
                        UserDataRecalculateEvent.class,
                        event -> securityService.queueCommandTreeRefresh(
                                event.getUser().getUniqueId()
                        )
                );
    }

    @Override
    public void disable() {
        if (permissionRefreshSubscription != null) {
            permissionRefreshSubscription.close();
            permissionRefreshSubscription = null;
        }

        if (service != null) {
            service.shutdown();
            service = null;
        }
    }
}
