package net.mineacle.core.tpa.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.tpa.gui.TpaTargetMenuGui;
import net.mineacle.core.tpa.service.TpaRequestType;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

@SuppressWarnings("unused")
public final class TpaTargetMenuListener implements Listener {

    private static final String PRIMARY = "&#8436FE";
    private static final String SECONDARY = "&#B078FF";
    private static final String BODY = "&#bbbbbb";

    private final Core core;
    private final TpaService tpaService;

    public TpaTargetMenuListener(Core core, TpaService tpaService) {
        this.core = core;
        this.tpaService = tpaService;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        String title = GuiText.plain(event.getView().title());

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
            tpaService.clearMenuTarget(player.getUniqueId());
            player.closeInventory();
            sendBoth(player, "&cTeleport request cancelled");
            SoundService.guiCancel(player, core);
            return;
        }

        if (rawSlot != TpaTargetMenuGui.CONFIRM_SLOT) {
            return;
        }

        Player target = tpaService.menuTarget(player.getUniqueId());
        tpaService.clearMenuTarget(player.getUniqueId());

        if (target == null || !target.isOnline()) {
            player.closeInventory();
            sendBoth(player, "&cThat player is no longer online");
            SoundService.guiError(player, core);
            return;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.closeInventory();
            sendBoth(player, "&cYou cannot teleport to yourself");
            SoundService.guiError(player, core);
            return;
        }

        player.closeInventory();
        sendRequest(player, target);
    }

    private void sendRequest(Player requester, Player target) {
        if (tpaService.createRequest(
                requester,
                target,
                TpaRequestType.TO_TARGET
        ) != TpaService.CreateResult.SUCCESS) {
            sendBoth(requester, "&cCould not send teleport request");
            SoundService.guiError(requester, core);
            return;
        }

        String requesterName = DisplayNames.displayName(requester);
        String targetName = DisplayNames.displayName(target);

        sendBoth(requester, BODY + "Teleport request sent to " + SECONDARY + targetName);
        SoundService.guiSelect(requester, core);

        target.sendMessage(requestMessage(
                BODY + requesterName + " " + SECONDARY + "wants to teleport to you",
                requester
        ));
        target.sendActionBar(actionBar(
                BODY + requesterName + " " + SECONDARY + "wants to teleport to you"
        ));
        SoundService.teleportReceived(target, core);
    }

    private Component requestMessage(String message, Player requester) {
        return Component.text()
                .append(legacy(message))
                .append(Component.newline())
                .append(legacy("&a[Accept]").clickEvent(ClickEvent.runCommand("/tpaccept")))
                .append(legacy(" " + BODY + "/ "))
                .append(legacy("&c[Deny]").clickEvent(ClickEvent.runCommand("/tpdeny")))
                .append(legacy(" " + BODY + "/ "))
                .append(legacy(PRIMARY + "[View]").clickEvent(
                        ClickEvent.runCommand("/tpamenu " + DisplayNames.commandDisplayName(requester))
                ))
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
