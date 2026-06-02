package net.mineacle.core.sell;

import com.comphenix.protocol.ProtocolLibrary;
import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.sell.command.SellCommand;
import net.mineacle.core.sell.listener.ItemStackNormalizeListener;
import net.mineacle.core.sell.listener.SellGuiListener;
import net.mineacle.core.sell.listener.SellMultiGuiListener;
import net.mineacle.core.sell.listener.SellWorthPacketListener;
import net.mineacle.core.sell.listener.SellWorthRefreshListener;
import net.mineacle.core.sell.listener.WorthGuiListener;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.plugin.Plugin;

public final class SellModule extends Module {

    private static SellService sellService;
    private SellWorthPacketListener packetListener;

    public static SellService sellService() {
        return sellService;
    }

    @Override
    public String name() {
        return "Sell";
    }

    @Override
    public void enable(Core core) {
        sellService = new SellService(core);

        SellCommand command = new SellCommand(core, sellService);
        register(core, "sell", command);
        register(core, "worth", command);
        register(core, "sellmulti", command);

        core.getServer().getPluginManager().registerEvents(new SellGuiListener(core, sellService), core);
        core.getServer().getPluginManager().registerEvents(new WorthGuiListener(core, sellService), core);
        core.getServer().getPluginManager().registerEvents(new SellMultiGuiListener(), core);
        core.getServer().getPluginManager().registerEvents(new ItemStackNormalizeListener(core), core);
        core.getServer().getPluginManager().registerEvents(new SellWorthRefreshListener(core), core);

        Plugin protocolLib = core.getServer().getPluginManager().getPlugin("ProtocolLib");
        if (protocolLib != null && protocolLib.isEnabled()) {
            packetListener = new SellWorthPacketListener(core, sellService);
            ProtocolLibrary.getProtocolManager().addPacketListener(packetListener);
            core.getLogger().info("Sell worth hover lore enabled for normal inventory and storage only");
        } else {
            core.getLogger().warning("ProtocolLib not found; worth hover lore is disabled outside Mineacle GUIs");
        }
    }

    @Override
    public void disable() {
        if (packetListener != null) {
            ProtocolLibrary.getProtocolManager().removePacketListener(packetListener);
            packetListener = null;
        }

        if (sellService != null) {
            sellService.save();
        }

        sellService = null;
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
