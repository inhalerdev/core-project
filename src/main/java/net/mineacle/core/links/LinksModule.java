package net.mineacle.core.links;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.links.command.LinksCommand;
import net.mineacle.core.links.gui.GuideRulesGui;
import net.mineacle.core.links.gui.GuideRulesGuiListener;
import net.mineacle.core.links.service.GuideRulesService;
import net.mineacle.core.links.service.LinksService;
import org.bukkit.command.PluginCommand;

public final class LinksModule extends Module {

    private LinksService linksService;
    private GuideRulesService guideRulesService;

    @Override
    public String name() {
        return "Links";
    }

    @Override
    public void enable(Core core) {
        linksService = new LinksService(core);
        guideRulesService = new GuideRulesService(core);

        GuideRulesGui guideRulesGui =
                new GuideRulesGui(core, guideRulesService);
        LinksCommand command = new LinksCommand(
                core,
                linksService,
                guideRulesGui
        );

        register(core, "guide", command);
        register(core, "rules", command);
        register(core, "discord", command);
        register(core, "store", command);
        register(core, "x", command);

        core.getServer().getPluginManager().registerEvents(
                new GuideRulesGuiListener(
                        core,
                        guideRulesGui,
                        linksService
                ),
                core
        );
    }

    @Override
    public void disable() {
        linksService = null;
        guideRulesService = null;
    }

    private void register(
            Core core,
            String commandName,
            LinksCommand executor
    ) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: " + commandName
            );
        }

        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
