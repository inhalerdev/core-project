package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import org.bukkit.entity.Player;

public final class InvSeeCommand extends AbstractInspectCommand {

    public InvSeeCommand(Core core) {
        super(
                core,
                "mineacleadmin.invsee",
                "mineacleadmin.invsee.self",
                "&cUsage: /invsee <player>",
                "&cYou cannot inspect yourself",
                "opened the inventory of"
        );
    }

    @Override
    protected void openInspection(Player viewer, Player target) {
        viewer.openInventory(target.getInventory());
    }
}
