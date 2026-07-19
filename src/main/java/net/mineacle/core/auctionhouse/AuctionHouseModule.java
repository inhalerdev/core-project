package net.mineacle.core.auctionhouse;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.command.AuctionHouseCommand;
import net.mineacle.core.auctionhouse.gui.AuctionHouseGuiListener;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.PluginCommand;

public final class AuctionHouseModule extends Module {

    private AuctionHouseService service;
    private AuctionHouseGuiListener listener;

    @Override
    public String name() {
        return "AuctionHouse";
    }

    @Override
    public void enable(Core core) {
        service = new AuctionHouseService(core);
        service.load();

        AuctionHouseCommand command = new AuctionHouseCommand(core, service);
        register(core, "auction", command);

        listener = new AuctionHouseGuiListener(core, service);
        core.getServer().getPluginManager().registerEvents(listener, core);
    }

    @Override
    public void disable() {
        if (listener != null) {
            listener.shutdown();
            listener = null;
        }

        if (service != null) {
            service.save();
            service = null;
        }
    }

    private void register(Core core, String commandName, AuctionHouseCommand executor) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + commandName);
            return;
        }

        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }
}
