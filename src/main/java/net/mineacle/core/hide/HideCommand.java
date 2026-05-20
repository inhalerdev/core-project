package net.mineacle.core.hide;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.nametag.NametagModule;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public final class HideCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final HideService service;

    public HideCommand(Core core, HideService service) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only");
            return true;
        }

        if (!service.enabled()) {
            send(player, "&cHide is disabled");
            SoundService.guiError(player, core);
            return true;
        }

        if (!service.canUse(player)) {
            send(player, service.message("blocked", "&cMineacle+ is required to use /hide"));
            SoundService.guiError(player, core);
            return true;
        }

        boolean hidden = service.toggle(player);

        if (hidden) {
            send(player, service.message("enabled", "&#bbbbbbHidden mode &#ff88ffenabled"));
            SoundService.guiConfirm(player, core);
        } else {
            send(player, service.message("disabled", "&#bbbbbbHidden mode &cdisabled"));
            SoundService.guiCancel(player, core);
        }

        NametagModule.refreshAll();
        return true;
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message)));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return Collections.emptyList();
    }
}
