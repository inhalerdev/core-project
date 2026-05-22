package net.mineacle.core.enchant;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.enchant.command.EnchantCommand;
import org.bukkit.command.PluginCommand;

public final class EnchantModule extends Module {

    @Override
    public String name() {
        return "Enchant";
    }

    @Override
    public void enable(Core core) {
        EnchantCommand command = new EnchantCommand(core);
        PluginCommand enchant = core.getCommand("enchant");

        if (enchant == null) {
            core.getLogger().warning("Missing command in plugin.yml: enchant");
            return;
        }

        enchant.setExecutor(command);
        enchant.setTabCompleter(command);
    }

    @Override
    public void disable() {
    }
}
