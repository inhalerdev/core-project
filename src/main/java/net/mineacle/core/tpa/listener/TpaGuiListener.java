package net.mineacle.core.tpa.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.service.TeleportService;
import net.mineacle.core.tpa.gui.TpaRequestGui;
import net.mineacle.core.tpa.service.TpaRequest;
import net.mineacle.core.tpa.service.TpaRequestType;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

@SuppressWarnings("unused")
public final class TpaGuiListener
        implements Listener {

    private static final String SECONDARY =
            "&#B078FF";
    private static final String NEUTRAL =
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

        int slot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        if (!event.getView()
                .title()
                .equals(
                        TpaRequestGui.TITLE
                )) {
            return;
        }

        event.setCancelled(true);

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
                tpaService.removeRequest(
                        target.getUniqueId()
                );

        if (request == null) {
            target.closeInventory();
            sendBoth(
                    target,
                    "&cYou have no pending teleport requests"
            );
            SoundService.guiError(
                    target,
                    core
            );
            return;
        }

        Player requester =
                tpaService.requester(request);

        if (requester == null
                || !requester.isOnline()) {
            target.closeInventory();
            sendBoth(
                    target,
                    "&cThat player is no longer online"
            );
            SoundService.guiError(
                    target,
                    core
            );
            return;
        }

        target.closeInventory();
        SoundService.guiSelect(
                target,
                core
        );

        sendBoth(
                requester,
                NEUTRAL
                        + "Teleport request accepted by "
                        + playerName(target)
        );
        sendBoth(
                target,
                NEUTRAL
                        + "Accepted teleport request from "
                        + playerName(requester)
        );

        if (request.type()
                == TpaRequestType.TO_TARGET) {
            beginPlayerTeleport(
                    requester,
                    target
            );
            return;
        }

        beginPlayerTeleport(
                target,
                requester
        );
    }

    private void beginPlayerTeleport(
            Player traveler,
            Player destination
    ) {
        String destinationName =
                DisplayNames.displayName(
                        destination
                );

        teleportService.beginTpa(
                traveler,
                destinationName,
                () -> {
                    if (!destination.isOnline()) {
                        sendBoth(
                                traveler,
                                "&cThat player is no longer online"
                        );
                        return false;
                    }

                    boolean teleported =
                            traveler.teleport(
                                    destination,
                                    PlayerTeleportEvent
                                            .TeleportCause
                                            .COMMAND
                            );

                    if (teleported) {
                        sendBoth(
                                traveler,
                                NEUTRAL
                                        + "Teleported to "
                                        + playerName(
                                        destination
                                )
                        );
                    }

                    return teleported;
                }
        );
    }

    private void deny(Player target) {
        TpaRequest request =
                tpaService.removeRequest(
                        target.getUniqueId()
                );

        if (request == null) {
            target.closeInventory();
            sendBoth(
                    target,
                    "&cYou have no pending teleport requests"
            );
            SoundService.guiError(
                    target,
                    core
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

    private String playerName(
            Player player
    ) {
        return SECONDARY
                + DisplayNames.displayName(
                player
        );
    }

    private void sendBoth(
            Player player,
            String message
    ) {
        player.sendMessage(
                TextColor.color(message)
        );
        player.sendActionBar(
                actionBar(message)
        );
    }

    private Component actionBar(
            String message
    ) {
        return LegacyComponentSerializer
                .legacySection()
                .deserialize(
                        TextColor.color(
                                message
                        )
                );
    }
}
