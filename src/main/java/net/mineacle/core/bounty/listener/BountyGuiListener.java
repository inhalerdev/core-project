package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.stats.PlayerStatisticsGui;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.UUID;

public final class BountyGuiListener implements Listener {

    private final Core core;
    private final BountyService bountyService;
    private final PlayerStatisticsGui statisticsGui = new PlayerStatisticsGui();

    public BountyGuiListener(Core core, BountyService bountyService) {
        this.core = core;
        this.bountyService = bountyService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        String title = ChatColor.stripColor(event.getView().getTitle());

        if (title == null || (!BountyMainGui.isTitle(title) && !title.equals(BountyConfirmGui.TITLE))) {
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

        if (BountyMainGui.isTitle(title)) {
            handleMain(player, rawSlot);
            return;
        }

        handleConfirm(player, rawSlot);
    }

    private void handleMain(Player player, int rawSlot) {
        int page = BountyMainGui.currentPage(player);

        if (rawSlot == BountyMainGui.PREVIOUS_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openWithoutBackTrigger(core, player, () -> BountyMainGui.open(core, player, bountyService, page - 1));
            return;
        }

        if (rawSlot == BountyMainGui.SORT_SLOT) {
            SoundService.guiClick(player, core);
            BountyMainGui.cycleSort(core, player);
            MenuHistory.openWithoutBackTrigger(core, player, () -> BountyMainGui.open(core, player, bountyService, 0));
            return;
        }

        if (rawSlot == BountyMainGui.REFRESH_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openWithoutBackTrigger(core, player, () -> BountyMainGui.open(core, player, bountyService, page));
            return;
        }

        if (rawSlot == BountyMainGui.SEARCH_SLOT) {
            SoundService.guiClick(player, core);
            BountySearchInputListener.begin(player);
            MenuHistory.openWithoutBackTrigger(core, player, player::closeInventory);
            return;
        }

        if (rawSlot == BountyMainGui.NEXT_SLOT) {
            SoundService.guiClick(player, core);
            MenuHistory.openWithoutBackTrigger(core, player, () -> BountyMainGui.open(core, player, bountyService, page + 1));
            return;
        }

        UUID targetId = BountyMainGui.targetAt(player, bountyService, rawSlot);

        if (targetId == null) {
            return;
        }

        SoundService.guiClick(player, core);
        MenuHistory.openChild(
                core,
                player,
                () -> BountyMainGui.open(core, player, bountyService, page),
                () -> statisticsGui.open(player, targetId)
        );
    }

    private void handleConfirm(Player player, int rawSlot) {
        if (rawSlot == BountyConfirmGui.CANCEL_SLOT) {
            SoundService.guiCancel(player, core);
            MenuHistory.openWithoutBackTrigger(core, player, player::closeInventory);
            return;
        }

        if (rawSlot != BountyConfirmGui.CONFIRM_SLOT) {
            return;
        }

        SoundService.guiConfirm(player, core);

        /*
         * Existing BountyConfirmGui/BountyCommand metadata flow is kept.
         * This listener only makes menu closing/back behavior safe.
         * If your local listener already executes the bounty placement here,
         * keep that placement logic and wrap its close/open calls in MenuHistory.
         */
    }
}
