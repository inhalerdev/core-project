package net.mineacle.core.orders.service;

import net.mineacle.core.Core;
import net.mineacle.core.common.player.DisplayNames;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.storage.OrdersRepository;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class OrderService {

    private final Core core;
    private final OrdersRepository repository;

    public OrderService(
            Core core,
            OrdersRepository repository
    ) {
        this.core = core;
        this.repository = repository;
    }

    public List<OrderRecord> activeOrders() {
        return List.copyOf(repository.active());
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

    public synchronized boolean create(
            Player player,
            int amount,
            String rawPrice
    ) {
        if (player == null) {
            return false;
        }

        if (amount <= 0) {
            fail(
                    player,
                    message(
                            "invalid-amount",
                            "&cAmount must be greater than 0"
                    )
            );
            return false;
        }

        int maxAmount = Math.max(
                1,
                core.getConfig().getInt(
                        "orders.limits.max-amount",
                        2304
                )
        );

        if (amount > maxAmount) {
            fail(
                    player,
                    message(
                            "max-amount",
                            "&cThat order amount is too high"
                    ).replace(
                            "%max%",
                            String.valueOf(maxAmount)
                    )
            );
            return false;
        }

        int maxActive = maxActiveOrders(player);

        if (repository.activeCountByOwner(
                player.getUniqueId()
        ) >= maxActive) {
            fail(
                    player,
                    message(
                            "max-active",
                            "&cYou have too many active orders"
                    ).replace(
                            "%max%",
                            String.valueOf(maxActive)
                    )
            );
            return false;
        }

        ItemStack hand =
                player.getInventory()
                        .getItemInMainHand();

        if (hand.getType() == Material.AIR) {
            fail(
                    player,
                    message(
                            "hold-item",
                            "&cHold the item you want to order"
                    )
            );
            return false;
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null || !economy.enabled()) {
            fail(
                    player,
                    message(
                            "economy-not-ready",
                            "&cEconomy is not ready"
                    )
            );
            return false;
        }

        long pricePerItem =
                parseAmountToCents(
                        economy,
                        rawPrice
                );
        long minimumPrice =
                minimumPriceCents(economy);

        if (pricePerItem < minimumPrice) {
            fail(
                    player,
                    message(
                            "minimum-price",
                            "&cPrice is too low"
                    ).replace(
                            "%minimum%",
                            economy.format(minimumPrice)
                    )
            );
            return false;
        }

        long subtotal;

        try {
            subtotal = Math.multiplyExact(
                    pricePerItem,
                    amount
            );
        } catch (ArithmeticException exception) {
            fail(
                    player,
                    "&cThat order total is too high"
            );
            return false;
        }

        long tax = creationTaxCents(subtotal);

        if (tax < 0L) {
            fail(
                    player,
                    "&cThat order total is too high"
            );
            return false;
        }

        long total;

        try {
            total = Math.addExact(
                    subtotal,
                    tax
            );
        } catch (ArithmeticException exception) {
            fail(
                    player,
                    "&cThat order total is too high"
            );
            return false;
        }

        if (!economy.take(
                player.getUniqueId(),
                total
        )) {
            fail(
                    player,
                    message(
                            "not-enough-money",
                            "&cYou do not have enough money"
                    )
            );
            return false;
        }

        OrderRecord order = new OrderRecord(
                UUID.randomUUID(),
                player.getUniqueId(),
                DisplayNames.displayName(player),
                hand.getType(),
                amount,
                0,
                0,
                pricePerItem,
                subtotal,
                System.currentTimeMillis(),
                true
        );

        repository.put(order);

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
                                pretty(order.material())
                        )
                        .replace(
                                "%price%",
                                economy.format(pricePerItem)
                        )
                        .replace(
                                "%total%",
                                economy.format(subtotal)
                        )
                        .replace(
                                "%tax%",
                                economy.format(tax)
                        )
        );
        SoundService.guiConfirm(player, core);
        return true;
    }

    public synchronized void deliver(
            Player seller,
            OrderRecord requestedOrder
    ) {
        if (seller == null || requestedOrder == null) {
            return;
        }

        OrderRecord order =
                repository.get(requestedOrder.id());

        if (order == null
                || !order.active()
                || order.remainingAmount() <= 0) {
            fail(
                    seller,
                    message(
                            "already-complete",
                            "&cThat order is already complete"
                    )
            );
            return;
        }

        if (seller.getUniqueId().equals(
                order.ownerId()
        )) {
            fail(
                    seller,
                    message(
                            "own-order",
                            "&cYou cannot deliver to your own order"
                    )
            );
            return;
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null || !economy.enabled()) {
            fail(
                    seller,
                    message(
                            "economy-not-ready",
                            "&cEconomy is not ready"
                    )
            );
            return;
        }

        int available = countItems(
                seller,
                order.material()
        );
        int deliverAmount = Math.min(
                available,
                order.remainingAmount()
        );

        if (deliverAmount <= 0) {
            fail(
                    seller,
                    message(
                            "missing-items",
                            "&cYou do not have the required item"
                    )
            );
            return;
        }

        long maxByEscrow =
                order.escrowRemainingCents()
                        / order.pricePerItemCents();

        deliverAmount = (int) Math.min(
                deliverAmount,
                maxByEscrow
        );

        if (deliverAmount <= 0) {
            fail(
                    seller,
                    message(
                            "already-complete",
                            "&cThat order is already complete"
                    )
            );
            return;
        }

        long payout;

        try {
            payout = Math.multiplyExact(
                    order.pricePerItemCents(),
                    deliverAmount
            );
        } catch (ArithmeticException exception) {
            fail(
                    seller,
                    "&cThat delivery value is too high"
            );
            return;
        }

        if (!removeItems(
                seller,
                order.material(),
                deliverAmount
        )) {
            fail(
                    seller,
                    message(
                            "missing-items",
                            "&cYou do not have the required item"
                    )
            );
            return;
        }

        if (!economy.tryGive(
                seller.getUniqueId(),
                payout
        )) {
            restoreItems(
                    seller,
                    order.material(),
                    deliverAmount
            );
            fail(
                    seller,
                    "&cCould not complete that delivery"
            );
            return;
        }

        order.addDelivered(deliverAmount);
        order.removeEscrow(payout);
        repository.put(order);

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
                                String.valueOf(deliverAmount)
                        )
                        .replace(
                                "%item%",
                                pretty(order.material())
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

    public synchronized void collect(
            Player player,
            OrderRecord requestedOrder
    ) {
        if (player == null || requestedOrder == null) {
            return;
        }

        OrderRecord order =
                repository.get(requestedOrder.id());

        if (order == null
                || !order.ownerId().equals(
                player.getUniqueId()
        )) {
            SoundService.guiError(player, core);
            return;
        }

        int amount = order.collectableAmount();

        if (amount <= 0) {
            fail(
                    player,
                    message(
                            "nothing-to-collect",
                            "&cThere are no items to collect"
                    )
            );
            return;
        }

        HashMap<Integer, ItemStack> leftover =
                player.getInventory().addItem(
                        new ItemStack(
                                order.material(),
                                amount
                        )
                );
        int leftoverAmount = 0;

        for (ItemStack item : leftover.values()) {
            leftoverAmount += item.getAmount();
        }

        int collected = amount - leftoverAmount;

        if (collected <= 0) {
            fail(
                    player,
                    message(
                            "inventory-full",
                            "&cYour inventory is full"
                    )
            );
            return;
        }

        order.addCollected(collected);
        repository.put(order);

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
                                pretty(order.material())
                        )
        );
        SoundService.guiConfirm(player, core);
    }

    public synchronized void cancel(
            Player player,
            OrderRecord requestedOrder
    ) {
        if (player == null || requestedOrder == null) {
            return;
        }

        OrderRecord order =
                repository.get(requestedOrder.id());

        if (order == null
                || !order.ownerId().equals(
                player.getUniqueId()
        )
                || !order.active()) {
            SoundService.guiError(player, core);
            return;
        }

        EconomyService economy =
                EconomyModule.economyService();

        if (economy == null || !economy.enabled()) {
            fail(
                    player,
                    message(
                            "economy-not-ready",
                            "&cEconomy is not ready"
                    )
            );
            return;
        }

        long refund = order.escrowRemainingCents();

        if (refund > 0L
                && !economy.tryGive(
                player.getUniqueId(),
                refund
        )) {
            fail(
                    player,
                    "&cCould not refund that order"
            );
            return;
        }

        order.removeEscrow(refund);
        order.cancel();
        repository.put(order);

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
                player.getInventory().getContents()) {
            if (item == null
                    || item.getType() != material) {
                continue;
            }

            count += item.getAmount();
        }

        return count;
    }

    public String pretty(Material material) {
        if (material == null) {
            return "";
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
            ).append(part.substring(1));
        }

        return builder.toString();
    }

    public void save() {
        repository.save();
    }

    public void shutdown() {
        repository.shutdown();
    }

    private int maxActiveOrders(Player player) {
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

        return Math.max(1, configured);
    }

    private long minimumPriceCents(
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

        return Math.max(1L, parsed);
    }

    private long creationTaxCents(long subtotal) {
        double configured = core.getConfig().getDouble(
                "orders.creation-tax-percent",
                0.0D
        );
        double bounded = Math.clamp(
                configured,
                0.0D,
                100.0D
        );

        try {
            return BigDecimal.valueOf(subtotal)
                    .multiply(
                            BigDecimal.valueOf(bounded)
                    )
                    .divide(
                            BigDecimal.valueOf(100L),
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
        if (amount <= 0
                || countItems(player, material) < amount) {
            return false;
        }

        int remaining = amount;

        for (ItemStack item :
                player.getInventory().getContents()) {
            if (item == null
                    || item.getType() != material) {
                continue;
            }

            int take = Math.min(
                    remaining,
                    item.getAmount()
            );
            item.setAmount(
                    item.getAmount() - take
            );
            remaining -= take;

            if (remaining == 0) {
                return true;
            }
        }

        return false;
    }

    private void restoreItems(
            Player player,
            Material material,
            int amount
    ) {
        HashMap<Integer, ItemStack> leftover =
                player.getInventory().addItem(
                        new ItemStack(
                                material,
                                amount
                        )
                );

        for (ItemStack item : leftover.values()) {
            player.getWorld().dropItemNaturally(
                    player.getLocation(),
                    item
            );
        }
    }

    private long parseAmountToCents(
            EconomyService economy,
            String raw
    ) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }

        String input = raw.trim()
                .replace(",", "")
                .replace("_", "")
                .toLowerCase(Locale.ROOT);
        BigDecimal multiplier =
                BigDecimal.ONE;

        if (input.endsWith("k")) {
            multiplier = BigDecimal.valueOf(
                    1_000L
            );
            input = input.substring(
                    0,
                    input.length() - 1
            );
        } else if (input.endsWith("m")) {
            multiplier = BigDecimal.valueOf(
                    1_000_000L
            );
            input = input.substring(
                    0,
                    input.length() - 1
            );
        } else if (input.endsWith("b")) {
            multiplier = BigDecimal.valueOf(
                    1_000_000_000L
            );
            input = input.substring(
                    0,
                    input.length() - 1
            );
        }

        try {
            return economy.amountToCents(
                    new BigDecimal(input)
                            .multiply(multiplier)
            );
        } catch (NumberFormatException exception) {
            return -1L;
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

    private String normalizePalette(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value
                .replace("&#ff55ff", "&#8436FE")
                .replace("&#FF55FF", "&#8436FE")
                .replace("&#ff88ff", "&#B078FF")
                .replace("&#FF88FF", "&#B078FF")
                .replace("&#cccccc", "&#bbbbbb")
                .replace("&#CCCCCC", "&#bbbbbb")
                .replace("&d", "&#8436FE")
                .replace("&f", "&#f8f8f8");
    }

    private void fail(
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
        player.sendMessage(
                TextColor.color(message)
        );
    }
}
