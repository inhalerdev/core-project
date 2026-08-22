package net.mineacle.core.orders.service;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.market.service.MarketExchangeService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.storage.OrdersRepository;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public final class OrderService {

    public enum CreationResult {
        SUCCESS(false),
        DISABLED(false),
        INVALID_ITEM(false),
        INVALID_AMOUNT(false),
        TOO_MANY_ACTIVE(false),
        ECONOMY_NOT_READY(false),
        INVALID_PRICE(true),
        PRICE_TOO_LOW(true),
        INSUFFICIENT_FUNDS(true),
        STORAGE_ERROR(false);

        private final boolean retryPrice;

        CreationResult(boolean retryPrice) {
            this.retryPrice = retryPrice;
        }

        public boolean retryPrice() {
            return retryPrice;
        }
    }

    private final Core core;
    private final OrdersRepository repository;
    private final MarketExchangeService marketExchange;

    public OrderService(
            Core core,
            OrdersRepository repository,
            MarketExchangeService marketExchange
    ) {
        this.core = core;
        this.repository = repository;
        this.marketExchange = marketExchange;
    }

    public boolean enabled() {
        return core.getConfig().getBoolean(
                "orders.enabled",
                true
        );
    }

    public void reload() {
        repository.save();
    }

    /**
     * Public market view. Orders that have fallen below the current guaranteed
     * /sell floor remain owner-cancellable but are not exposed as executable
     * resting bids.
     */
    public List<OrderRecord> activeOrders() {
        List<OrderRecord> result =
                new ArrayList<>();

        for (OrderRecord order : repository.active()) {
            if (order == null
                    || !order.active()
                    || order.remainingAmount() <= 0
                    || isOrderMaterialRejected(order.material())
                    || belowCurrentServerFloor(order)) {
                continue;
            }

            result.add(order);
        }

        result.sort(
                java.util.Comparator
                        .comparingLong(
                                OrderRecord::pricePerItemCents
                        )
                        .reversed()
                        .thenComparingLong(
                                OrderRecord::createdAtMillis
                        )
                        .thenComparing(
                                order -> order.id().toString()
                        )
        );

        return List.copyOf(result);
    }

    public List<OrderRecord> ownerOrders(
            UUID ownerId
    ) {
        if (ownerId == null) {
            return List.of();
        }

        return List.copyOf(
                repository.byOwner(ownerId)
        );
    }

    public OrderRecord get(UUID id) {
        return repository.get(id);
    }

    public long minimumOrderUnitCents(
            Material material
    ) {
        EconomyService economy =
                EconomyModule.economyService();
        long configured =
                economy == null
                        ? 1L
                        : configuredMinimumPricePerItem(
                                economy
                        );

        return marketExchange.minimumOrderUnitCents(
                material,
                configured
        );
    }


    public boolean isOrderMaterialRejected(
            Material material
    ) {
        return !marketExchange.isFungibleOrderMaterial(material);
    }


    public boolean create(
            Player player,
            int amount,
            String rawPriceEach
    ) {
        if (player == null) {
            return false;
        }

        ItemStack hand = player.getInventory()
                .getItemInMainHand();

        return createDetailed(
                player,
                hand.getType(),
                amount,
                rawPriceEach
        ) == CreationResult.SUCCESS;
    }

    public boolean create(
            Player player,
            Material material,
            int amount,
            String rawPriceEach
    ) {
        return createDetailed(
                player,
                material,
                amount,
                rawPriceEach
        ) == CreationResult.SUCCESS;
    }

    public synchronized CreationResult createDetailed(
            Player player,
            Material material,
            int amount,
            String rawPriceEach
    ) {
        if (player == null) {
            return CreationResult.INVALID_ITEM;
        }

        if (!enabled()) {
            error(
                    player,
                    message(
                            "disabled",
                            "&cOrders are currently disabled"
                    )
            );
            return CreationResult.DISABLED;
        }

        if (material == null
                || material == Material.AIR
                || !material.isItem()) {
            error(
                    player,
                    message(
                            "hold-item",
                            "&cChoose an item from the order menu"
                    )
            );
            return CreationResult.INVALID_ITEM;
        }

        if (!marketExchange.isFungibleOrderMaterial(material)) {
            error(
                    player,
                    "&cThat item is Auction House only &#bbbbbb— Orders accept plain fungible commodities"
            );
            return CreationResult.INVALID_ITEM;
        }

        if (amount <= 0) {
            error(
                    player,
                    message(
                            "invalid-amount",
                            "&cAmount must be greater than 0"
                    )
            );
            return CreationResult.INVALID_AMOUNT;
        }

        int maximumAmount = Math.max(
                1,
                core.getConfig().getInt(
                        "orders.limits.max-amount",
                        2304
                )
        );

        if (amount > maximumAmount) {
            error(
                    player,
                    message(
                            "max-amount",
                            "&cThat order amount is too high"
                    ).replace(
                            "%max%",
                            String.valueOf(maximumAmount)
                    )
            );
            return CreationResult.INVALID_AMOUNT;
        }

        int maximumActive = maxActiveOrders(
                player
        );

        if (repository.activeCountByOwner(
                player.getUniqueId()
        ) >= maximumActive) {
            error(
                    player,
                    message(
                            "max-active",
                            "&cYou have too many active orders"
                    ).replace(
                            "%max%",
                            String.valueOf(maximumActive)
                    )
            );
            return CreationResult.TOO_MANY_ACTIVE;
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null || !economy.enabled()) {
            error(
                    player,
                    message(
                            "economy-not-ready",
                            "&cEconomy is not ready"
                    )
            );
            return CreationResult.ECONOMY_NOT_READY;
        }

        long limitPriceEach = economy.parseAmountToCents(
                rawPriceEach
        );

        if (limitPriceEach <= 0L) {
            error(
                    player,
                    "&cType a price per item like 10, 250, or 1k"
            );
            return CreationResult.INVALID_PRICE;
        }

        long minimumEach =
                marketExchange.minimumOrderUnitCents(
                        material,
                        configuredMinimumPricePerItem(
                                economy
                        )
                );

        if (limitPriceEach < minimumEach) {
            floorError(
                    player,
                    material,
                    amount,
                    minimumEach,
                    economy
            );
            return CreationResult.PRICE_TOO_LOW;
        }

        long escrow = safeRequiredTotal(
                limitPriceEach,
                amount
        );

        if (escrow == Long.MAX_VALUE) {
            error(
                    player,
                    "&cThat order value is too large"
            );
            return CreationResult.INVALID_PRICE;
        }

        long tax = creationTax(escrow);

        if (tax < 0L) {
            error(
                    player,
                    "&cThat order value is too large"
            );
            return CreationResult.INVALID_PRICE;
        }

        long totalCost;

        try {
            totalCost = Math.addExact(
                    escrow,
                    tax
            );
        } catch (ArithmeticException exception) {
            error(
                    player,
                    "&cThat order value is too large"
            );
            return CreationResult.INVALID_PRICE;
        }

        /*
         * Transaction-boundary floor recheck. The v10 governor may publish a
         * new guaranteed Sell price while the player is typing their bid.
         */
        long finalMinimumEach =
                marketExchange.minimumOrderUnitCents(
                        material,
                        configuredMinimumPricePerItem(
                                economy
                        )
                );

        if (limitPriceEach < finalMinimumEach) {
            floorError(
                    player,
                    material,
                    amount,
                    finalMinimumEach,
                    economy
            );
            return CreationResult.PRICE_TOO_LOW;
        }

        UUID ownerId = player.getUniqueId();

        if (!economy.has(
                ownerId,
                totalCost
        )) {
            error(
                    player,
                    message(
                            "not-enough-money",
                            "&cYou do not have enough money"
                    )
            );
            send(
                    player,
                    "&#bbbbbbYou need &a"
                            + economy.format(totalCost)
            );
            send(
                    player,
                    "&#bbbbbbYour balance: &a"
                            + economy.format(
                            economy.getBalanceCents(
                                    ownerId
                            )
                    )
            );
            return CreationResult.INSUFFICIENT_FUNDS;
        }

        if (!economy.take(
                ownerId,
                totalCost
        )) {
            error(
                    player,
                    message(
                            "not-enough-money",
                            "&cYou do not have enough money"
                    )
            );
            return CreationResult.INSUFFICIENT_FUNDS;
        }

        OrderRecord order = OrderRecord.limitOrder(
                UUID.randomUUID(),
                ownerId,
                DisplayNames.displayName(player),
                material,
                amount,
                limitPriceEach,
                System.currentTimeMillis()
        );

        if (!repository.putDurable(order)) {
            if (!economy.tryGive(
                    ownerId,
                    totalCost
            )) {
                core.getLogger().severe(
                        "Could not refund failed durable Order creation for "
                                + ownerId
                );
            }
            error(
                    player,
                    message(
                            "storage-error",
                            "&cCould not queue your order"
                    )
            );
            return CreationResult.STORAGE_ERROR;
        }

        send(
                player,
                message(
                        "created",
                        "&#bbbbbbCreated order for "
                                + "&#B078FF%amount%x %item%"
                )
                        .replace(
                                "%amount%",
                                String.valueOf(amount)
                        )
                        .replace(
                                "%item%",
                                pretty(material)
                        )
        );
        send(
                player,
                "&#bbbbbbBid: &#11fc7b"
                        + economy.format(limitPriceEach)
                        + " &#bbbbbbeach — Escrow: &#11fc7b"
                        + economy.format(escrow)
        );

        if (tax > 0L) {
            send(
                    player,
                    "&#bbbbbbCreation tax: &a"
                            + economy.format(tax)
            );
        }

        SoundService.guiConfirm(
                player,
                core
        );
        return CreationResult.SUCCESS;
    }

    public synchronized void deliver(
            Player seller,
            OrderRecord suppliedOrder
    ) {
        if (seller == null || suppliedOrder == null) {
            return;
        }

        OrderRecord original = repository.get(
                suppliedOrder.id()
        );

        if (original == null
                || !original.active()
                || original.remainingAmount() <= 0) {
            error(
                    seller,
                    message(
                            "already-complete",
                            "&cThat order is already complete"
                    )
            );
            return;
        }

        if (isOrderMaterialRejected(original.material())) {
            error(
                    seller,
                    "&cThat legacy order can no longer accept deliveries &#bbbbbb— the buyer can cancel and refund it"
            );
            return;
        }

        if (belowCurrentServerFloor(original)) {
            long floor =
                    minimumOrderUnitCents(
                            original.material()
                    );
            error(
                    seller,
                    "&cThat order is below the current server price &#bbbbbb— /sell guarantees &#11fc7b"
                            + formatMoney(floor)
                            + " each"
            );
            return;
        }

        if (seller.getUniqueId().equals(
                original.ownerId()
        )) {
            error(
                    seller,
                    message(
                            "own-order",
                            "&cYou cannot deliver to your own order"
                    )
            );
            return;
        }

        int available = countItems(
                seller,
                original.material()
        );
        int deliveryAmount = Math.min(
                available,
                original.remainingAmount()
        );

        if (deliveryAmount <= 0) {
            error(
                    seller,
                    message(
                            "missing-items",
                            "&cYou do not have the required item"
                    )
            );
            return;
        }

        long payout = original.payoutFor(
                deliveryAmount
        );

        if (payout <= 0L) {
            error(
                    seller,
                    "&cThat order does not have enough escrow"
            );
            return;
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null || !economy.enabled()) {
            error(
                    seller,
                    message(
                            "economy-not-ready",
                            "&cEconomy is not ready"
                    )
            );
            return;
        }

        ItemStack[] inventoryBefore =
                cloneStorage(
                        seller.getInventory()
                );

        if (!removeItems(
                seller,
                original.material(),
                deliveryAmount
        )) {
            restoreStorage(
                    seller.getInventory(),
                    inventoryBefore
            );
            error(
                    seller,
                    message(
                            "missing-items",
                            "&cYou do not have the required item"
                    )
            );
            return;
        }

        OrderRecord updated = original.copy();
        updated.addDelivered(
                deliveryAmount,
                payout
        );

        if (!repository.putDurable(updated)) {
            restoreStorage(
                    seller.getInventory(),
                    inventoryBefore
            );
            error(
                    seller,
                    message(
                            "storage-error",
                            "&cCould not queue that delivery"
                    )
            );
            return;
        }

        if (!economy.tryGive(
                seller.getUniqueId(),
                payout
        )) {
            if (!repository.putDurable(original)) {
                core.getLogger().severe(
                        "Could not durably roll back failed Order payout "
                                + original.id()
                );
            }
            restoreStorage(
                    seller.getInventory(),
                    inventoryBefore
            );
            error(
                    seller,
                    "&cCould not add the delivery payout"
            );
            return;
        }

        send(
                seller,
                message(
                        "delivered",
                        "&#bbbbbbDelivered "
                                + "&#B078FF%amount%x %item% "
                                + "&#bbbbbbfor &a+%money%"
                )
                        .replace(
                                "%amount%",
                                String.valueOf(
                                        deliveryAmount
                                )
                        )
                        .replace(
                                "%item%",
                                pretty(
                                        original.material()
                                )
                        )
                        .replace(
                                "%money%",
                                economy.format(payout)
                        )
        );
        SoundService.economyReceive(
                seller,
                core
        );
    }

    public synchronized boolean collect(
            Player player,
            OrderRecord suppliedOrder
    ) {
        if (player == null || suppliedOrder == null) {
            return false;
        }

        OrderRecord original = repository.get(
                suppliedOrder.id()
        );

        if (original == null
                || !original.ownerId().equals(
                player.getUniqueId()
        )) {
            error(
                    player,
                    "&cThat order is not available"
            );
            return false;
        }

        int available =
                original.collectableAmount();

        if (available <= 0) {
            error(
                    player,
                    message(
                            "nothing-to-collect",
                            "&cThere are no items to collect"
                    )
            );
            return false;
        }

        PlayerInventory inventory =
                player.getInventory();
        ItemStack[] inventoryBefore =
                cloneStorage(inventory);
        int collected = addPlainItems(
                inventory,
                original.material(),
                available
        );

        if (collected <= 0) {
            restoreStorage(
                    inventory,
                    inventoryBefore
            );
            error(
                    player,
                    message(
                            "inventory-full",
                            "&cYour inventory is full"
                    )
            );
            return false;
        }

        OrderRecord updated = original.copy();
        updated.addCollected(collected);

        boolean accepted = updated.settled()
                ? repository.removeDurable(updated.id())
                : repository.putDurable(updated);

        if (!accepted) {
            restoreStorage(
                    inventory,
                    inventoryBefore
            );
            error(
                    player,
                    message(
                            "storage-error",
                            "&cCould not queue collected items"
                    )
            );
            return false;
        }

        send(
                player,
                message(
                        "collected",
                        "&#bbbbbbCollected "
                                + "&#B078FF%amount%x %item%"
                )
                        .replace(
                                "%amount%",
                                String.valueOf(collected)
                        )
                        .replace(
                                "%item%",
                                pretty(
                                        original.material()
                                )
                        )
        );
        SoundService.guiConfirm(
                player,
                core
        );
        return true;
    }

    public synchronized boolean cancel(
            Player player,
            OrderRecord suppliedOrder
    ) {
        if (player == null || suppliedOrder == null) {
            return false;
        }

        OrderRecord original = repository.get(
                suppliedOrder.id()
        );

        if (original == null
                || !original.ownerId().equals(
                player.getUniqueId()
        )) {
            error(
                    player,
                    "&cThat order is not available"
            );
            return false;
        }

        if (!original.active()) {
            error(
                    player,
                    "&cThat order is already closed"
            );
            return false;
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null || !economy.enabled()) {
            error(
                    player,
                    message(
                            "economy-not-ready",
                            "&cEconomy is not ready"
                    )
            );
            return false;
        }

        long refund =
                original.escrowRemainingCents();

        if (refund > 0L
                && !economy.tryGive(
                player.getUniqueId(),
                refund
        )) {
            error(
                    player,
                    "&cCould not return the order escrow"
            );
            return false;
        }

        OrderRecord updated = original.copy();
        updated.cancelAndRefund();

        boolean accepted = updated.settled()
                ? repository.removeDurable(updated.id())
                : repository.putDurable(updated);

        if (!accepted) {
            if (refund > 0L
                    && !economy.take(
                    player.getUniqueId(),
                    refund
            )) {
                core.getLogger().severe(
                        "Could not reverse failed Order cancellation refund for "
                                + original.id()
                );
            }

            error(
                    player,
                    message(
                            "storage-error",
                            "&cCould not queue order cancellation"
                    )
            );
            return false;
        }

        send(
                player,
                message(
                        "cancelled",
                        "&#bbbbbbCancelled order and refunded "
                                + "&a%refund%"
                ).replace(
                        "%refund%",
                        economy.format(refund)
                )
        );
        SoundService.guiCancel(
                player,
                core
        );
        return true;
    }

    public int countItems(
            Player player,
            Material material
    ) {
        if (player == null || material == null) {
            return 0;
        }

        int count = 0;

        for (ItemStack item :
                player.getInventory()
                        .getStorageContents()) {
            if (item == null
                    || item.getType() != material
                    || marketExchange.isNonCanonicalOrderStack(item)) {
                continue;
            }

            count += item.getAmount();
        }

        return count;
    }

    public long previewPayout(
            OrderRecord order,
            int amount
    ) {
        if (order == null) {
            return 0L;
        }

        OrderRecord current =
                repository.get(order.id());

        if (current == null
                || belowCurrentServerFloor(current)) {
            return 0L;
        }

        return current.payoutFor(amount);
    }

    public String ownerDisplayName(
            OrderRecord order
    ) {
        if (order == null) {
            return "";
        }

        OfflinePlayer owner =
                Bukkit.getOfflinePlayer(
                        order.ownerId()
                );
        String display =
                DisplayNames.displayName(owner);

        if (display == null
                || display.isBlank()) {
            return order.ownerName();
        }

        return display;
    }

    public String pretty(Material material) {
        if (material == null) {
            return "Unknown Item";
        }

        String[] parts =
                material.name()
                        .toLowerCase(Locale.ROOT)
                        .split("_");
        StringBuilder builder =
                new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(
                    Character.toUpperCase(
                            part.charAt(0)
                    )
            );

            if (part.length() > 1) {
                builder.append(
                        part.substring(1)
                );
            }
        }

        return builder.toString();
    }

    public boolean save() {
        return repository.save();
    }

    public void shutdown() {
        repository.shutdown();
    }

    private int maxActiveOrders(
            Player player
    ) {
        int configured = player.hasPermission(
                "mineacle.plus"
        )
                ? core.getConfig().getInt(
                        "orders.limits.max-active-plus",
                        25
                )
                : core.getConfig().getInt(
                        "orders.limits.max-active-default",
                        10
                );

        return Math.max(
                1,
                configured
        );
    }

    private long configuredMinimumPricePerItem(
            EconomyService economy
    ) {
        Object configured = core.getConfig().get(
                "orders.limits.minimum-price-per-item",
                "0.01"
        );
        long parsed =
                economy.parseAmountToCents(
                        String.valueOf(configured)
                );

        return Math.max(
                1L,
                parsed
        );
    }

    private boolean belowCurrentServerFloor(
            OrderRecord order
    ) {
        if (order == null
                || order.material() == null
                || order.remainingAmount() <= 0) {
            return true;
        }

        long floor =
                marketExchange.serverGuaranteedUnitCents(
                        order.material()
                );

        return floor > 0L
                && order.pricePerItemCents() < floor;
    }

    private long safeRequiredTotal(
            long unitCents,
            int amount
    ) {
        try {
            return Math.multiplyExact(
                    Math.max(
                            1L,
                            unitCents
                    ),
                    Math.max(
                            1,
                            amount
                    )
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void floorError(
            Player player,
            Material material,
            int amount,
            long minimumEach,
            EconomyService economy
    ) {
        long minimumTotal = safeRequiredTotal(
                minimumEach,
                amount
        );
        error(
                player,
                "&cOrder bid too low &#bbbbbb— "
                        + pretty(material)
                        + " is guaranteed at &#11fc7b"
                        + economy.format(minimumEach)
                        + " each &#bbbbbb(&#11fc7b"
                        + economy.format(minimumTotal)
                        + " escrow for "
                        + amount
                        + "x&#bbbbbb)"
        );
    }

    public String formatMoney(
            long cents
    ) {
        EconomyService economy =
                EconomyModule.economyService();

        return economy == null
                ? String.valueOf(cents)
                : economy.format(cents);
    }

    private long creationTax(long escrow) {
        double configured =
                core.getConfig().getDouble(
                        "orders.creation-tax-percent",
                        0.0D
                );

        if (!Double.isFinite(configured)) {
            configured = 0.0D;
        }

        double bounded = Math.clamp(
                configured,
                0.0D,
                100.0D
        );

        try {
            return BigDecimal.valueOf(escrow)
                    .multiply(
                            BigDecimal.valueOf(
                                    bounded
                            )
                    )
                    .divide(
                            BigDecimal.valueOf(
                                    100L
                            ),
                            0,
                            RoundingMode.HALF_UP
                    )
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return -1L;
        }
    }

    private boolean removeItems(
            Player player,
            Material material,
            int amount
    ) {
        PlayerInventory inventory =
                player.getInventory();
        ItemStack[] contents =
                cloneStorage(inventory);
        int remaining = amount;

        for (int index = 0;
             index < contents.length
                     && remaining > 0;
             index++) {
            ItemStack item = contents[index];

            if (item == null
                    || item.getType() != material
                    || marketExchange.isNonCanonicalOrderStack(item)) {
                continue;
            }

            int take = Math.min(
                    remaining,
                    item.getAmount()
            );
            int left = item.getAmount() - take;

            if (left <= 0) {
                contents[index] = null;
            } else {
                ItemStack reduced = item.clone();
                reduced.setAmount(left);
                contents[index] = reduced;
            }

            remaining -= take;
        }

        if (remaining > 0) {
            return false;
        }

        inventory.setStorageContents(contents);
        return true;
    }

    private int addPlainItems(
            PlayerInventory inventory,
            Material material,
            int amount
    ) {
        int remaining = amount;
        int maximumStack = Math.max(
                1,
                material.getMaxStackSize()
        );

        while (remaining > 0) {
            int stackAmount = Math.min(
                    maximumStack,
                    remaining
            );
            ItemStack stack = new ItemStack(
                    material,
                    stackAmount
            );
            int leftovers = inventory.addItem(
                            stack
                    )
                    .values()
                    .stream()
                    .mapToInt(
                            ItemStack::getAmount
                    )
                    .sum();
            int added =
                    stackAmount - leftovers;

            if (added <= 0) {
                break;
            }

            remaining -= added;

            if (leftovers > 0) {
                break;
            }
        }

        return amount - remaining;
    }

    private ItemStack[] cloneStorage(
            PlayerInventory inventory
    ) {
        ItemStack[] contents =
                inventory.getStorageContents();
        ItemStack[] copy =
                new ItemStack[contents.length];

        for (int index = 0;
             index < contents.length;
             index++) {
            copy[index] =
                    contents[index] == null
                            ? null
                            : contents[index].clone();
        }

        return copy;
    }

    private void restoreStorage(
            PlayerInventory inventory,
            ItemStack[] contents
    ) {
        try {
            inventory.setStorageContents(
                    contents
            );
        } catch (IllegalArgumentException exception) {
            core.getLogger().log(
                    Level.SEVERE,
                    "Could not restore an Orders inventory transaction",
                    exception
            );
        }
    }

    private String message(
            String key,
            String fallback
    ) {
        String value = core.getConfig().getString(
                "orders.messages." + key,
                fallback
        );

        return normalizePalette(value);
    }

    private String normalizePalette(
            String value
    ) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value
                .replace(
                        "&#ff55ff",
                        "&#8436FE"
                )
                .replace(
                        "&#FF55FF",
                        "&#8436FE"
                )
                .replace(
                        "&#ff88ff",
                        "&#B078FF"
                )
                .replace(
                        "&#FF88FF",
                        "&#B078FF"
                )
                .replace(
                        "&#cccccc",
                        "&#bbbbbb"
                )
                .replace(
                        "&#CCCCCC",
                        "&#bbbbbb"
                )
                .replace(
                        "&d",
                        "&#8436FE"
                )
                .replace(
                        "&f",
                        "&#f8f8f8"
                );
    }

    private void error(
            Player player,
            String message
    ) {
        send(
                player,
                message
        );
        SoundService.guiError(
                player,
                core
        );
    }

    private void send(
            Player player,
            String message
    ) {
        String colored =
                TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(
                LegacyComponentSerializer
                        .legacySection()
                        .deserialize(colored)
        );
    }
}
