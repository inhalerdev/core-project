package net.mineacle.core.bounty;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.stats.PlayerStatisticsGui;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.UUID;

public final class BountyGuiListener implements Listener {

    private final Core core;
    private final BountyService bountyService;
    private final BountySearchInputListener searchInputListener;
    private final PlayerStatisticsGui statisticsGui =
            new PlayerStatisticsGui();

    public BountyGuiListener(
            Core core,
            BountyService bountyService,
            BountySearchInputListener searchInputListener
    ) {
        this.core = core;
        this.bountyService = bountyService;
        this.searchInputListener = searchInputListener;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onClick(InventoryClickEvent event) {
        BountyMainGui.MainHolder mainHolder =
                BountyMainGui.holder(
                        event.getView().getTopInventory()
                );
        BountyConfirmGui.ConfirmHolder confirmHolder =
                BountyConfirmGui.holder(
                        event.getView().getTopInventory()
                );

        if (mainHolder == null && confirmHolder == null) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        int rawSlot = event.getRawSlot();
        int topSize = event.getView()
                .getTopInventory()
                .getSize();

        if (rawSlot < 0 || rawSlot >= topSize) {
            return;
        }

        if ((rawSlot == BountyMainGui.PREVIOUS_SLOT
                || rawSlot == BountyMainGui.NEXT_SLOT)
                && BountyMainGui.isDisabledNavigation(
                event.getCurrentItem()
        )) {
            return;
        }

        if (mainHolder != null) {
            handleMain(
                    event,
                    player,
                    mainHolder,
                    rawSlot
            );
            return;
        }

        handleConfirm(player, confirmHolder, rawSlot);
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = false
    )
    public void onDrag(InventoryDragEvent event) {
        if (!BountyMainGui.isBountyInventory(
                event.getView().getTopInventory()
        )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(org.bukkit.event.Event.Result.DENY);
    }

    private void handleMain(
            InventoryClickEvent event,
            Player player,
            BountyMainGui.MainHolder holder,
            int rawSlot
    ) {
        if (rawSlot == BountyMainGui.PREVIOUS_SLOT) {
            reopen(player, holder.page() - 1);
            return;
        }

        if (rawSlot == BountyMainGui.SORT_SLOT) {
            BountyMainGui.cycleSort(player);
            reopen(player, 0);
            return;
        }

        if (rawSlot == BountyMainGui.REFRESH_SLOT) {
            reopen(player, holder.page());
            return;
        }

        if (rawSlot == BountyMainGui.SEARCH_SLOT) {
            if (event.isRightClick()
                    && BountyMainGui.hasSearch(player)) {
                BountyMainGui.clearSearch(player);
                player.sendActionBar(
                        net.kyori.adventure.text.serializer.legacy
                                .LegacyComponentSerializer
                                .legacySection()
                                .deserialize(
                                        TextColor.color(
                                                "&#bbbbbbBounty search cleared"
                                        )
                                )
                );
                reopen(player, 0);
                return;
            }

            searchInputListener.begin(
                    player,
                    holder.page()
            );
            return;
        }

        if (rawSlot == BountyMainGui.NEXT_SLOT) {
            reopen(player, holder.page() + 1);
            return;
        }

        UUID targetId = holder.targetAt(rawSlot);

        if (targetId == null) {
            return;
        }

        MenuHistory.openChild(
                core,
                player,
                () -> BountyMainGui.open(
                        core,
                        player,
                        bountyService,
                        holder.page()
                ),
                () -> statisticsGui.open(player, targetId)
        );
    }

    private void handleConfirm(
            Player player,
            BountyConfirmGui.ConfirmHolder holder,
            int rawSlot
    ) {
        if (rawSlot == BountyConfirmGui.CANCEL_SLOT) {
            SoundService.guiCancel(player, core);
            reopen(player, 0);
            return;
        }

        if (rawSlot != BountyConfirmGui.CONFIRM_SLOT
                || !holder.tryConsume()) {
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(
                holder.targetId()
        );
        BountyService.PlaceResult result =
                bountyService.placeDetailed(
                        player,
                        target,
                        holder.amountCents()
                );

        switch (result.status()) {
            case SUCCESS -> {
                String targetName =
                        bountyService.displayName(target);

                player.sendMessage(TextColor.color(
                        "&#bbbbbbPlaced &a"
                                + bountyService.format(
                                result.contributionCents()
                        )
                                + " &#bbbbbbbounty on &#bbbbbb"
                                + targetName
                ));
                SoundService.guiConfirm(player, core);

                Player onlineTarget = target.getPlayer();

                if (onlineTarget != null
                        && onlineTarget.isOnline()) {
                    onlineTarget.sendMessage(TextColor.color(
                            "&#bbbbbb"
                                    + bountyService.displayName(player)
                                    + " placed an &a"
                                    + bountyService.format(
                                    result.contributionCents()
                            )
                                    + " &#bbbbbbbounty on you"
                    ));
                    SoundService.guiConfirm(
                            onlineTarget,
                            core
                    );
                }
            }
            case DISABLED -> sendError(
                    player,
                    "&cBounty system is currently disabled"
            );
            case INVALID_TARGET -> sendError(
                    player,
                    "&cThat player could not be found"
            );
            case SELF_TARGET -> sendError(
                    player,
                    "&cYou cannot place a bounty on yourself"
            );
            case INVALID_AMOUNT -> sendError(
                    player,
                    "&cEnter a valid bounty amount"
            );
            case BELOW_MINIMUM -> sendError(
                    player,
                    "&cMinimum bounty is &a"
                            + bountyService.format(
                            bountyService.minimumCents()
                    )
            );
            case ABOVE_MAXIMUM -> sendError(
                    player,
                    "&cMaximum bounty is &a"
                            + bountyService.format(
                            bountyService.maximumCents()
                    )
            );
            case ECONOMY_UNAVAILABLE -> sendError(
                    player,
                    "&cEconomy is not available"
            );
            case NOT_ENOUGH_MONEY -> sendError(
                    player,
                    "&cYou do not have enough money"
            );
            case STORAGE_ERROR -> sendError(
                    player,
                    "&cCould not save that bounty"
            );
        }

        reopen(player, 0);
    }

    private void reopen(Player player, int page) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> BountyMainGui.open(
                        core,
                        player,
                        bountyService,
                        page
                )
        );
    }

    private void sendError(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }
}
