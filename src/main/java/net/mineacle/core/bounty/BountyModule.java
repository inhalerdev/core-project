package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.bounty.command.BountyCommand;
import net.mineacle.core.bounty.gui.BountyMainGui;
import net.mineacle.core.bounty.listener.BountyGuiListener;
import net.mineacle.core.bounty.listener.BountyListener;
import net.mineacle.core.bounty.listener.BountySearchInputListener;
import net.mineacle.core.bounty.service.BountyService;
import net.mineacle.core.bounty.service.YamlBountyRepository;
import org.bukkit.command.PluginCommand;

public final class BountyModule
        extends Module {

    private static BountyService bountyService;

    private BountySearchInputListener searchInputListener;

    @SuppressWarnings("unused")
    public static BountyService bountyService() {
        return bountyService;
    }

    @Override
    public String name() {
        return "Bounty";
    }

    @Override
    public void enable(
            Core core
    ) throws Exception {
        YamlBountyRepository repository =
                new YamlBountyRepository(core);
        BountyService service =
                new BountyService(
                        core,
                        repository
                );

        service.load();
        bountyService = service;

        PluginCommand pluginCommand =
                core.getCommand("bounty");

        if (pluginCommand == null) {
            bountyService = null;
            service.shutdown();

            throw new IllegalStateException(
                    "Missing command in plugin.yml: bounty"
            );
        }

        BountyCommand command =
                new BountyCommand(
                        core,
                        service
                );

        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        searchInputListener =
                new BountySearchInputListener(
                        core,
                        service
                );

        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new BountyListener(
                                core,
                                service
                        ),
                        core
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new BountyGuiListener(
                                core,
                                service,
                                searchInputListener
                        ),
                        core
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        searchInputListener,
                        core
                );
    }

    @Override
    public void disable() {
        if (searchInputListener != null) {
            searchInputListener.shutdown();
            searchInputListener = null;
        }

        BountyMainGui.clearAllState();

        BountyService service =
                bountyService;
        bountyService = null;

        if (service != null) {
            service.shutdown();
        }
    }
}
