package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class BountyModule extends Module {

    private static BountyService bountyService;

    public static BountyService bountyService() {
        return bountyService;
    }

    @Override
    public String name() {
        return "Bounty";
    }

    @Override
    public void enable(Core core) {
        bountyService = new BountyService(core);

        BountyCommand command = new BountyCommand(core, bountyService);
        register(core, "bounty", command);

        core.getServer().getPluginManager().registerEvents(new BountyListener(core, bountyService), core);
        core.getServer().getPluginManager().registerEvents(new BountyGuiListener(core, bountyService), core);
    }

    @Override
    public void disable() {
        if (bountyService != null) {
            bountyService.save();
        }

        bountyService = null;
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
