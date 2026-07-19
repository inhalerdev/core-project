package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.bounty.command.BountyCommand;
import org.bukkit.command.PluginCommand;

public final class BountyModule extends Module {

    private static BountyService bountyService;

    private BountySearchInputListener searchInputListener;

    public static BountyService bountyService() {
        return bountyService;
    }

    @Override
    public String name() {
        return "Bounty";
    }

    @Override
    public void enable(Core core) throws Exception {
        YamlBountyRepository repository = new YamlBountyRepository(core);
        bountyService = new BountyService(core, repository);
        bountyService.load();

        PluginCommand pluginCommand = core.getCommand("bounty");

        if (pluginCommand == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: bounty"
            );
        }

        BountyCommand command = new BountyCommand(core, bountyService);
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);

        searchInputListener = new BountySearchInputListener(
                core,
                bountyService
        );

        core.getServer().getPluginManager().registerEvents(
                new BountyListener(core, bountyService),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new BountyGuiListener(
                        core,
                        bountyService,
                        searchInputListener
                ),
                core
        );
        core.getServer().getPluginManager().registerEvents(
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

        if (bountyService != null) {
            bountyService.shutdown();
            bountyService = null;
        }
    }
}
