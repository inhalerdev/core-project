package net.mineacle.core.tpa.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.common.teleport.TeleportService;
import net.mineacle.core.tpa.gui.TpaRequestGui;
import net.mineacle.core.tpa.service.TpaRequest;
import net.mineacle.core.tpa.service.TpaRequestType;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

@SuppressWarnings("unused")
public final class TpaGuiListener
        implements Listener {

    private static final String SECONDARY =
            "&#B078FF";
    private static final String BODY =
            "&#bbbbbb";

    private final Core core;
    private final TpaService tpaService;
    private final TeleportService teleportService;

    public TpaGuiListener(
            Core core,
            TpaService tpaService,
            TeleportService teleportService
    ) {
        this.core = core;
        this.tpaService = tpaService;
        this.teleportService = teleportService;
    }

    @EventHandler
    public void onInventoryClick(
            InventoryClickEvent event
    ) {
        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        if (!event.getView()
                .title()
                .equals(TpaRequestGui.TITLE)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        if (slot == TpaRequestGui.DENY_SLOT) {
            deny(player);
            return;
        }

        if (slot == TpaRequestGui.ACCEPT_SLOT) {
            accept(player);
        }
    }

    private void accept(Player target) {
        TpaRequest request =
                tpaService.getRequest(
                        target.getUniqueId()
                );

        if (request == null) {
            target.closeInventory();
            error(
                    target,
                    "&cYou have no pending teleport requests"
            );
            return;
        }

        Player requester =
                tpaService.requester(request);

        if (requester == null
                || !requester.isOnline()) {
            tpaService.removeRequest(
                    target.getUniqueId()
            );
            target.closeInventory();
            error(
                    target,
                    "&cThat player is no longer online"
            );
            return;
        }

        Player traveler = request.type()
                == TpaRequestType.TO_TARGET
                ? requester
                : target;
        Player destination = request.type()
                == TpaRequestType.TO_TARGET
                ? target
                : requester;

        if (teleportService.isActive(traveler)) {
            target.closeInventory();
            error(
                    target,
                    "&cThat teleport cannot start right now"
            );

            if (!traveler.getUniqueId().equals(
                    target.getUniqueId()
            )) {
                error(
                        traveler,
                        "&cYou already have a teleport in progress"
                );
            }
            return;
        }

        if (!teleportService.beginPlayer(
                traveler,
                destination
        )) {
            target.closeInventory();
            return;
        }

        tpaService.removeRequest(
                target.getUniqueId()
        );
        target.closeInventory();
        SoundService.guiSelect(
                target,
                core
        );

        sendBoth(
                requester,
                "&aTeleport request accepted "
                        + BODY
                        + "by "
                        + playerName(target)
        );
        sendBoth(
                target,
                "&aTeleport request accepted "
                        + BODY
                        + "from "
                        + playerName(requester)
        );
    }

    private void deny(Player target) {
        TpaRequest request =
                tpaService.removeRequest(
                        target.getUniqueId()
                );

        if (request == null) {
            target.closeInventory();
            error(
                    target,
                    "&cYou have no pending teleport requests"
            );
            return;
        }

        Player requester =
                tpaService.requester(request);

        target.closeInventory();
        sendBoth(
                target,
                "&cTeleport request denied"
        );
        SoundService.guiCancel(
                target,
                core
        );

        if (requester != null
                && requester.isOnline()) {
            sendBoth(
                    requester,
                    playerName(target)
                            + " &cdenied your teleport request"
            );
            SoundService.guiCancel(
                    requester,
                    core
            );
        }

        MenuHistory.clear(target);
    }

    private String playerName(Player player) {
        return SECONDARY
                + DisplayNames.displayName(player);
    }

    private void error(
            Player player,
            String message
    ) {
        sendBoth(player, message);
        SoundService.guiError(
                player,
                core
        );
    }

    private void sendBoth(
            Player player,
            String message
    ) {
        String colored =
                TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(
                component(colored)
        );
    }

    private Component component(String message) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(message)
                );
    }
}
