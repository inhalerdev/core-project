package net.mineacle.core.punish;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.punish.command.BanMenuCommand;
import net.mineacle.core.punish.listener.PunishGuiListener;
import net.mineacle.core.punish.service.PunishService;
import org.bukkit.command.PluginCommand;

public final class PunishModule extends Module {
    private PunishService service;

    @Override
    public String name() {
        return "Punish";
    }

    @Override
    public void enable(Core core) {
        service = new PunishService(core);
        BanMenuCommand command = new BanMenuCommand(service);
        PluginCommand pluginCommand = core.getCommand("banmenu");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: banmenu");
        }
        core.getServer().getPluginManager().registerEvents(new PunishGuiListener(service), core);
    }

    @Override
    public void disable() {
        service = null;
    }
}
