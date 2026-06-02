package net.mineacle.core.sell;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.sell.command.SellCommand;
import net.mineacle.core.sell.listener.ItemStackNormalizeListener;
import net.mineacle.core.sell.listener.SellGuiListener;
import net.mineacle.core.sell.listener.SellMultiGuiListener;
import net.mineacle.core.sell.listener.WorthGuiListener;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class SellModule extends Module {

    private static SellService sellService;

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

        /*
         * Do NOT register SellWorthPacketListener or SellWorthRefreshListener.
         *
         * The packet listener adds worth lore to normal player inventory packets.
         * That makes some items appear with worth lore and some without depending on
         * inventory view/raw slot. It also makes picked-up stacks and existing stacks
         * behave like different client-side items.
         *
         * Worth/price text belongs only in /worth, /sell, and /sellmulti GUI display
         * items. Normal player inventory items must stay clean so they combine.
         */
        core.getLogger().info("Sell worth packet lore disabled; item stack normalizer enabled");
    }

    @Override
    public void disable() {
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
