package net.mineacle.core.auctionhouse.gui;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.model.AuctionHouseListing;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@SuppressWarnings("unused")
public final class AuctionHouseGuiListener
        implements Listener {

    private final Core core;
    private final AuctionHouseService service;
    private final Map<UUID, InputPrompt>
            prompts =
            new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask>
            timeoutTasks =
            new ConcurrentHashMap<>();

    public AuctionHouseGuiListener(
            Core core,
            AuctionHouseService service
    ) {
        this.core = core;
        this.service = service;
    }

    public void shutdown() {
        for (BukkitTask task
                : timeoutTasks.values()) {
            task.cancel();
        }

        timeoutTasks.clear();
        prompts.clear();
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onClick(
            InventoryClickEvent event
    ) {
        if (!(event.getWhoClicked()
                instanceof Player player)) {
            return;
        }

        Object holder =
                event.getView()
                        .getTopInventory()
                        .getHolder();

        if (!(holder instanceof AuctionHouseGui.BrowseHolder)
                && !(holder instanceof AuctionHouseGui.OwnHolder)
                && !(holder instanceof AuctionHouseGui.ConfirmBuyHolder)
                && !(holder instanceof AuctionHouseGui.ConfirmCancelHolder)) {
            return;
        }

        event.setCancelled(true);

        if (event.getClickedInventory()
                == null
                || event.getClickedInventory()
                != event.getView()
                .getTopInventory()) {
            return;
        }

        if (!service.enabled()) {
            MenuHistory.close(
                    core,
                    player
            );
            failPath(
                    player,
                    "messages.disabled",
                    "&cAuction House is currently disabled"
            );
            return;
        }

        switch (holder) {
            case AuctionHouseGui.BrowseHolder browse ->
                    handleBrowse(
                            event,
                            player,
                            browse
                    );
            case AuctionHouseGui.OwnHolder own ->
                    handleOwn(
                            event,
                            player,
                            own
                    );
            case AuctionHouseGui.ConfirmBuyHolder confirmBuy ->
                    handleConfirmBuy(
                            event,
                            player,
                            confirmBuy
                    );
            case AuctionHouseGui.ConfirmCancelHolder confirmCancel ->
                    handleConfirmCancel(
                            event,
                            player,
                            confirmCancel
                    );
            default -> {
            }
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST
    )
    public void onDrag(
            InventoryDragEvent event
    ) {
        Object holder =
                event.getView()
                        .getTopInventory()
                        .getHolder();

        if (!(holder instanceof AuctionHouseGui.BrowseHolder)
                && !(holder instanceof AuctionHouseGui.OwnHolder)
                && !(holder instanceof AuctionHouseGui.ConfirmBuyHolder)
                && !(holder instanceof AuctionHouseGui.ConfirmCancelHolder)) {
            return;
        }

        int topSize =
                event.getView()
                        .getTopInventory()
                        .getSize();

        for (int rawSlot
                : event.getRawSlots()) {
            if (rawSlot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(
            priority = EventPriority.LOWEST
    )
    public void onChat(
            AsyncChatEvent event
    ) {
        Player player =
                event.getPlayer();
        InputPrompt prompt =
                takePrompt(
                        player.getUniqueId()
                );

        if (prompt == null) {
            return;
        }

        event.setCancelled(true);

        String input =
                PlainTextComponentSerializer
                        .plainText()
                        .serialize(
                                event.message()
                        )
                        .trim();

        core.getServer()
                .getScheduler()
                .runTask(
                        core,
                        () -> handlePrompt(
                                player,
                                prompt,
                                input
                        )
                );
    }

    @EventHandler(
            priority = EventPriority.MONITOR
    )
    public void onJoin(
            PlayerJoinEvent event
    ) {
        service.deliverPendingSaleNotice(
                event.getPlayer()
        );
    }

    @EventHandler
    public void onQuit(
            PlayerQuitEvent event
    ) {
        clearPrompt(
                event.getPlayer()
                        .getUniqueId()
        );
    }

    private void handleBrowse(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.BrowseHolder holder
    ) {
        int slot =
                event.getRawSlot();
        UUID listingId =
                holder.listingAt(slot);

        if (listingId != null) {
            AuctionHouseListing listing =
                    service.listing(
                            listingId
                    );

            if (listing == null
                    || service.isExpired(
                    listing
            )) {
                failPath(
                        player,
                        "messages.not-available",
                        "&cThat listing is no longer available"
                );
                reopenBrowse(
                        player,
                        holder
                );
                return;
            }

            if (event.isShiftClick()
                    && service
                    .quickBuyEnabled()) {
                buy(
                        player,
                        listingId,
                        holder
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
                    () ->
                            AuctionHouseGui
                                    .openBrowse(
                                            player,
                                            service,
                                            holder.page(),
                                            holder.sortMode(),
                                            holder.filterMode(),
                                            holder.query()
                                    ),
                    () ->
                            AuctionHouseGui
                                    .openConfirmBuy(
                                            player,
                                            service,
                                            listing,
                                            holder.page(),
                                            holder.sortMode(),
                                            holder.filterMode(),
                                            holder.query()
                                    )
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .previousSlot()
                && holder.page() > 0) {
            SoundService.guiPage(
                    player,
                    core
            );
            replaceBrowse(
                    player,
                    holder.page() - 1,
                    holder.sortMode(),
                    holder.filterMode(),
                    holder.query()
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .sortSlot()) {
            SoundService.guiSort(
                    player,
                    core
            );
            replaceBrowse(
                    player,
                    0,
                    event.isRightClick()
                            ? holder.sortMode()
                            .previous()
                            : holder.sortMode()
                            .next(),
                    holder.filterMode(),
                    holder.query()
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .filterSlot()) {
            SoundService.guiFilter(
                    player,
                    core
            );
            replaceBrowse(
                    player,
                    0,
                    holder.sortMode(),
                    event.isRightClick()
                            ? holder.filterMode()
                            .previous()
                            : holder.filterMode()
                            .next(),
                    holder.query()
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .refreshSlot()) {
            SoundService.guiRefresh(
                    player,
                    core
            );
            reopenBrowse(
                    player,
                    holder
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .searchSlot()) {
            if (event.isRightClick()
                    && !holder.query()
                    .isBlank()) {
                SoundService.guiCancel(
                        player,
                        core
                );
                replaceBrowse(
                        player,
                        0,
                        holder.sortMode(),
                        holder.filterMode(),
                        ""
                );
                return;
            }

            SoundService.guiSearch(
                    player,
                    core
            );
            beginSearch(
                    player,
                    holder
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .ownItemsSlot()) {
            SoundService.guiSelect(
                    player,
                    core
            );
            MenuHistory.openChild(
                    core,
                    player,
                    () ->
                            AuctionHouseGui
                                    .openBrowse(
                                            player,
                                            service,
                                            holder.page(),
                                            holder.sortMode(),
                                            holder.filterMode(),
                                            holder.query()
                                    ),
                    () ->
                            AuctionHouseGui
                                    .openOwn(
                                            player,
                                            service,
                                            0
                                    )
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .nextSlot()) {
            SoundService.guiPage(
                    player,
                    core
            );
            replaceBrowse(
                    player,
                    holder.page() + 1,
                    holder.sortMode(),
                    holder.filterMode(),
                    holder.query()
            );
        }
    }

    private void handleOwn(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.OwnHolder holder
    ) {
        int slot =
                event.getRawSlot();
        UUID listingId =
                holder.listingAt(slot);

        if (listingId != null) {
            AuctionHouseListing listing =
                    service.listing(
                            listingId
                    );

            if (listing == null) {
                failPath(
                        player,
                        "messages.not-available",
                        "&cThat listing is no longer available"
                );
                replaceOwn(
                        player,
                        holder.page()
                );
                return;
            }

            if (event.isShiftClick()) {
                cancelListing(
                        player,
                        listingId,
                        holder.page(),
                        false
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
                    () ->
                            AuctionHouseGui
                                    .openOwn(
                                            player,
                                            service,
                                            holder.page()
                                    ),
                    () ->
                            AuctionHouseGui
                                    .openConfirmCancel(
                                            player,
                                            service,
                                            listing,
                                            holder.page()
                                    )
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .ownPreviousSlot()
                && holder.page() > 0) {
            SoundService.guiPage(
                    player,
                    core
            );
            replaceOwn(
                    player,
                    holder.page() - 1
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .ownBackSlot()) {
            SoundService.guiBack(
                    player,
                    core
            );
            backToBrowse(
                    player,
                    0,
                    service.defaultSort(),
                    AuctionHouseService
                            .FilterMode.ALL,
                    ""
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .ownRefreshSlot()) {
            SoundService.guiRefresh(
                    player,
                    core
            );
            replaceOwn(
                    player,
                    holder.page()
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .ownListItemSlot()) {
            beginListPrompt(
                    player,
                    holder.page(),
                    event.isShiftClick()
                            ? 1
                            : -1
            );
            return;
        }

        if (slot
                == AuctionHouseGui
                .ownNextSlot()) {
            SoundService.guiPage(
                    player,
                    core
            );
            replaceOwn(
                    player,
                    holder.page() + 1
            );
        }
    }

    private void handleConfirmBuy(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.ConfirmBuyHolder holder
    ) {
        int slot =
                event.getRawSlot();

        if (slot
                == AuctionHouseGui
                .confirmBackSlot()) {
            SoundService.guiBack(
                    player,
                    core
            );
            backToBrowse(
                    player,
                    holder.returnPage(),
                    holder.returnSort(),
                    holder.returnFilter(),
                    holder.returnQuery()
            );
            return;
        }

        if (slot
                != AuctionHouseGui
                .confirmActionSlot()) {
            return;
        }

        buy(
                player,
                holder.listingId(),
                holder
        );
    }

    private void handleConfirmCancel(
            InventoryClickEvent event,
            Player player,
            AuctionHouseGui.ConfirmCancelHolder holder
    ) {
        int slot =
                event.getRawSlot();

        if (slot
                == AuctionHouseGui
                .confirmBackSlot()) {
            SoundService.guiBack(
                    player,
                    core
            );
            backToOwn(
                    player,
                    holder.returnPage()
            );
            return;
        }

        if (slot
                != AuctionHouseGui
                .confirmActionSlot()) {
            return;
        }

        cancelListing(
                player,
                holder.listingId(),
                holder.returnPage(),
                true
        );
    }

    private void buy(
            Player player,
            UUID listingId,
            AuctionHouseGui.BrowseHolder holder
    ) {
        AuctionHouseService.BuyOutcome
                outcome =
                service.buy(
                        player,
                        listingId
                );

        handleBuyOutcome(
                player,
                outcome,
                holder.page(),
                holder.sortMode(),
                holder.filterMode(),
                holder.query(),
                false
        );
    }

    private void buy(
            Player player,
            UUID listingId,
            AuctionHouseGui.ConfirmBuyHolder holder
    ) {
        AuctionHouseService.BuyOutcome
                outcome =
                service.buy(
                        player,
                        listingId
                );

        handleBuyOutcome(
                player,
                outcome,
                holder.returnPage(),
                holder.returnSort(),
                holder.returnFilter(),
                holder.returnQuery(),
                true
        );
    }

    private void handleBuyOutcome(
            Player player,
            AuctionHouseService.BuyOutcome
                    outcome,
            int page,
            AuctionHouseService.SortMode
                    sortMode,
            AuctionHouseService.FilterMode
                    filterMode,
            String query,
            boolean fromConfirm
    ) {
        AuctionHouseListing listing =
                outcome.listing();

        switch (outcome.result()) {
            case SUCCESS -> {
                player.sendMessage(
                        TextColor.color(
                                service.text(
                                        "messages.purchased",
                                        "&#bbbbbbPurchased &#B078FF%item% &#bbbbbbfor &a%price%",
                                        "%item%",
                                        service.itemName(
                                                listing.item()
                                        ),
                                        "%price%",
                                        service.format(
                                                listing.priceCents()
                                        )
                                )
                        )
                );

                SoundService.economyPay(
                        player,
                        core
                );

                returnToBrowseAfterAction(
                        player,
                        page,
                        sortMode,
                        filterMode,
                        query,
                        fromConfirm
                );
            }
            case OWN_ITEM ->
                    failPath(
                            player,
                            "messages.own-item",
                            "&cYou cannot buy your own listing"
                    );
            case NOT_ENOUGH_MONEY ->
                    failPath(
                            player,
                            "messages.not-enough-money",
                            "&cYou do not have enough money"
                    );
            case INVENTORY_FULL ->
                    failPath(
                            player,
                            "messages.inventory-full",
                            "&cYour inventory does not have enough space"
                    );
            case ECONOMY_MISSING ->
                    failPath(
                            player,
                            "messages.economy-missing",
                            "&cEconomy is not available"
                    );
            case PAYMENT_FAILED ->
                    failPath(
                            player,
                            "messages.payment-failed",
                            "&cCould not safely complete that payment"
                    );
            case STORAGE_ERROR ->
                    failPath(
                            player,
                            "messages.storage-error",
                            "&cCould not safely complete that purchase"
                    );
            case BUSY ->
                    failPath(
                            player,
                            "messages.busy",
                            "&cThat listing is already being processed"
                    );
            case EXPIRED,
                 NOT_FOUND -> {
                failPath(
                        player,
                        "messages.not-available",
                        "&cThat listing is no longer available"
                );
                returnToBrowseAfterAction(
                        player,
                        page,
                        sortMode,
                        filterMode,
                        query,
                        fromConfirm
                );
            }
        }
    }

    private void cancelListing(
            Player player,
            UUID listingId,
            int page,
            boolean fromConfirm
    ) {
        AuctionHouseListing listing =
                service.listing(
                        listingId
                );
        boolean expired =
                listing != null
                        && service.isExpired(
                        listing
                );

        AuctionHouseService.CancelResult
                result =
                service.cancelListing(
                        player,
                        listingId
                );

        switch (result) {
            case SUCCESS -> {
                player.sendMessage(
                        TextColor.color(
                                service.text(
                                        expired
                                                ? "messages.reclaimed"
                                                : "messages.cancelled",
                                        expired
                                                ? "&#bbbbbbReclaimed &#B078FF%item%"
                                                : "&#bbbbbbCancelled listing for &#B078FF%item%",
                                        "%item%",
                                        listing == null
                                                ? "item"
                                                : service.itemName(
                                                listing.item()
                                        )
                                )
                        )
                );
                SoundService.guiConfirm(
                        player,
                        core
                );
                returnToOwnAfterAction(
                        player,
                        page,
                        fromConfirm
                );
            }
            case NOT_FOUND -> {
                failPath(
                        player,
                        "messages.not-available",
                        "&cThat listing is no longer available"
                );
                returnToOwnAfterAction(
                        player,
                        page,
                        fromConfirm
                );
            }
            case NOT_OWNER ->
                    fail(
                            player,
                            core.getMessage(
                                    "general.no-permission"
                            )
                    );
            case INVENTORY_FULL ->
                    failPath(
                            player,
                            "messages.inventory-full",
                            "&cYour inventory does not have enough space"
                    );
            case STORAGE_ERROR ->
                    failPath(
                            player,
                            "messages.storage-error",
                            "&cCould not safely update that listing"
                    );
        }
    }

    private void beginSearch(
            Player player,
            AuctionHouseGui.BrowseHolder holder
    ) {
        InputPrompt prompt =
                new InputPrompt(
                        PromptType.SEARCH,
                        holder.page(),
                        holder.sortMode(),
                        holder.filterMode(),
                        holder.query(),
                        null,
                        0
                );

        beginPrompt(
                player,
                prompt
        );

        player.sendMessage(
                TextColor.color(
                        service.text(
                                "messages.search-prompt",
                                "&#bbbbbbType an item name to search"
                        )
                )
        );
        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbType &#D0AFFFcancel &#bbbbbbto return or &#D0AFFFclear &#bbbbbbto reset"
                )
        );
    }

    private void beginListPrompt(
            Player player,
            int page,
            int requestedAmount
    ) {
        if (!service.canList(player)) {
            fail(
                    player,
                    core.getMessage(
                            "general.no-permission"
                    )
            );
            return;
        }

        if (service.listingSlotsFull(player)) {
            failPath(
                    player,
                    "messages.no-slot",
                    "&cYour auction slots are full"
            );
            return;
        }

        ItemStack held =
                service.previewHeldItem(
                        player
                );

        if (held == null
                || held.getType().isAir()) {
            failPath(
                    player,
                    "messages.no-item",
                    "&cHold the item you want to list"
            );
            return;
        }

        int amount =
                requestedAmount > 0
                        ? requestedAmount
                        : held.getAmount();

        amount =
                Math.min(
                        amount,
                        held.getAmount()
                );

        ItemStack preview =
                held.clone();
        preview.setAmount(1);

        InputPrompt prompt =
                new InputPrompt(
                        PromptType.LIST_PRICE,
                        page,
                        service.defaultSort(),
                        AuctionHouseService
                                .FilterMode.ALL,
                        "",
                        preview,
                        amount
                );

        beginPrompt(
                player,
                prompt
        );

        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbListing &#B078FF"
                                + amount
                                + "x "
                                + service.itemName(
                                held
                        )
                )
        );

        ItemStack worthItem =
                held.clone();
        worthItem.setAmount(amount);
        long worth =
                service.worthCents(
                        worthItem
                );

        if (worth > 0L) {
            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbbWorth: &#D0AFFF"
                                    + service.format(
                                    worth
                            )
                    )
            );
        }

        player.sendMessage(
                TextColor.color(
                        service.text(
                                "messages.price-prompt",
                                "&#bbbbbbType a price or &#D0AFFFcancel"
                        )
                )
        );

        SoundService.guiSelect(
                player,
                core
        );
    }

    private void beginPrompt(
            Player player,
            InputPrompt prompt
    ) {
        UUID playerId =
                player.getUniqueId();

        clearPrompt(playerId);
        prompts.put(
                playerId,
                prompt
        );

        BukkitTask timeout =
                core.getServer()
                        .getScheduler()
                        .runTaskLater(
                                core,
                                () ->
                                        expirePrompt(
                                                playerId,
                                                prompt
                                        ),
                                service
                                        .promptTimeoutTicks()
                        );

        timeoutTasks.put(
                playerId,
                timeout
        );

        MenuHistory.closeForInput(
                core,
                player
        );
    }

    private void handlePrompt(
            Player player,
            InputPrompt prompt,
            String input
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (input.equalsIgnoreCase(
                "cancel"
        )
                || input.equalsIgnoreCase(
                "cancelled"
        )) {
            SoundService.guiCancel(
                    player,
                    core
            );
            returnFromPrompt(
                    player,
                    prompt
            );
            return;
        }

        if (prompt.type()
                == PromptType.SEARCH) {
            handleSearchPrompt(
                    player,
                    prompt,
                    input
            );
            return;
        }

        handleListPrompt(
                player,
                prompt,
                input
        );
    }

    private void handleSearchPrompt(
            Player player,
            InputPrompt prompt,
            String input
    ) {
        if (input.equalsIgnoreCase(
                "clear"
        )) {
            SoundService.guiCancel(
                    player,
                    core
            );
            replaceBrowse(
                    player,
                    0,
                    prompt.sortMode(),
                    prompt.filterMode(),
                    ""
            );
            return;
        }

        if (input.isBlank()) {
            failPath(
                    player,
                    "messages.empty-search",
                    "&cSearch cannot be empty"
            );
            returnFromPrompt(
                    player,
                    prompt
            );
            return;
        }

        if (service.searchQueryTooLong(
                input
        )) {
            fail(
                    player,
                    TextColor.color(
                            service.text(
                                    "messages.search-too-long",
                                    "&cSearch cannot exceed %max% characters",
                                    "%max%",
                                    String.valueOf(
                                            service.maxSearchLength()
                                    )
                            )
                    )
            );
            returnFromPrompt(
                    player,
                    prompt
            );
            return;
        }

        SoundService.guiSearch(
                player,
                core
        );
        replaceBrowse(
                player,
                0,
                prompt.sortMode(),
                prompt.filterMode(),
                service
                        .sanitizeSearchQuery(
                                input
                        )
        );
    }

    private void handleListPrompt(
            Player player,
            InputPrompt prompt,
            String input
    ) {
        long price =
                service.parsePriceCents(
                        input
                );

        AuctionHouseService.CreateOutcome
                outcome =
                service.createListing(
                        player,
                        price,
                        prompt.amount(),
                        prompt.item()
                );

        if (outcome.result()
                == AuctionHouseService
                .CreateResult.SUCCESS) {
            AuctionHouseListing listing =
                    outcome.listing();

            player.sendMessage(
                    TextColor.color(
                            service.text(
                                    "messages.listed",
                                    "&#bbbbbbListed &#B078FF%amount%x %item% &#bbbbbbfor &a%price%",
                                    "%amount%",
                                    String.valueOf(
                                            listing.amount()
                                    ),
                                    "%item%",
                                    service.itemName(
                                            listing.item()
                                    ),
                                    "%price%",
                                    service.format(
                                            listing.priceCents()
                                    )
                            )
                    )
            );
            SoundService.guiConfirm(
                    player,
                    core
            );
            replaceOwn(
                    player,
                    prompt.page()
            );
            return;
        }

        sendCreateError(
                player,
                outcome
        );
        replaceOwn(
                player,
                prompt.page()
        );
    }

    private void sendCreateError(
            Player player,
            AuctionHouseService.CreateOutcome
                    outcome
    ) {
        switch (outcome.result()) {
            case DISABLED ->
                    failPath(
                            player,
                            "messages.disabled",
                            "&cAuction House is currently disabled"
                    );
            case NO_PERMISSION ->
                    fail(
                            player,
                            core.getMessage(
                                    "general.no-permission"
                            )
                    );
            case NO_ITEM ->
                    failPath(
                            player,
                            "messages.no-item",
                            "&cHold the item you want to list"
                    );
            case ITEM_CHANGED ->
                    failPath(
                            player,
                            "messages.item-changed",
                            "&cThe item in your hand changed"
                    );
            case INVALID_AMOUNT ->
                    failPath(
                            player,
                            "messages.invalid-amount",
                            "&cEnter a valid item amount"
                    );
            case NO_SLOT ->
                    failPath(
                            player,
                            "messages.no-slot",
                            "&cYou do not have an auction slot available"
                    );
            case INVALID_PRICE ->
                    failPath(
                            player,
                            "messages.invalid-price",
                            "&cEnter a valid auction price"
                    );
            case BELOW_MINIMUM ->
                    fail(
                            player,
                            TextColor.color(
                                    service.text(
                                            "messages.below-minimum",
                                            "&cMinimum auction price is &a%price%",
                                            "%price%",
                                            service.format(
                                                    service.minPriceCents()
                                            )
                                    )
                            )
                    );
            case ABOVE_MAXIMUM ->
                    fail(
                            player,
                            TextColor.color(
                                    service.text(
                                            "messages.above-maximum",
                                            "&cMaximum auction price is &a%price%",
                                            "%price%",
                                            service.format(
                                                    service.maxPriceCents()
                                            )
                                    )
                            )
                    );
            case BLOCKED_ITEM ->
                    failPath(
                            player,
                            "messages.blocked-item",
                            "&cThat item cannot be listed"
                    );
            case FILLED_CONTAINER ->
                    failPath(
                            player,
                            "messages.filled-container",
                            "&cEmpty that container before listing it"
                    );
            case OVERSIZED_ITEM ->
                    failPath(
                            player,
                            "messages.oversized-item",
                            "&cThat item contains too much data to list safely"
                    );
            case STORAGE_ERROR ->
                    failPath(
                            player,
                            "messages.storage-error",
                            "&cCould not safely save that listing"
                    );
        }
    }

    private void expirePrompt(
            UUID playerId,
            InputPrompt expected
    ) {
        if (!prompts.remove(
                playerId,
                expected
        )) {
            return;
        }

        timeoutTasks.remove(
                playerId
        );

        Player player =
                Bukkit.getPlayer(
                        playerId
                );

        if (player == null
                || !player.isOnline()) {
            return;
        }

        failPath(
                player,
                "messages.prompt-timeout",
                "&cAuction input timed out"
        );
        returnFromPrompt(
                player,
                expected
        );
    }

    private InputPrompt takePrompt(
            UUID playerId
    ) {
        InputPrompt prompt =
                prompts.remove(
                        playerId
                );
        BukkitTask task =
                timeoutTasks.remove(
                        playerId
                );

        if (task != null) {
            task.cancel();
        }

        return prompt;
    }

    private void clearPrompt(
            UUID playerId
    ) {
        prompts.remove(playerId);

        BukkitTask task =
                timeoutTasks.remove(
                        playerId
                );

        if (task != null) {
            task.cancel();
        }
    }

    private void returnFromPrompt(
            Player player,
            InputPrompt prompt
    ) {
        if (prompt.type()
                == PromptType.SEARCH) {
            replaceBrowse(
                    player,
                    prompt.page(),
                    prompt.sortMode(),
                    prompt.filterMode(),
                    prompt.query()
            );
        } else {
            replaceOwn(
                    player,
                    prompt.page()
            );
        }
    }

    private void reopenBrowse(
            Player player,
            AuctionHouseGui.BrowseHolder holder
    ) {
        replaceBrowse(
                player,
                holder.page(),
                holder.sortMode(),
                holder.filterMode(),
                holder.query()
        );
    }

    private void returnToBrowseAfterAction(
            Player player,
            int page,
            AuctionHouseService.SortMode sortMode,
            AuctionHouseService.FilterMode filterMode,
            String query,
            boolean fromConfirm
    ) {
        if (fromConfirm) {
            backToBrowse(
                    player,
                    page,
                    sortMode,
                    filterMode,
                    query
            );
            return;
        }

        replaceBrowse(
                player,
                page,
                sortMode,
                filterMode,
                query
        );
    }

    private void returnToOwnAfterAction(
            Player player,
            int page,
            boolean fromConfirm
    ) {
        if (fromConfirm) {
            backToOwn(
                    player,
                    page
            );
            return;
        }

        replaceOwn(
                player,
                page
        );
    }

    private void backToBrowse(
            Player player,
            int page,
            AuctionHouseService.SortMode
                    sortMode,
            AuctionHouseService.FilterMode
                    filterMode,
            String query
    ) {
        if (!MenuHistory.back(
                core,
                player
        )) {
            replaceBrowse(
                    player,
                    page,
                    sortMode,
                    filterMode,
                    query
            );
        }
    }

    private void backToOwn(
            Player player,
            int page
    ) {
        if (!MenuHistory.back(
                core,
                player
        )) {
            replaceOwn(
                    player,
                    page
            );
        }
    }

    private void replaceBrowse(
            Player player,
            int page,
            AuctionHouseService.SortMode
                    sortMode,
            AuctionHouseService.FilterMode
                    filterMode,
            String query
    ) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () ->
                        AuctionHouseGui
                                .openBrowse(
                                        player,
                                        service,
                                        page,
                                        sortMode,
                                        filterMode,
                                        query
                                )
        );
    }

    private void replaceOwn(
            Player player,
            int page
    ) {
        MenuHistory.openWithoutBackTrigger(
                core,
                player,
                () ->
                        AuctionHouseGui
                                .openOwn(
                                        player,
                                        service,
                                        page
                                )
        );
    }

    private void failPath(
            Player player,
            String path,
            String fallback
    ) {
        fail(
                player,
                TextColor.color(
                        service.text(
                                path,
                                fallback
                        )
                )
        );
    }

    private void fail(
            Player player,
            String message
    ) {
        player.sendMessage(message);
        SoundService.guiError(
                player,
                core
        );
    }

    private enum PromptType {
        SEARCH,
        LIST_PRICE
    }

    private record InputPrompt(
            PromptType type,
            int page,
            AuctionHouseService.SortMode
                    sortMode,
            AuctionHouseService.FilterMode
                    filterMode,
            String query,
            ItemStack item,
            int amount
    ) {
        private InputPrompt {
            query =
                    query == null
                            ? ""
                            : query;
            item =
                    item == null
                            ? null
                            : item.clone();
        }

        @Override
        public ItemStack item() {
            return item == null
                    ? null
                    : item.clone();
        }
    }
}
