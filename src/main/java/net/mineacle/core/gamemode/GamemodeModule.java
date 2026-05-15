package net.mineacle.core.gamemode;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.gamemode.command.GamemodeShortcutCommand;
import org.bukkit.GameMode;
import org.bukkit.command.PluginCommand;

public final class GamemodeModule extends Module {

    @Override
    public String name() {
        return "Gamemode";
    }

    @Override
    public void enable(Core core) {
        register(core, "gmc", GameMode.CREATIVE);
        register(core, "gms", GameMode.SURVIVAL);
        register(core, "gmsp", GameMode.SPECTATOR);
        register(core, "gma", GameMode.ADVENTURE);
    }

    @Override
    public void disable() {
    }

    private void register(Core core, String commandName, GameMode gameMode) {
        PluginCommand command = core.getCommand(commandName);

        if (command == null) {
            core.getLogger().warning("Missing command in plugin.yml: " + commandName);
            return;
        }

        command.setExecutor(new GamemodeShortcutCommand(core, gameMode));
    }
}
