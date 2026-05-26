package net.mineacle.core.punish.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.punish.gui.PunishConfirmGui;
import net.mineacle.core.punish.gui.PunishGui;
import net.mineacle.core.punish.model.PunishAction;
import net.mineacle.core.punish.model.PunishCase;
import net.mineacle.core.punish.model.PunishDuration;
import net.mineacle.core.punish.model.PunishReason;
import net.mineacle.core.punish.model.PunishSession;
import net.mineacle.core.punish.service.PunishService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class PunishGuiListener implements Listener {
    private final PunishService service;

    public PunishGuiListener(PunishService service) {
        this.service = service;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();
        if (slot < 0 || slot >= topSize) {
            return;
        }

        String title = event.getView().getTitle();
        if (PunishGui.isTitle(title, service)) {
            event.setCancelled(true);
            handleMainClick(player, slot);
            return;
        }
        if (PunishConfirmGui.isTitle(title, service)) {
            event.setCancelled(true);
            handleConfirmClick(player, slot);
        }
    }

    private void handleMainClick(Player player, int slot) {
        PunishSession session = service.session(player);
        if (session == null) {
            player.closeInventory();
            SoundService.guiError(player, service.core());
            return;
        }

        PunishAction action = PunishAction.atSlot(slot);
        if (action != null) {
            session.action(action);
            SoundService.guiClick(player, service.core());
            PunishGui.open(player, service);
            return;
        }

        PunishReason reason = service.reasonAt(slot);
        if (reason != null) {
            session.reason(reason);
            SoundService.guiClick(player, service.core());
            PunishGui.open(player, service);
            return;
        }

        PunishDuration duration = service.durationAt(slot);
        if (duration != null) {
            session.duration(duration);
            SoundService.guiClick(player, service.core());
            PunishGui.open(player, service);
            return;
        }

        if (slot == PunishGui.CLOSE_SLOT) {
            service.clear(player);
            player.closeInventory();
            SoundService.guiCancel(player, service.core());
            return;
        }

        if (slot != PunishGui.CONFIRM_SLOT) {
            return;
        }

        String error = service.validationError(session);
        if (error != null) {
            sendBoth(player, error);
            SoundService.guiError(player, service.core());
            return;
        }

        SoundService.guiClick(player, service.core());
        PunishConfirmGui.open(player, service);
    }

    private void handleConfirmClick(Player player, int slot) {
        PunishSession session = service.session(player);
        if (session == null) {
            player.closeInventory();
            SoundService.guiError(player, service.core());
            return;
        }

        if (slot == PunishConfirmGui.CANCEL_SLOT) {
            service.clear(player);
            player.closeInventory();
            sendBoth(player, service.message("cancelled", "&cPunishment cancelled"));
            SoundService.guiCancel(player, service.core());
            return;
        }

        if (slot == PunishConfirmGui.INFO_SLOT) {
            return;
        }

        if (slot != PunishConfirmGui.CONFIRM_SLOT) {
            return;
        }

        if (!session.confirmReady()) {
            session.confirmReady(true);
            sendBoth(player, service.message("confirm-again", "&#bbbbbbClick confirm again to continue"));
            SoundService.guiConfirm(player, service.core());
            service.core().getServer().getScheduler().runTaskLater(service.core(), () -> {
                if (!player.isOnline()) {
                    return;
                }
                PunishSession current = service.session(player);
                if (current == null || !current.confirmReady()) {
                    return;
                }
                current.confirmReady(false);
                sendBoth(player, service.message("timeout", "&cAction timed out"));
                SoundService.guiError(player, service.core());
            }, 20L * service.confirmTimeoutSeconds());
            return;
        }

        PunishCase punishCase = service.execute(player, session);
        service.clear(player);
        player.closeInventory();

        if (punishCase == null) {
            SoundService.guiError(player, service.core());
            return;
        }

        sendBoth(player, service.message("success", "&#bbbbbbPunished &#ff88ff%player% &#bbbbbbwith case &#ff88ff%case_id%")
            .replace("%player%", punishCase.targetName())
            .replace("%case_id%", punishCase.caseId())
            .replace("%action%", punishCase.action().displayName())
            .replace("%reason%", punishCase.reason())
            .replace("%duration%", punishCase.duration()));
        SoundService.guiConfirm(player, service.core());
    }

    private void sendBoth(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        player.sendActionBar(actionBar(message));
    }

    private Component actionBar(String message) {
        return LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message));
    }
}
