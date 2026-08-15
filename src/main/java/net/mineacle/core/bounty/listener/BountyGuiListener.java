package net.mineacle.core.bounty.listener;

import net.mineacle.core.Core;
import net.mineacle.core.bounty.gui.BountyConfirmGui;
import net.mineacle.core.bounty.gui.BountyMainGui;
import net.mineacle.core.bounty.service.BountyService;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.stats.PlayerStatisticsGui;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.UUID;

public final class BountyGuiListener
        implements Listener {

    private final Core core;
    private final BountyService bountyService;
    private final BountySearchInputListener
            inputListener;
    private final PlayerStatisticsGui statisticsGui =
            new PlayerStatisticsGui();

    public BountyGuiListener(
            Core core,
            BountyService bountyService,
            BountySearchInputListener inputListener
    ) {
        this.core = core;
        this.bountyService = bountyService;
        this.inputListener = inputListener;
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onClick(
            InventoryClickEvent event
    ) {
        Object holder =
                event.getView()
                        .getTopInventory()
                        .getHolder();

        if (!(holder
                instanceof BountyMainGui.MainHolder)
                && !(holder
                instanceof BountyConfirmGui
                .ConfirmHolder)) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                Event.Result.DENY
        );

        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        if (event.getClickedInventory()
                == null
                || event.getClickedInventory()
                != event.getView()
                .getTopInventory()) {
            return;
        }

        int rawSlot =
                event.getRawSlot();

        if (holder instanceof BountyMainGui.MainHolder main) {
            handleMain(
                    event,
                    player,
                    main,
                    rawSlot
            );
            return;
        }

        BountyConfirmGui.ConfirmHolder confirm =
                (BountyConfirmGui.ConfirmHolder) holder;

        handleConfirm(
                player,
                confirm,
                rawSlot
        );
    }

    @SuppressWarnings("unused")
    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onDrag(
            InventoryDragEvent event
    ) {
        if (!BountyMainGui
                .isBountyInventory(
                        event.getView()
                                .getTopInventory()
                )) {
            return;
        }

        event.setCancelled(true);
        event.setResult(
                Event.Result.DENY
        );
    }

    private void handleMain(
            InventoryClickEvent event,
            Player player,
            BountyMainGui.MainHolder holder,
            int rawSlot
    ) {
        if (rawSlot
                == BountyMainGui.PREVIOUS_SLOT) {
            if (holder.page() <= 0) {
                return;
            }

            SoundService.guiPage(
                    player,
                    core
            );
            reopen(
                    player,
                    holder.page() - 1
            );
            return;
        }

        if (rawSlot
                == BountyMainGui.SORT_SLOT) {
            BountyMainGui.cycleSort(
                    player,
                    event.isRightClick()
            );
            SoundService.guiSort(
                    player,
                    core
            );
            reopen(
                    player,
                    0
            );
            return;
        }

        if (rawSlot
                == BountyMainGui.REFRESH_SLOT) {
            SoundService.guiRefresh(
                    player,
                    core
            );
            reopen(
                    player,
                    holder.page()
            );
            return;
        }

        if (rawSlot
                == BountyMainGui.SEARCH_SLOT) {
            if (event.isRightClick()
                    && BountyMainGui
                    .hasSearch(player)) {
                BountyMainGui.clearSearch(
                        player
                );
                SoundService.guiCancel(
                        player,
                        core
                );
                reopen(
                        player,
                        0
                );
                return;
            }

            SoundService.guiSearch(
                    player,
                    core
            );
            inputListener.beginSearch(
                    player,
                    holder.page()
            );
            return;
        }

        if (rawSlot
                == BountyMainGui.NEXT_SLOT) {
            if (!holder.hasNext()) {
                return;
            }

            SoundService.guiPage(
                    player,
                    core
            );
            reopen(
                    player,
                    holder.page() + 1
            );
            return;
        }

        UUID targetId =
                holder.targetAt(
                        rawSlot
                );

        if (targetId == null) {
            return;
        }

        if (event.isShiftClick()) {
            SoundService.guiSelect(
                    player,
                    core
            );
            inputListener.beginAmount(
                    player,
                    holder.page(),
                    targetId
            );
            return;
        }

        SoundService.guiSelect(
                player,
                core
        );

        MenuHistory.openChild(
                core,
                player,
                () -> BountyMainGui.open(
                        player,
                        bountyService,
                        holder.page()
                ),
                () -> statisticsGui.open(
                        player,
                        targetId
                )
        );
    }

    private void handleConfirm(
            Player player,
            BountyConfirmGui.ConfirmHolder holder,
            int rawSlot
    ) {
        if (rawSlot
                == BountyConfirmGui.CANCEL_SLOT) {
            SoundService.guiCancel(
                    player,
                    core
            );
            reopen(
                    player,
                    holder.returnPage()
            );
            return;
        }

        if (rawSlot
                != BountyConfirmGui.CONFIRM_SLOT
                || !holder.tryConsume()) {
            return;
        }

        OfflinePlayer target =
                Bukkit.getOfflinePlayer(
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
                        bountyService.displayName(
                                target
                        );

                player.sendMessage(
                        TextColor.color(
                                "&#bbbbbbPlaced &a"
                                        + bountyService.format(
                                        result.contributionCents()
                                )
                                        + " &#bbbbbbon &#B078FF"
                                        + targetName
                                        + " &#bbbbbb· Total &a"
                                        + bountyService.format(
                                        result.totalBountyCents()
                                )
                        )
                );
                SoundService.guiConfirm(
                        player,
                        core
                );

                Player onlineTarget =
                        target.getPlayer();

                if (onlineTarget != null
                        && onlineTarget
                        .isOnline()) {
                    onlineTarget.sendMessage(
                            TextColor.color(
                                    "&#B078FF"
                                            + bountyService.displayName(
                                            player
                                    )
                                            + " &#bbbbbbadded &a"
                                            + bountyService.format(
                                            result.contributionCents()
                                    )
                                            + " &#bbbbbbto your bounty · Total &a"
                                            + bountyService.format(
                                            result.totalBountyCents()
                                    )
                            )
                    );
                    SoundService.guiConfirm(
                            onlineTarget,
                            core
                    );
                }
            }
            case DISABLED ->
                    error(
                            player,
                            "&cBounty system is currently disabled"
                    );
            case INVALID_TARGET ->
                    error(
                            player,
                            "&cThat player could not be found"
                    );
            case SELF_TARGET ->
                    error(
                            player,
                            "&cYou cannot place a bounty on yourself"
                    );
            case INVALID_AMOUNT ->
                    error(
                            player,
                            "&cEnter a valid bounty amount"
                    );
            case BELOW_MINIMUM ->
                    error(
                            player,
                            "&cMinimum bounty is &a"
                                    + bountyService.format(
                                    bountyService
                                            .minimumCents()
                            )
                    );
            case ABOVE_MAXIMUM ->
                    error(
                            player,
                            "&cMaximum bounty is &a"
                                    + bountyService.format(
                                    bountyService
                                            .maximumCents()
                            )
                    );
            case ECONOMY_UNAVAILABLE ->
                    error(
                            player,
                            "&cEconomy is not available"
                    );
            case NOT_ENOUGH_MONEY ->
                    error(
                            player,
                            "&cYou do not have enough money"
                    );
            case STORAGE_ERROR ->
                    error(
                            player,
                            "&cCould not safely save that bounty"
                    );
        }

        reopen(
                player,
                holder.returnPage()
        );
    }

    private void reopen(
            Player player,
            int page
    ) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () -> BountyMainGui.open(
                        player,
                        bountyService,
                        page
                )
        );
    }

    private void error(
            Player player,
            String message
    ) {
        player.sendMessage(
                TextColor.color(message)
        );
        SoundService.guiError(
                player,
                core
        );
    }
}
