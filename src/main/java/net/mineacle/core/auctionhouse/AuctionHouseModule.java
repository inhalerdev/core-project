package net.mineacle.core.auctionhouse;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.command.AuctionHouseCommand;
import net.mineacle.core.auctionhouse.gui.AuctionHouseGuiListener;
import net.mineacle.core.auctionhouse.listener.AuctionTransactionRecoveryListener;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class AuctionHouseModule extends Module {

    private AuctionHouseService service;
    private AuctionHouseGuiListener guiListener;
    private AuctionTransactionRecoveryListener recoveryListener;

    @Override
    public String name() {
        return "AuctionHouse";
    }

    @Override
    public void enable(Core core) {
        service = new AuctionHouseService(core);
        service.load();

        AuctionHouseCommand command =
                new AuctionHouseCommand(
                        core,
                        service
                );
        register(
                core,
                command
        );

        recoveryListener =
                new AuctionTransactionRecoveryListener(
                        core,
                        service
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        recoveryListener,
                        core
                );

        guiListener =
                new AuctionHouseGuiListener(
                        core,
                        service
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        guiListener,
                        core
                );
    }

    @Override
    public void disable() {
        if (recoveryListener != null) {
            recoveryListener.shutdown();
            recoveryListener = null;
        }

        if (guiListener != null) {
            guiListener.shutdown();
            guiListener = null;
        }

        if (service != null) {
            service.shutdown();
            service = null;
        }
    }

    private void register(
            Core core,
            CommandExecutor executor
    ) {
        PluginCommand command =
                core.getCommand("auction");

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: auction"
            );
        }

        command.setExecutor(executor);

        if (executor
                instanceof TabCompleter completer) {
            command.setTabCompleter(
                    completer
            );
        }
    }
}
