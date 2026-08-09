package net.mineacle.core.tpa.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.tpa.command.TpaMenuCommand;
import net.mineacle.core.tpa.gui.TpaTargetMenuGui;
import net.mineacle.core.tpa.service.TpaRequestType;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.UUID;

public final class TpaTargetMenuListener implements Listener {

    private final Core core;
    private final TpaService tpaService;

    public TpaTargetMenuListener(Core core, TpaService tpaService) {
        this.core = core;
        this.tpaService = tpaService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!TpaTargetMenuGui.isTitle(title)) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        if (rawSlot == TpaTargetMenuGui.CANCEL_SLOT) {
            player.removeMetadata(TpaMenuCommand.META_TARGET, core);
            player.closeInventory();
            sendBoth(player, "&cTeleport request cancelled");
            SoundService.guiCancel(player, core);
            return;
        }

        if (rawSlot != TpaTargetMenuGui.CONFIRM_SLOT) {
            return;
        }

        Player target = target(player);

        if (target == null) {
            player.removeMetadata(TpaMenuCommand.META_TARGET, core);
            player.closeInventory();
            sendBoth(player, "&cThat player is no longer online");
            SoundService.guiError(player, core);
            return;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.removeMetadata(TpaMenuCommand.META_TARGET, core);
            player.closeInventory();
            sendBoth(player, "&cYou cannot teleport to yourself");
            SoundService.guiError(player, core);
            return;
        }

        player.removeMetadata(TpaMenuCommand.META_TARGET, core);
        player.closeInventory();
        sendRequest(player, target);
    }

    private Player target(Player player) {
        if (!player.hasMetadata(TpaMenuCommand.META_TARGET)) {
            return null;
        }

        String raw = player.getMetadata(TpaMenuCommand.META_TARGET).get(0).asString();

        try {
            return Bukkit.getPlayer(UUID.fromString(raw));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private void sendRequest(Player requester, Player target) {
        if (!tpaService.createRequest(requester, target, TpaRequestType.TO_TARGET)) {
            sendBoth(requester, "&cCould not send teleport request");
            SoundService.guiError(requester, core);
            return;
        }

        String requesterName = DisplayNames.displayName(requester);
        String targetName = DisplayNames.displayName(target);

        sendBoth(requester, "&#bbbbbbTeleport request sent to &#ff88ff" + targetName);
        SoundService.guiSelect(requester, core);

        target.sendMessage(requestMessage(
                "&#bbbbbb" + requesterName + " &#ff88ffwants to teleport to you",
                requester
        ));

        target.sendActionBar(actionBar("&#bbbbbb" + requesterName + " &#ff88ffwants to teleport to you"));
        SoundService.teleportReceived(target, core);

        core.getServer().getScheduler().runTaskLater(core, () -> {
            if (tpaService.getRequest(target.getUniqueId()) == null) {
                return;
            }

            tpaService.removeRequest(target.getUniqueId());

            if (requester.isOnline()) {
                requester.sendMessage(TextColor.color("&cTeleport request expired"));
                SoundService.guiError(requester, core);
            }

            if (target.isOnline()) {
                target.sendMessage(TextColor.color("&cTeleport request expired"));
                SoundService.guiError(target, core);
            }
        }, tpaService.timeoutSeconds() * 20L);
    }

    private Component requestMessage(String message, Player requester) {
        return Component.text()
                .append(legacy(message))
                .append(Component.newline())
                .append(legacy("&a[Accept]").clickEvent(ClickEvent.runCommand("/tpaccept")))
                .append(legacy(" &#bbbbbb/ "))
                .append(legacy("&c[Deny]").clickEvent(ClickEvent.runCommand("/tpdeny")))
                .append(legacy(" &#bbbbbb/ "))
                .append(legacy("&dView").clickEvent(ClickEvent.runCommand("/tpamenu " + DisplayNames.commandDisplayName(requester))))
                .build();
    }

    private void sendBoth(Player player, String message) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }

    private Component legacy(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
