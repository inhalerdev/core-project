package net.mineacle.core.bounty;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.stats.PlayerStatisticsGui;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.UUID;

public final class BountyGuiListener implements Listener {

    private final Core core;
    private final BountyService bountyService;
    private final PlayerStatisticsGui statsGui = new PlayerStatisticsGui();

    public BountyGuiListener(Core core, BountyService bountyService) {
        this.core = core;
        this.bountyService = bountyService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = ChatColor.stripColor(event.getView().getTitle());

        if (BountyMainGui.isTitle(title)) {
            handleMainClick(event, player);
            return;
        }

        if (BountyConfirmGui.TITLE.equals(title)) {
            handleConfirmClick(event, player);
        }
    }

    private void handleMainClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        int slot = event.getRawSlot();
        int topSize = event.getView().getTopInventory().getSize();

        if (slot < 0 || slot >= topSize) {
            return;
        }

        int page = BountyMainGui.currentPage(player);

        if (slot == BountyMainGui.PREVIOUS_SLOT) {
            SoundService.guiClick(player, core);
            BountyMainGui.open(core, player, bountyService, page - 1);
            return;
        }

        if (slot == BountyMainGui.SORT_SLOT) {
            SoundService.guiClick(player, core);
            BountyMainGui.cycleSort(core, player);
            BountyMainGui.open(core, player, bountyService, 0);
            return;
        }

        if (slot == BountyMainGui.REFRESH_SLOT) {
            SoundService.guiClick(player, core);
            BountyMainGui.open(core, player, bountyService, page);
            return;
        }

        if (slot == BountyMainGui.NEXT_SLOT) {
            SoundService.guiClick(player, core);
            BountyMainGui.open(core, player, bountyService, page + 1);
            return;
        }

        UUID targetId = BountyMainGui.targetAt(player, bountyService, slot);

        if (targetId == null) {
            return;
        }

        SoundService.guiClick(player, core);
        MenuHistory.openChild(
                core,
                player,
                () -> BountyMainGui.open(core, player, bountyService, page),
                () -> statsGui.open(player, targetId)
        );
    }

    private void handleConfirmClick(InventoryClickEvent event, Player player) {
        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        int slot = event.getRawSlot();

        if (slot == BountyConfirmGui.CANCEL_SLOT) {
            clearMeta(player);
            player.closeInventory();
            player.sendMessage(TextColor.color("&cBounty cancelled"));
            SoundService.guiCancel(player, core);
            return;
        }

        if (slot != BountyConfirmGui.CONFIRM_SLOT) {
            return;
        }

        if (!player.hasMetadata(BountyCommand.META_TARGET) || !player.hasMetadata(BountyCommand.META_AMOUNT)) {
            clearMeta(player);
            player.closeInventory();
            player.sendMessage(TextColor.color("&cNo bounty is ready to confirm"));
            SoundService.guiError(player, core);
            return;
        }

        UUID targetId = UUID.fromString(player.getMetadata(BountyCommand.META_TARGET).get(0).asString());
        long amount = player.getMetadata(BountyCommand.META_AMOUNT).get(0).asLong();
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetId);

        if (!bountyService.place(player, target, amount)) {
            clearMeta(player);
            player.closeInventory();
            player.sendMessage(TextColor.color("&cCould not place bounty"));
            SoundService.guiError(player, core);
            return;
        }

        clearMeta(player);
        player.closeInventory();

        String message = "&#bbbbbbPlaced &a" + bountyService.format(amount) + " &#bbbbbbbounty on &#ff88ff" + DisplayNames.displayName(target);

        player.sendMessage(TextColor.color(message));
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(TextColor.color(message)));
        SoundService.guiConfirm(player, core);

        Player onlineTarget = target.getPlayer();

        if (onlineTarget != null && onlineTarget.isOnline()) {
            onlineTarget.sendMessage(TextColor.color("&#ff88ff" + DisplayNames.displayName(player) + " &#bbbbbbplaced a bounty on you"));
            SoundService.guiError(onlineTarget, core);
        }
    }

    private void clearMeta(Player player) {
        player.removeMetadata(BountyCommand.META_TARGET, core);
        player.removeMetadata(BountyCommand.META_AMOUNT, core);
    }
}
