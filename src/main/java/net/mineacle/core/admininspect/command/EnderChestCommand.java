package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import org.bukkit.entity.Player;

public final class EnderChestCommand extends AbstractInspectCommand {

    public EnderChestCommand(Core core) {
        super(
                core,
                "mineacleadmin.echest",
                "mineacleadmin.echest.self",
                "&cUsage: /echest <player>",
                "&cYou cannot inspect your own ender chest",
                "opened the ender chest of"
        );
    }

    @Override
    protected void openInspection(Player viewer, Player target) {
        viewer.openInventory(target.getEnderChest());
    }
}
