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
import org.bukkit.scheduler.BukkitTask;

public final class SellModule extends Module {

    private static SellService sellService;

    private SellWorthPacketListener packetListener;
    private WorthGuiListener worthGuiListener;
    private BukkitTask marketTask;

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
        sellService.start();

        SellCommand command =
                new SellCommand(core, sellService);

        register(core, "sell", command);
        register(core, "worth", command);
        register(core, "sellmulti", command);

        core.getServer().getPluginManager().registerEvents(
                new SellGuiListener(core, sellService),
                core
        );
        worthGuiListener = new WorthGuiListener(
                core,
                sellService
        );
        core.getServer().getPluginManager().registerEvents(
                worthGuiListener,
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new SellMultiGuiListener(),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new ItemStackNormalizeListener(
                        core,
                        sellService
                ),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new SellWorthRefreshListener(core),
                core
        );

        marketTask = core.getServer()
                .getScheduler()
                .runTaskTimer(
                        core,
                        sellService::tick,
                        20L,
                        20L * 20L
                );

        Plugin protocolLib = core.getServer()
                .getPluginManager()
                .getPlugin("ProtocolLib");

        if (protocolLib != null && protocolLib.isEnabled()) {
            packetListener = new SellWorthPacketListener(
                    core,
                    sellService
            );
            ProtocolLibrary.getProtocolManager()
                    .addPacketListener(packetListener);
            core.getLogger().info(
                    "Sell worth hover lore enabled for "
                            + "player inventory and real storage"
            );
        } else {
            core.getLogger().warning(
                    "ProtocolLib not found — packet-only "
                            + "Worth lore is disabled"
            );
        }
    }

    @Override
    public void disable() {
        if (marketTask != null) {
            marketTask.cancel();
            marketTask = null;
        }

        if (packetListener != null) {
            ProtocolLibrary.getProtocolManager()
                    .removePacketListener(packetListener);
            packetListener = null;
        }

        if (worthGuiListener != null) {
            worthGuiListener.shutdown();
            worthGuiListener = null;
        }

        if (sellService != null) {
            sellService.shutdown();
            sellService = null;
        }
    }

    private void register(
            Core core,
            String commandName,
            CommandExecutor executor
    ) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: " + commandName
            );
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
