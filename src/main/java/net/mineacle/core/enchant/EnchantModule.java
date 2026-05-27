package net.mineacle.core.enchant;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.enchant.command.EnchantCommand;
import net.mineacle.core.enchant.command.EnchantInfoCommand;
import org.bukkit.command.PluginCommand;

public final class EnchantModule extends Module {

    @Override
    public String name() {
        return "Enchant";
    }

    @Override
    public void enable(Core core) {
        EnchantCommand enchantCommand = new EnchantCommand(core);
        PluginCommand enchant = core.getCommand("enchant");

        if (enchant == null) {
            core.getLogger().warning("Missing command in plugin.yml: enchant");
        } else {
            enchant.setExecutor(enchantCommand);
            enchant.setTabCompleter(enchantCommand);
        }

        EnchantInfoCommand enchantInfoCommand = new EnchantInfoCommand(core);
        PluginCommand enchantInfo = core.getCommand("enchantinfo");

        if (enchantInfo == null) {
            core.getLogger().warning("Missing command in plugin.yml: enchantinfo");
        } else {
            enchantInfo.setExecutor(enchantInfoCommand);
            enchantInfo.setTabCompleter(enchantInfoCommand);
        }
    }

    @Override
    public void disable() {
    }
}
