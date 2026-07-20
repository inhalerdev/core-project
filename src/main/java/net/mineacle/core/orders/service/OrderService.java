package net.mineacle.core.orders.service;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
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
import java.util.Comparator;
import java.util.List;
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

    public OrderService(
            Core core,
            OrdersRepository repository
    ) {
        this.core = core;
        this.repository = repository;
    }

    public boolean enabled() {
        return core.getConfig().getBoolean(
                "orders.enabled",
                true
        );
    }

    public synchronized void reload() {
        repository.load();
    }

    public List<OrderRecord> activeOrders() {
        return repository.all().stream()
                .filter(OrderRecord::active)
                .filter(order -> order.remainingAmount() > 0)
                .sorted(
                        Comparator.comparingLong(
                                OrderRecord::createdAtMillis
                        ).reversed()
                )
                .toList();
    }

    public List<OrderRecord> ownerOrders(UUID ownerId) {
        if (ownerId == null) {
            return List.of();
        }

        return repository.all().stream()
                .filter(
                        order -> order.ownerId()
                                .equals(ownerId)
                )
                .sorted(
                        Comparator.comparingLong(
                                OrderRecord::createdAtMillis
                        ).reversed()
                )
                .toList();
    }

    public OrderRecord get(UUID id) {
        return repository.get(id);
    }

    public boolean create(
            Player player,
            int amount,
            String rawPrice
    ) {
        if (player == null) {
            return false;
        }

        ItemStack hand = player.getInventory()
                .getItemInMainHand();
        Material material = hand == null
                ? Material.AIR
                : hand.getType();

        return createDetailed(
                player,
                material,
                amount,
                rawPrice
        ) == CreationResult.SUCCESS;
    }

    public boolean create(
            Player player,
            Material material,
            int amount,
            String rawTotalPay
    ) {
        return createDetailed(
                player,
                material,
                amount,
                rawTotalPay
        ) == CreationResult.SUCCESS;
    }

    public synchronized CreationResult createDetailed(
            Player player,
            Material material,
            int amount,
            String rawTotalPay
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

        int maximumActive = maxActiveOrders(player);

        if (activeOwnedCount(player.getUniqueId())
                >= maximumActive) {
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

        long escrow = economy.parseAmountToCents(
                rawTotalPay
        );

        if (escrow <= 0L) {
            error(
                    player,
                    message(
                            "invalid-price",
                            "&cType a price like 100k, 11.5M, or 250000"
                    )
            );
            return CreationResult.INVALID_PRICE;
        }

        long minimumEach = minimumPricePerItem(economy);
        long minimumTotal;

        try {
            minimumTotal = Math.multiplyExact(
                    minimumEach,
                    amount
            );
        } catch (ArithmeticException exception) {
            error(player, "&cThat order value is too large");
            return CreationResult.INVALID_PRICE;
        }

        if (escrow < minimumTotal) {
            error(
                    player,
                    message(
                            "minimum-price",
                            "&cPrice is too low"
                    ).replace(
                            "%minimum%",
                            economy.format(minimumTotal)
                    )
            );
            return CreationResult.PRICE_TOO_LOW;
        }

        long tax = creationTax(escrow);
        long totalCost;

        try {
            totalCost = Math.addExact(escrow, tax);
        } catch (ArithmeticException exception) {
            error(player, "&cThat order value is too large");
            return CreationResult.INVALID_PRICE;
        }

        UUID ownerId = player.getUniqueId();

        if (!economy.has(ownerId, totalCost)) {
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
                            economy.getBalanceCents(ownerId)
                    )
            );
            return CreationResult.INSUFFICIENT_FUNDS;
        }

        if (!economy.take(ownerId, totalCost)) {
            error(
                    player,
                    message(
                            "not-enough-money",
                            "&cYou do not have enough money"
                    )
            );
            return CreationResult.INSUFFICIENT_FUNDS;
        }

        OrderRecord order = new OrderRecord(
                UUID.randomUUID(),
                ownerId,
                DisplayNames.displayName(player),
                material,
                amount,
                0,
                0,
                escrow,
                escrow,
                System.currentTimeMillis(),
                true
        );

        if (!repository.put(order)) {
            economy.tryGive(ownerId, totalCost);
            error(
                    player,
                    message(
                            "storage-error",
                            "&cCould not save your order"
                    )
            );
            return CreationResult.STORAGE_ERROR;
        }

        send(
                player,
                message(
                        "created",
                        "&#bbbbbbCreated order for "
                                + "&#ff88ff%amount%x %item%"
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
                "&#bbbbbbPlayers can earn &a"
                        + economy.format(escrow)
                        + " &#bbbbbbby completing it"
        );

        if (tax > 0L) {
            send(
                    player,
                    "&#bbbbbbCreation tax: &a"
                            + economy.format(tax)
            );
        }

        SoundService.guiConfirm(player, core);
        return CreationResult.SUCCESS;
    }

    public synchronized boolean deliver(
            Player seller,
            OrderRecord suppliedOrder
    ) {
        if (seller == null || suppliedOrder == null) {
            return false;
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
            return false;
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
            return false;
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
            return false;
        }

        long payout = original.payoutFor(
                deliveryAmount
        );

        if (payout <= 0L) {
            error(
                    seller,
                    "&cThat order does not have enough escrow"
            );
            return false;
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
            return false;
        }

        ItemStack[] inventoryBefore =
                cloneStorage(seller.getInventory());

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
            return false;
        }

        OrderRecord updated = original.copy();
        updated.addDelivered(
                deliveryAmount,
                payout
        );

        if (!repository.put(updated)) {
            restoreStorage(
                    seller.getInventory(),
                    inventoryBefore
            );
            error(
                    seller,
                    message(
                            "storage-error",
                            "&cCould not save that delivery"
                    )
            );
            return false;
        }

        if (!economy.tryGive(
                seller.getUniqueId(),
                payout
        )) {
            repository.put(original);
            restoreStorage(
                    seller.getInventory(),
                    inventoryBefore
            );
            error(
                    seller,
                    "&cCould not add the delivery payout"
            );
            return false;
        }

        send(
                seller,
                message(
                        "delivered",
                        "&#bbbbbbDelivered "
                                + "&#ff88ff%amount%x %item% "
                                + "&#bbbbbbfor &a+%money%"
                )
                        .replace(
                                "%amount%",
                                String.valueOf(deliveryAmount)
                        )
                        .replace(
                                "%item%",
                                pretty(original.material())
                        )
                        .replace(
                                "%money%",
                                economy.format(payout)
                        )
        );
        SoundService.economyReceive(seller, core);
        return true;
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
            error(player, "&cThat order is not available");
            return false;
        }

        int available = original.collectableAmount();

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

        boolean persisted = updated.settled()
                ? repository.remove(updated.id())
                : repository.put(updated);

        if (!persisted) {
            restoreStorage(
                    inventory,
                    inventoryBefore
            );
            error(
                    player,
                    message(
                            "storage-error",
                            "&cCould not save collected items"
                    )
            );
            return false;
        }

        send(
                player,
                message(
                        "collected",
                        "&#bbbbbbCollected "
                                + "&#ff88ff%amount%x %item%"
                )
                        .replace(
                                "%amount%",
                                String.valueOf(collected)
                        )
                        .replace(
                                "%item%",
                                pretty(original.material())
                        )
        );
        SoundService.guiConfirm(player, core);
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
            error(player, "&cThat order is not available");
            return false;
        }

        if (!original.active()) {
            error(player, "&cThat order is already closed");
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

        long refund = original.escrowRemainingCents();

        if (refund > 0L
                && !economy.tryGive(
                player.getUniqueId(),
                refund
        )) {
            error(player, "&cCould not return the order escrow");
            return false;
        }

        OrderRecord updated = original.copy();
        updated.cancelAndRefund();

        boolean persisted = updated.settled()
                ? repository.remove(updated.id())
                : repository.put(updated);

        if (!persisted) {
            if (refund > 0L) {
                economy.take(
                        player.getUniqueId(),
                        refund
                );
            }

            error(
                    player,
                    message(
                            "storage-error",
                            "&cCould not cancel that order"
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
        SoundService.guiCancel(player, core);
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

        for (ItemStack item : player.getInventory()
                .getStorageContents()) {
            if (item == null
                    || item.getType() != material) {
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

        OrderRecord current = repository.get(order.id());

        return current == null
                ? 0L
                : current.payoutFor(amount);
    }

    public String ownerDisplayName(OrderRecord order) {
        if (order == null) {
            return "";
        }

        OfflinePlayer owner = Bukkit.getOfflinePlayer(
                order.ownerId()
        );
        String display = DisplayNames.displayName(owner);

        if (display == null || display.isBlank()) {
            return order.ownerName();
        }

        return display;
    }

    public String pretty(Material material) {
        if (material == null) {
            return "Unknown Item";
        }

        String[] parts = material.name()
                .toLowerCase()
                .split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(
                    Character.toUpperCase(part.charAt(0))
            );

            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }

        return builder.toString();
    }

    public boolean save() {
        return repository.save();
    }

    private int activeOwnedCount(UUID ownerId) {
        int count = 0;

        for (OrderRecord order : repository.all()) {
            if (order.ownerId().equals(ownerId)
                    && order.active()) {
                count++;
            }
        }

        return count;
    }

    private int maxActiveOrders(Player player) {
        if (player.hasPermission("mineacle.plus")) {
            return Math.max(
                    1,
                    core.getConfig().getInt(
                            "orders.limits.max-active-plus",
                            25
                    )
            );
        }

        return Math.max(
                1,
                core.getConfig().getInt(
                        "orders.limits.max-active-default",
                        10
                )
        );
    }

    private long minimumPricePerItem(
            EconomyService economy
    ) {
        Object configured = core.getConfig().get(
                "orders.limits.minimum-price-per-item",
                "0.01"
        );
        long parsed = economy.parseAmountToCents(
                String.valueOf(configured)
        );

        return parsed > 0L ? parsed : 1L;
    }

    private long creationTax(long escrow) {
        BigDecimal percent = BigDecimal.valueOf(
                Math.max(
                        0.0D,
                        core.getConfig().getDouble(
                                "orders.creation-tax-percent",
                                0.0D
                        )
                )
        );

        try {
            return BigDecimal.valueOf(escrow)
                    .multiply(percent)
                    .divide(
                            BigDecimal.valueOf(100L),
                            0,
                            RoundingMode.HALF_UP
                    )
                    .longValueExact();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
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
             index < contents.length && remaining > 0;
             index++) {
            ItemStack item = contents[index];

            if (item == null
                    || item.getType() != material) {
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
            int leftovers = inventory.addItem(stack)
                    .values()
                    .stream()
                    .filter(item -> item != null)
                    .mapToInt(ItemStack::getAmount)
                    .sum();
            int added = stackAmount - leftovers;

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
            copy[index] = contents[index] == null
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
            inventory.setStorageContents(contents);
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
        return core.getConfig().getString(
                "orders.messages." + key,
                fallback
        );
    }

    private void error(
            Player player,
            String message
    ) {
        send(player, message);
        SoundService.guiError(player, core);
    }

    private void send(
            Player player,
            String message
    ) {
        String colored = TextColor.color(message);
        player.sendMessage(colored);
        player.sendActionBar(
                LegacyComponentSerializer.legacySection()
                        .deserialize(colored)
        );
    }
}
