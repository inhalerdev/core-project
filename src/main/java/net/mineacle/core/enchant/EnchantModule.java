package net.mineacle.core.enchant;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class EnchantModule extends Module {

    @Override
    public String name() {
        return "Enchant";
    }

    @Override
    public void enable(Core core) {
        EnchantCommand enchantCommand =
                new EnchantCommand(core);
        EnchantInfoCommand enchantInfoCommand =
                new EnchantInfoCommand(core);

        register(core, "enchant", enchantCommand);
        register(core, "enchantinfo", enchantInfoCommand);

        core.getServer().getPluginManager().registerEvents(
                new EnchantCommandListener(
                        enchantCommand,
                        enchantInfoCommand
                ),
                core
        );
    }

    @Override
    public void disable() {
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
