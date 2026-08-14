package net.mineacle.core.baltop;

import net.mineacle.core.Core;
import net.mineacle.core.baltop.command.BalTopCommand;
import net.mineacle.core.baltop.listener.BalTopGuiListener;
import net.mineacle.core.baltop.service.BalTopLeaderboardCache;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import org.bukkit.command.PluginCommand;

public final class BalTopModule extends Module {

    private BalTopGuiListener listener;
    private BalTopLeaderboardCache leaderboardCache;

    @Override
    public String name() {
        return "BalTop";
    }

    @Override
    public void enable(
            Core core
    ) {
        EconomyService economyService =
                EconomyModule.economyService();

        if (economyService == null) {
            core.getLogger().warning(
                    "BalTop could not enable because EconomyService is not loaded"
            );
            return;
        }

        leaderboardCache =
                new BalTopLeaderboardCache(
                        economyService
                );
        BalTopCommand command =
                new BalTopCommand(
                        core,
                        economyService,
                        leaderboardCache
                );
        PluginCommand balTop =
                core.getCommand("baltop");

        if (balTop == null) {
            core.getLogger().warning(
                    "Missing command in plugin.yml: baltop"
            );
            leaderboardCache = null;
            return;
        }

        balTop.setExecutor(command);
        balTop.setTabCompleter(command);

        listener =
                new BalTopGuiListener(
                        core,
                        economyService,
                        leaderboardCache
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        listener,
                        core
                );
    }

    @Override
    public void disable() {
        if (listener != null) {
            listener.shutdown();
            listener = null;
        }

        if (leaderboardCache != null) {
            leaderboardCache.clear();
            leaderboardCache = null;
        }
    }
}
