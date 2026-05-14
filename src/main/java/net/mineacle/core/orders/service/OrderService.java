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
        if (player == null) {
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

        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand == null || hand.getType() == Material.AIR) {
            send(player, message("hold-item", "&cHold the item you want to order"));
            SoundService.guiError(player, core);
            return false;
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            send(player, message("economy-not-ready", "&cEconomy is not ready"));
            SoundService.guiError(player, core);
            return false;
        }

        long pricePerItem = economy.amountToCents(parseBigDecimal(rawPrice));
        long minimumPrice = economy.amountToCents(BigDecimal.valueOf(core.getConfig().getDouble("orders.limits.minimum-price-per-item", 0.01D)));

        if (pricePerItem < minimumPrice) {
            send(player, message("minimum-price", "&cPrice is too low").replace("%minimum%", economy.format(minimumPrice)));
            SoundService.guiError(player, core);
            return false;
        }

        long subtotal = pricePerItem * amount;
        double taxPercent = Math.max(0.0D, core.getConfig().getDouble("orders.creation-tax-percent", 0.0D));
        long tax = Math.round(subtotal * (taxPercent / 100.0D));
        long total = subtotal + tax;

        if (!economy.take(player.getUniqueId(), total)) {
            send(player, message("not-enough-money", "&cYou do not have enough money"));
            SoundService.guiError(player, core);
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

        send(player, message("created", "&#ccccccCreated order for &d%amount%x %item%")
                .replace("%amount%", String.valueOf(amount))
                .replace("%item%", pretty(order.material()))
                .replace("%price%", economy.format(pricePerItem))
                .replace("%total%", economy.format(subtotal))
                .replace("%tax%", economy.format(tax)));

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

        send(seller, message("delivered", "&#ccccccDelivered &d%amount%x %item% &#ccccccfor &a+%money%")
                .replace("%amount%", String.valueOf(deliverAmount))
                .replace("%item%", pretty(order.material()))
                .replace("%money%", amount));

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

        int amount = order.collectableAmount();

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

        order.addCollected(collected);
        repository.put(order);

        send(player, message("collected", "&#ccccccCollected &d%amount%x %item%")
                .replace("%amount%", String.valueOf(collected))
                .replace("%item%", pretty(order.material())));

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

        send(player, message("cancelled", "&#ccccccCancelled order and refunded &a%refund%")
                .replace("%refund%", refundText));

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

    private BigDecimal parseBigDecimal(String raw) {
        try {
            return new BigDecimal(raw);
        } catch (Exception exception) {
            return BigDecimal.valueOf(-1L);
        }
    }

    private String message(String key, String fallback) {
        return core.getConfig().getString("orders.messages." + key, fallback);
    }

    private void send(Player player, String message) {
        player.sendMessage(TextColor.color(message));
    }
}
