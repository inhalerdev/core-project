package net.mineacle.core.punish.listener;

import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.punish.gui.BanGui;
import net.mineacle.core.punish.service.PunishService;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class BanGuiListener implements Listener {

    private final PunishService service;

    public BanGuiListener(PunishService service) {
        this.service = service;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (!BanGui.isTitle(title, service)) {
            return;
        }

        event.setCancelled(true);

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        PunishService.ReasonOption reason = service.reasonAt(slot);

        if (reason != null) {
            BanGui.setReason(player, reason);
            player.sendMessage(TextColor.color("&#bbbbbbReason selected: &#ff88ff" + reason.reason()));
            SoundService.guiClick(player, service.core());
            return;
        }

        PunishService.DurationOption duration = service.durationAt(slot);

        if (duration != null) {
            BanGui.setDuration(player, duration);
            player.sendMessage(TextColor.color("&#bbbbbbDuration selected: &#ff88ff" + duration.duration()));
            SoundService.guiClick(player, service.core());
            return;
        }

        if (slot != BanGui.CONFIRM_SLOT) {
            return;
        }

        BanGui.Session session = BanGui.session(player);

        if (session == null) {
            player.closeInventory();
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(session.targetUuid());
        service.punish(player, target, session.reason(), session.duration());
        BanGui.clear(player);
        SoundService.guiConfirm(player, service.core());
    }
}
