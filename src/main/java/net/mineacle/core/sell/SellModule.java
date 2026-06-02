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
         * Do not register SellWorthPacketListener or SellWorthRefreshListener.
         * Worth text belongs inside /worth and Mineacle sell/worth GUIs only.
         * Global packet lore causes client-side stack mismatch after pickup.
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
