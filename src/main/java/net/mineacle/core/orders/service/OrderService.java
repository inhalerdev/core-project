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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public final class OrderService {

    private final Core core;
    private final OrdersRepository repository;

    public OrderService(Core core, OrdersRepository repository) {
        this.core = core;
        this.repository = repository;
    }

    public List<OrderRecord> activeOrders() {
        return repository.all().stream()
                .filter(OrderRecord::active)
                .filter(order -> order.remainingAmount() > 0)
                .sorted(Comparator.comparingLong(OrderRecord::createdAtMillis).reversed())
                .toList();
    }

    public List<OrderRecord> ownerOrders(UUID ownerId) {
        return repository.all().stream()
                .filter(order -> order.ownerId().equals(ownerId))
                .sorted(Comparator.comparingLong(OrderRecord::createdAtMillis).reversed())
                .toList();
    }

    public OrderRecord get(UUID id) {
        return repository.get(id);
    }

    public boolean create(Player player, int amount, String rawPrice) {
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand == null || hand.getType() == Material.AIR) {
            send(player, message("hold-item", "&cChoose an item from the order menu"));
            SoundService.guiError(player, core);
            return false;
        }

        return create(player, hand.getType(), amount, rawPrice);
    }

    public boolean create(Player player, Material material, int amount, String rawTotalPay) {
        if (player == null) {
            return false;
        }

        if (material == null || material == Material.AIR || !material.isItem()) {
            send(player, message("hold-item", "&cChoose an item from the order menu"));
            SoundService.guiError(player, core);
            return false;
        }

        if (amount <= 0) {
            send(player, message("invalid-amount", "&cAmount must be greater than 0"));
            SoundService.guiError(player, core);
            return false;
        }

        int maxAmount = core.getConfig().getInt("orders.limits.max-amount", 2304);

        if (amount > maxAmount) {
            send(player, message("max-amount", "&cThat order amount is too high").replace("%max%", String.valueOf(maxAmount)));
            SoundService.guiError(player, core);
            return false;
        }

        int maxActive = maxActiveOrders(player);

        if (activeOwnedCount(player.getUniqueId()) >= maxActive) {
            send(player, message("max-active", "&cYou have too many active orders").replace("%max%", String.valueOf(maxActive)));
            SoundService.guiError(player, core);
            return false;
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            send(player, message("economy-not-ready", "&cEconomy is not ready"));
            SoundService.guiError(player, core);
            return false;
        }

        /*
         * rawTotalPay is the total amount the buyer wants to pay for the whole order.
         * Example: 64 logs for 100k means total escrow is 100k, not 100k each.
         */
        long subtotal = economy.parseAmountToCents(rawTotalPay);

        if (subtotal <= 0L) {
            send(player, "&cType a price like 100k, 11.5M, or 250000");
            SoundService.guiError(player, core);
            return false;
        }

        long minimumTotal = economy.amountToCents(BigDecimal.valueOf(core.getConfig().getDouble("orders.limits.minimum-price-per-item", 0.01D)))
                * amount;

        if (subtotal < minimumTotal) {
            send(player, message("minimum-price", "&cPrice is too low").replace("%minimum%", economy.format(minimumTotal)));
            SoundService.guiError(player, core);
            return false;
        }

        long pricePerItem = Math.max(1L, subtotal / amount);
        long escrow = pricePerItem * amount;

        double taxPercent = Math.max(0.0D, core.getConfig().getDouble("orders.creation-tax-percent", 0.0D));
        long tax = Math.round(escrow * (taxPercent / 100.0D));
        long totalCost = escrow + tax;

        if (!economy.has(player.getUniqueId(), totalCost)) {
            send(player, "&cNot enough money");
            send(player, "&#bbbbbbYou need &a" + economy.format(totalCost) + " &#bbbbbbto create this order");
            send(player, "&#bbbbbbYour balance: &a" + economy.format(economy.getBalanceCents(player.getUniqueId())));
            SoundService.guiError(player, core);
            return false;
        }

        if (!economy.take(player.getUniqueId(), totalCost)) {
            send(player, "&cNot enough money");
            SoundService.guiError(player, core);
            return false;
        }

        OrderRecord order = new OrderRecord(
                UUID.randomUUID(),
                player.getUniqueId(),
                DisplayNames.displayName(player),
                material,
                amount,
                0,
                pricePerItem,
                escrow,
                System.currentTimeMillis(),
                true
        );

        repository.put(order);

        send(player, "&#bbbbbbOrder created: &#ff88ff" + amount + "x " + pretty(material));
        send(player, "&#bbbbbbPlayers can earn &a" + economy.format(escrow) + " &#bbbbbbby delivering it");
        SoundService.guiConfirm(player, core);
        return true;
    }

    public void deliver(Player seller, OrderRecord order) {
        if (seller == null || order == null) {
            return;
        }

        if (!order.active() || order.remainingAmount() <= 0) {
            send(seller, message("already-complete", "&cThat order is already complete"));
            SoundService.guiError(seller, core);
            return;
        }

        if (seller.getUniqueId().equals(order.ownerId())) {
            send(seller, message("own-order", "&cYou cannot deliver to your own order"));
            SoundService.guiError(seller, core);
            return;
        }

        int available = countItems(seller, order.material());
        int deliverAmount = Math.min(available, order.remainingAmount());

        if (deliverAmount <= 0) {
            send(seller, message("missing-items", "&cYou do not have the required item"));
            SoundService.guiError(seller, core);
            return;
        }

        long payout = deliverAmount * order.pricePerItemCents();

        if (payout > order.escrowRemainingCents()) {
            payout = order.escrowRemainingCents();
            deliverAmount = (int) Math.max(1L, payout / order.pricePerItemCents());
        }

        removeItems(seller, order.material(), deliverAmount);

        EconomyService economy = EconomyModule.economyService();

        if (economy != null) {
            economy.give(seller.getUniqueId(), payout);
        }

        order.addDelivered(deliverAmount);
        order.removeEscrow(payout);
        repository.put(order);

        String amount = economy == null ? "$" + payout : economy.format(payout);

        send(seller, "&#bbbbbbDelivered &#ff88ff" + deliverAmount + "x " + pretty(order.material()) + " &#bbbbbbfor &a+" + amount);
        SoundService.economyReceive(seller, core);
    }

    public void collect(Player player, OrderRecord order) {
        if (player == null || order == null) {
            return;
        }

        if (!order.ownerId().equals(player.getUniqueId())) {
            SoundService.guiError(player, core);
            return;
        }

        int amount = order.deliveredAmount();

        if (amount <= 0) {
            send(player, message("nothing-to-collect", "&cThere are no items to collect"));
            SoundService.guiError(player, core);
            return;
        }

        HashMap<Integer, ItemStack> leftover = player.getInventory().addItem(new ItemStack(order.material(), amount));

        int leftoverAmount = 0;

        for (ItemStack item : leftover.values()) {
            if (item != null) {
                leftoverAmount += item.getAmount();
            }
        }

        int collected = amount - leftoverAmount;

        if (collected <= 0) {
            send(player, message("inventory-full", "&cYour inventory is full"));
            SoundService.guiError(player, core);
            return;
        }

        order.addDelivered(-collected);
        repository.put(order);

        send(player, "&#bbbbbbCollected &#ff88ff" + collected + "x " + pretty(order.material()));
        SoundService.guiConfirm(player, core);
    }

    public void cancel(Player player, OrderRecord order) {
        if (player == null || order == null) {
            return;
        }

        if (!order.ownerId().equals(player.getUniqueId())) {
            SoundService.guiError(player, core);
            return;
        }

        if (!order.active()) {
            SoundService.guiError(player, core);
            return;
        }

        long refund = order.escrowRemainingCents();
        order.cancel();

        EconomyService economy = EconomyModule.economyService();

        if (economy != null && refund > 0L) {
            economy.give(player.getUniqueId(), refund);
        }

        repository.put(order);

        String refundText = economy == null ? "$" + refund : economy.format(refund);
        send(player, "&#bbbbbbCancelled order and refunded &a" + refundText);
        SoundService.guiCancel(player, core);
    }

    public int countItems(Player player, Material material) {
        int count = 0;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != material) {
                continue;
            }

            count += item.getAmount();
        }

        return count;
    }

    public String pretty(Material material) {
        String[] parts = material.name().toLowerCase().split("_");
        StringBuilder builder = new StringBuilder();

        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }

            if (!builder.isEmpty()) {
                builder.append(' ');
            }

            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }

        return builder.toString();
    }

    public void save() {
        repository.save();
    }

    private int activeOwnedCount(UUID ownerId) {
        int count = 0;

        for (OrderRecord order : repository.all()) {
            if (order.ownerId().equals(ownerId) && order.active()) {
                count++;
            }
        }

        return count;
    }

    private int maxActiveOrders(Player player) {
        if (player.hasPermission("mineacle.plus")) {
            return core.getConfig().getInt("orders.limits.max-active-plus", 25);
        }

        return core.getConfig().getInt("orders.limits.max-active-default", 10);
    }

    private void removeItems(Player player, Material material, int amount) {
        int remaining = amount;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != material) {
                continue;
            }

            int take = Math.min(remaining, item.getAmount());
            item.setAmount(item.getAmount() - take);
            remaining -= take;

            if (remaining <= 0) {
                return;
            }
        }
    }

    private String message(String key, String fallback) {
        return core.getConfig().getString("orders.messages." + key, fallback);
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
    }
}
