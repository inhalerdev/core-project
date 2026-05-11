package net.mineacle.core.tpa.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.homes.service.TeleportService;
import net.mineacle.core.tpa.gui.TpaRequestGui;
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
            target.sendMessage("§cYou have no pending teleport requests.");
            return;
        }

        Player requester = tpaService.requester(request);

        if (requester == null || !requester.isOnline()) {
            target.closeInventory();
            target.sendMessage("§cThat player is no longer online.");
            return;
        }

        target.closeInventory();

        if (request.type() == TpaRequestType.TO_TARGET) {
            requester.sendMessage("§aTeleport request accepted.");
            target.sendMessage("§aTeleport request accepted.");

            teleportService.begin(requester, "TPA", () -> {
                requester.teleport(target.getLocation());
                requester.sendMessage("§aTeleported to §f" + target.getName() + "§a.");
            });
            return;
        }

        requester.sendMessage("§aTeleport request accepted.");
        target.sendMessage("§aTeleport request accepted.");

        teleportService.begin(target, "TPA", () -> {
            target.teleport(requester.getLocation());
            target.sendMessage("§aTeleported to §f" + requester.getName() + "§a.");
        });
    }

    private void deny(Player target) {
        TpaRequest request = tpaService.removeRequest(target.getUniqueId());

        if (request == null) {
            target.closeInventory();
            target.sendMessage("§cYou have no pending teleport requests.");
            return;
        }

        Player requester = tpaService.requester(request);

        target.closeInventory();
        target.sendMessage("§cTeleport request denied.");

        if (requester != null && requester.isOnline()) {
            requester.sendMessage("§c" + target.getName() + " denied your teleport request.");
        }

        MenuHistory.clear(target);
    }
}