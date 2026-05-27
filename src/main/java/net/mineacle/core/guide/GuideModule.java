package net.mineacle.core.guide;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.guide.command.GuideCommand;
import net.mineacle.core.guide.listener.GuideMenuListener;
import net.mineacle.core.guide.service.GuideMenuService;
import org.bukkit.command.PluginCommand;

public final class GuideModule implements Module {

    private GuideMenuService service;
    private GuideMenuListener listener;

    @Override
    public String name() {
        return "Guide";
    }

    @Override
    public void enable(Core core) {
        this.service = new GuideMenuService(core);
        this.listener = new GuideMenuListener(core, service);

        GuideCommand guideCommand = new GuideCommand(core, service, "guide");
        GuideCommand rulesCommand = new GuideCommand(core, service, "rules");

        PluginCommand guide = core.getCommand("guide");
        if (guide != null) {
            guide.setExecutor(guideCommand);
            guide.setTabCompleter(guideCommand);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: guide");
        }

        PluginCommand rules = core.getCommand("rules");
        if (rules != null) {
            rules.setExecutor(rulesCommand);
            rules.setTabCompleter(rulesCommand);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: rules");
        }

        core.getServer().getPluginManager().registerEvents(listener, core);
    }

    @Override
    public void disable() {
        if (service != null) {
            service.clearSessions();
        }
    }
}
