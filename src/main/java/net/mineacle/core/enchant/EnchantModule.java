package net.mineacle.core.enchant;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.PluginCommand;

public final class EnchantModule extends Module {

    @Override
    public String name() {
        return "Enchant";
    }

    @Override
    public void enable(Core core) {
        EnchantCommand enchantCommand = new EnchantCommand(core);
        EnchantInfoCommand enchantInfoCommand = new EnchantInfoCommand(core);

        PluginCommand enchant = core.getCommand("enchant");

        if (enchant != null) {
            enchant.setExecutor(enchantCommand);
            enchant.setTabCompleter(enchantCommand);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: enchant");
        }

        PluginCommand enchantInfo = core.getCommand("enchantinfo");

        if (enchantInfo != null) {
            enchantInfo.setExecutor(enchantInfoCommand);
            enchantInfo.setTabCompleter(enchantInfoCommand);
        } else {
            core.getLogger().warning("Missing command in plugin.yml: enchantinfo");
        }

        core.getServer().getPluginManager().registerEvents(new EnchantCommandListener(enchantCommand, enchantInfoCommand), core);
    }

    @Override
    public void disable() {
    }
}
