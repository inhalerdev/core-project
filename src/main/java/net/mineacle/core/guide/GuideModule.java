package net.mineacle.core.guide;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.guide.command.GuideCommand;
import net.mineacle.core.guide.listener.GuideMenuListener;
import net.mineacle.core.guide.service.GuideMenuService;
import org.bukkit.command.PluginCommand;

public final class GuideModule extends Module {

    private GuideMenuService service;

    @Override
    public String name() {
        return "Guide";
    }

    @Override
    public void enable(Core core) {
        service = new GuideMenuService(core);
        service.reload();

        GuideCommand command = new GuideCommand(core, service);
        register(core, "guide", command);
        register(core, "rules", command);

        core.getServer().getPluginManager().registerEvents(new GuideMenuListener(core, service), core);
    }

    @Override
    public void disable() {
        service = null;
    }

    private void register(Core core, String name, GuideCommand executor) {
        PluginCommand command = core.getCommand(name);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + name);
            return;
        }

        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
