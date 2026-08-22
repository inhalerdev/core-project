package net.mineacle.core.market.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.market.model.MarketSellExecutionResult;
import net.mineacle.core.market.model.MarketTransaction;
import net.mineacle.core.market.service.MarketExchangeService;
import net.mineacle.core.sell.model.SaleResult;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/**
 * Activates the unified Market for direct player inventory Sell commands.
 *
 * <p>Plain fungible stacks are routed through player Orders first and then the
 * server fallback. Metadata-sensitive stacks remain on SellService's exact
 * stack valuation path. Mixed inventories are partitioned instead of forcing
 * the entire command back through legacy settlement.</p>
 */
public final class MarketSellCommandListener
        implements Listener {

    private final Core core;
    private final MarketExchangeService exchange;

    public MarketSellCommandListener(
            Core core,
            MarketExchangeService exchange
    ) {
        this.core = core;
        this.exchange = exchange;
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onCommand(
            PlayerCommandPreprocessEvent event
    ) {
        Player player = event.getPlayer();

        if (!player.hasPermission("mineaclesell.use")) {
            return;
        }

        String raw = event.getMessage();

        if (raw.length() <= 1) {
            return;
        }

        String[] parts = raw.substring(1)
                .trim()
                .split("\\s+");

        if (parts.length != 2
                || !parts[0].equalsIgnoreCase("sell")) {
            return;
        }

        String mode = parts[1].toLowerCase(Locale.ROOT);

        if (mode.equals("hand")) {
            handleHand(event, player);
            return;
        }

        if (!mode.equals("all")
                && !mode.equals("inventory")) {
            return;
        }

        handleInventory(event, player);
    }

    private void handleHand(
            PlayerCommandPreprocessEvent event,
            Player player
    ) {
        List<MarketTransaction.SourceItem> sources =
                handSources(player);

        if (sources == null || sources.isEmpty()) {
            return;
        }

        MarketSellExecutionResult market =
                exchange.executePlayerSell(
                        player,
                        sources
                );

        if (!market.handled()) {
            return;
        }

        event.setCancelled(true);
        finishResult(
                player,
                market,
                LegacySale.empty()
        );
    }

    private void handleInventory(
            PlayerCommandPreprocessEvent event,
            Player player
    ) {
        InventorySelection selection =
                inventorySelection(player);

        /*
         * No canonical stack means the normal SellCommand owns the command
         * unchanged. This preserves exact valuation for a purely metadata-
         * sensitive inventory and avoids duplicating legacy Sell behavior.
         */
        if (selection.marketSources().isEmpty()) {
            return;
        }

        MarketSellExecutionResult market =
                exchange.executePlayerSell(
                        player,
                        selection.marketSources()
                );

        if (!market.handled()) {
            return;
        }

        event.setCancelled(true);

        if (!market.accepted()) {
            finishResult(
                    player,
                    market,
                    LegacySale.empty()
            );
            return;
        }

        LegacySale legacy =
                executeLegacyFallback(
                        player,
                        selection.legacySources()
                );

        finishResult(
                player,
                market,
                legacy
        );
    }

    private void finishResult(
            Player player,
            MarketSellExecutionResult market,
            LegacySale legacy
    ) {
        if (!market.accepted()) {
            String message = market.message().isBlank()
                    ? "&cCould not process this sale"
                    : market.message();
            player.sendMessage(
                    TextColor.color(message)
            );
            player.sendActionBar(
                    GuiText.component(message)
            );
            SoundService.guiError(player, core);
            return;
        }

        long totalCents = safeAdd(
                market.totalCents(),
                legacy.totalCents()
        );
        long totalAmount = safeAdd(
                market.totalAmount(),
                legacy.totalAmount()
        );
        long serverCents = safeAdd(
                market.serverCents(),
                legacy.totalCents()
        );
        long serverAmount = safeAdd(
                market.serverAmount(),
                legacy.totalAmount()
        );
        String money = exchange.sellService()
                .format(totalCents);

        player.sendMessage(
                TextColor.color(
                        "&#bbbbbbSold &#D0AFFF"
                                + totalAmount
                                + "x items &#bbbbbbfor &#11fc7b+"
                                + money
                )
        );

        if (market.orderCents() > 0L
                && serverCents > 0L) {
            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbbOrders: &#D0AFFF"
                                    + market.orderAmount()
                                    + "x &#11fc7b+"
                                    + exchange.sellService()
                                    .format(market.orderCents())
                                    + " &#bbbbbb• Server: &#D0AFFF"
                                    + serverAmount
                                    + "x &#11fc7b+"
                                    + exchange.sellService()
                                    .format(serverCents)
                    )
            );
        } else if (market.orderCents() > 0L) {
            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbbFilled player Orders: &#D0AFFF"
                                    + market.orderAmount()
                                    + "x"
                    )
            );
        }

        if (!legacy.message().isBlank()) {
            player.sendMessage(
                    TextColor.color(
                            legacy.message()
                    )
            );
        }

        if (!market.completed()) {
            player.sendMessage(
                    TextColor.color(
                            "&#bbbbbbMarket payout is finishing through recovery"
                    )
            );
        }

        player.sendActionBar(
                GuiText.component(
                        "&#11fc7b+" + money
                )
        );
        SoundService.economyReceive(player, core);
    }

    private List<MarketTransaction.SourceItem> handSources(
            Player player
    ) {
        PlayerInventory inventory = player.getInventory();
        int slot = inventory.getHeldItemSlot();
        ItemStack raw = inventory.getItem(slot);

        if (!marketCompatibleSellable(
                player,
                raw
        )) {
            return null;
        }

        ItemStack clean =
                exchange.sellService()
                        .stripWorthLore(raw);

        return List.of(
                new MarketTransaction.SourceItem(
                        slot,
                        clean
                )
        );
    }

    private InventorySelection inventorySelection(
            Player player
    ) {
        SellService sell = exchange.sellService();
        ItemStack[] contents =
                player.getInventory()
                        .getStorageContents();
        List<MarketTransaction.SourceItem> market =
                new ArrayList<>();
        List<LegacySource> legacy =
                new ArrayList<>();

        for (int slot = 0;
             slot < contents.length;
             slot++) {
            ItemStack raw = contents[slot];

            if (raw == null
                    || raw.getType() == Material.AIR) {
                continue;
            }

            ItemStack clean =
                    sell.stripWorthLore(raw);

            if (clean == null
                    || clean.getType() == Material.AIR) {
                continue;
            }

            var quote = sell.quote(
                    player.getUniqueId(),
                    clean
            );

            if (!quote.sellable()
                    || quote.totalCents() <= 0L) {
                continue;
            }

            if (exchange.isNonCanonicalOrderStack(clean)) {
                legacy.add(
                        new LegacySource(
                                slot,
                                clean
                        )
                );
            } else {
                market.add(
                        new MarketTransaction.SourceItem(
                                slot,
                                clean
                        )
                );
            }
        }

        return new InventorySelection(
                List.copyOf(market),
                List.copyOf(legacy)
        );
    }

    /**
     * Executes only the non-canonical portion after Market has durably taken
     * ownership of the canonical source set. Every fallback stack is detached
     * from the live inventory before SellService sees it and restored on any
     * pre-credit failure.
     */
    private LegacySale executeLegacyFallback(
            Player player,
            List<LegacySource> rawSources
    ) {
        if (rawSources == null || rawSources.isEmpty()) {
            return LegacySale.empty();
        }

        PlayerInventory inventory = player.getInventory();
        Inventory temporary = Bukkit.createInventory(
                null,
                54
        );
        List<LegacySource> detached =
                new ArrayList<>();

        for (LegacySource source : rawSources) {
            ItemStack current =
                    inventory.getItem(source.slot());

            if (!sameExactStack(
                    current,
                    source.item()
            )) {
                continue;
            }

            temporary.setItem(
                    source.slot(),
                    source.item().clone()
            );
            inventory.setItem(
                    source.slot(),
                    null
            );
            detached.add(source);
        }

        if (detached.isEmpty()) {
            return LegacySale.empty();
        }

        SaleResult result;

        try {
            result = exchange.sellService()
                    .sellInventory(
                            player.getUniqueId(),
                            temporary
                    );
        } catch (RuntimeException exception) {
            restoreDetached(
                    player,
                    detached
            );
            core.getLogger().log(
                    Level.SEVERE,
                    "Mixed /sell fallback failed before payout for "
                            + player.getUniqueId(),
                    exception
            );
            return new LegacySale(
                    0L,
                    0L,
                    "&cSome items could not be sold and were returned"
            );
        }

        if (!result.soldAnything()) {
            restoreDetached(
                    player,
                    detached
            );
            return new LegacySale(
                    0L,
                    0L,
                    result.failureMessage().isBlank()
                            ? "&cSome items could not be sold and were returned"
                            : result.failureMessage()
            );
        }

        restoreReturned(
                player,
                detached,
                result.returnedItems()
        );

        return new LegacySale(
                result.totalCents(),
                result.totalAmount(),
                result.failureMessage()
        );
    }

    private void restoreDetached(
            Player player,
            List<LegacySource> sources
    ) {
        PlayerInventory inventory = player.getInventory();

        for (LegacySource source : sources) {
            ItemStack current =
                    inventory.getItem(source.slot());

            if (current == null
                    || current.getType() == Material.AIR) {
                inventory.setItem(
                        source.slot(),
                        source.item().clone()
                );
                continue;
            }

            returnItem(
                    player,
                    source.item()
            );
        }
    }

    private void restoreReturned(
            Player player,
            List<LegacySource> sources,
            List<ItemStack> returnedItems
    ) {
        if (returnedItems == null
                || returnedItems.isEmpty()) {
            return;
        }

        PlayerInventory inventory = player.getInventory();
        boolean[] used = new boolean[sources.size()];

        for (ItemStack rawReturned : returnedItems) {
            ItemStack returned = exchange.sellService()
                    .stripWorthLore(rawReturned);

            if (returned == null
                    || returned.getType() == Material.AIR) {
                continue;
            }

            boolean restored = false;

            for (int index = 0;
                 index < sources.size();
                 index++) {
                if (used[index]) {
                    continue;
                }

                LegacySource source = sources.get(index);
                ItemStack current =
                        inventory.getItem(source.slot());

                if ((current == null
                        || current.getType() == Material.AIR)
                        && sameExactStack(
                        returned,
                        source.item()
                )) {
                    inventory.setItem(
                            source.slot(),
                            returned
                    );
                    used[index] = true;
                    restored = true;
                    break;
                }
            }

            if (!restored) {
                returnItem(
                        player,
                        returned
                );
            }
        }
    }

    private void returnItem(
            Player player,
            ItemStack item
    ) {
        if (item == null
                || item.getType() == Material.AIR) {
            return;
        }

        Map<Integer, ItemStack> leftovers =
                player.getInventory()
                        .addItem(item.clone());

        for (ItemStack leftover : leftovers.values()) {
            player.getWorld()
                    .dropItemNaturally(
                            player.getLocation(),
                            leftover
                    );
        }
    }

    private boolean sameExactStack(
            ItemStack rawCurrent,
            ItemStack expected
    ) {
        if (rawCurrent == null
                || expected == null
                || rawCurrent.getType() == Material.AIR) {
            return false;
        }

        ItemStack current = exchange.sellService()
                .stripWorthLore(rawCurrent);

        return current != null
                && current.isSimilar(expected)
                && current.getAmount()
                == expected.getAmount();
    }

    private boolean marketCompatibleSellable(
            Player player,
            ItemStack raw
    ) {
        if (raw == null
                || raw.getType() == Material.AIR) {
            return false;
        }

        ItemStack clean =
                exchange.sellService()
                        .stripWorthLore(raw);

        if (clean == null
                || clean.getType() == Material.AIR
                || exchange.isNonCanonicalOrderStack(clean)) {
            return false;
        }

        var quote = exchange.sellService().quote(
                player.getUniqueId(),
                clean
        );

        return quote.sellable()
                && quote.totalCents() > 0L;
    }

    private long safeAdd(
            long left,
            long right
    ) {
        try {
            return Math.addExact(
                    Math.max(0L, left),
                    Math.max(0L, right)
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private record InventorySelection(
            List<MarketTransaction.SourceItem> marketSources,
            List<LegacySource> legacySources
    ) {
    }

    private record LegacySource(
            int slot,
            ItemStack item
    ) {
        private LegacySource {
            item = item == null
                    ? null
                    : item.clone();
        }
    }

    private record LegacySale(
            long totalCents,
            long totalAmount,
            String message
    ) {
        private LegacySale {
            totalCents = Math.max(0L, totalCents);
            totalAmount = Math.max(0L, totalAmount);
            message = message == null ? "" : message;
        }

        private static LegacySale empty() {
            return new LegacySale(
                    0L,
                    0L,
                    ""
            );
        }
    }
}
