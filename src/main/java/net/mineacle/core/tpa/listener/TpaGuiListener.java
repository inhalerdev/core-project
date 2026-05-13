package net.mineacle.core.tpa.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.homes.service.TeleportService;
import net.mineacle.core.tpa.service.TpaRequest;
import net.mineacle.core.tpa.service.TpaRequestType;
import net.mineacle.core.tpa.service.TpaService;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class TpaGuiListener implements Listener {

    private final Core core;
    private final TpaService tpaService;
    private final TeleportService teleportService;

    public TpaGuiListener(Core core, TpaService tpaService, TeleportService teleportService) {
        this.core = core;
        this.tpaService = tpaService;
        this.teleportService = teleportService;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title == null || !title.equalsIgnoreCase("Teleport Request")) {
            return;
        }

        event.setCancelled(true);

        if (slot == 11) {
            deny(player);
            return;
        }

        if (slot == 15) {
            accept(player);
        }
    }

    private void accept(Player target) {
        TpaRequest request = tpaService.removeRequest(target.getUniqueId());

        if (request == null) {
            target.closeInventory();
            send(target, "&cYou have no pending teleport requests");
            SoundService.guiError(target, core);
            return;
        }

        Player requester = tpaService.requester(request);

        if (requester == null || !requester.isOnline()) {
            target.closeInventory();
            send(target, "&cThat player is no longer online");
            SoundService.guiError(target, core);
            return;
        }

        target.closeInventory();

        send(requester, "&aTeleport request accepted");
        send(target, "&aTeleport request accepted");

        if (request.type() == TpaRequestType.TO_TARGET) {
            teleportService.begin(requester, "TPA", () -> {
                requester.teleport(target.getLocation());
                send(requester, "&aTeleported to &d" + DisplayNames.displayName(target));
            });
            return;
        }

        teleportService.begin(target, "TPA", () -> {
            target.teleport(requester.getLocation());
            send(target, "&aTeleported to &d" + DisplayNames.displayName(requester));
        });
    }

    private void deny(Player target) {
        TpaRequest request = tpaService.removeRequest(target.getUniqueId());

        if (request == null) {
            target.closeInventory();
            send(target, "&cYou have no pending teleport requests");
            SoundService.guiError(target, core);
            return;
        }

        Player requester = tpaService.requester(request);

        target.closeInventory();
        send(target, "&cTeleport request denied");
        SoundService.guiCancel(target, core);

        if (requester != null && requester.isOnline()) {
            send(requester, "&c" + DisplayNames.displayName(target) + " denied your teleport request");
        }

        MenuHistory.clear(target);
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
    }
}
