package net.mineacle.core.admininspect.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

abstract class AbstractInspectCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final String permission;
    private final String selfPermission;
    private final String usage;
    private final String selfDeniedMessage;
    private final String auditAction;

    protected AbstractInspectCommand(
            Core core,
            String permission,
            String selfPermission,
            String usage,
            String selfDeniedMessage,
            String auditAction
    ) {
        this.core = core;
        this.permission = permission;
        this.selfPermission = selfPermission;
        this.usage = usage;
        this.selfDeniedMessage = selfDeniedMessage;
        this.auditAction = auditAction;
    }

    @Override
    public final boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!viewer.hasPermission(permission)) {
            fail(viewer, core.getMessage("general.no-permission"));
            return true;
        }

        if (args.length != 1) {
            fail(viewer, TextColor.color(usage));
            return true;
        }

        Player target = DisplayNames.resolveOnline(args[0]);

        if (target == null || !target.isOnline()) {
            fail(viewer, TextColor.color("&cThat player is not online"));
            return true;
        }

        if (target.getUniqueId().equals(viewer.getUniqueId())
                && !viewer.hasPermission(selfPermission)) {
            fail(viewer, TextColor.color(selfDeniedMessage));
            return true;
        }

        openInspection(viewer, target);
        audit(viewer, target);
        return true;
    }

    @Override
    public final List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player viewer)
                || !viewer.hasPermission(permission)
                || args.length != 1) {
            return List.of();
        }

        boolean includeSelf = viewer.hasPermission(selfPermission);
        List<String> completions = new ArrayList<>(
                PlayerTabComplete.onlinePlayers(viewer, args[0], includeSelf)
        );
        completions.sort(String.CASE_INSENSITIVE_ORDER);
        return completions;
    }

    protected abstract void openInspection(Player viewer, Player target);

    private void fail(Player player, String message) {
        player.sendMessage(message);
        SoundService.guiError(player, core);
    }

    private void audit(Player viewer, Player target) {
        core.getLogger().info(
                viewer.getName()
                        + " (" + viewer.getUniqueId() + ") "
                        + auditAction + " "
                        + target.getName()
                        + " (" + target.getUniqueId() + ")"
        );
    }
}
