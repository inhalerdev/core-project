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
            player.sendMessage(TextColor.color("&cAmount must be greater than 0"));
            SoundService.guiError(player, core);
            return false;
        }

        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand == null || hand.getType() == Material.AIR) {
            player.sendMessage(TextColor.color("&cHold the item you want to order"));
            SoundService.guiError(player, core);
            return false;
        }

        EconomyService economy = EconomyModule.economyService();

        if (economy == null) {
            player.sendMessage(TextColor.color("&cEconomy is not ready"));
            SoundService.guiError(player, core);
            return false;
        }

        long pricePerItem = economy.amountToCents(parseBigDecimal(rawPrice));

        if (pricePerItem <= 0L) {
            player.sendMessage(TextColor.color("&cInvalid price"));
            SoundService.guiError(player, core);
            return false;
        }

        long total = pricePerItem * amount;

        if (!economy.take(player.getUniqueId(), total)) {
            player.sendMessage(TextColor.color("&cYou do not have enough money"));
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
                pricePerItem,
                total,
                System.currentTimeMillis(),
                true
        );

        repository.put(order);

        player.sendMessage(TextColor.color("&#ccccccCreated order for &d" + amount + "x " + pretty(order.material())));
        SoundService.guiConfirm(player, core);
        return true;
    }

    public void deliver(Player seller, OrderRecord order) {
        if (seller == null || order == null) {
            return;
        }

        if (!order.active() || order.remainingAmount() <= 0) {
            seller.sendMessage(TextColor.color("&cThat order is already complete"));
            SoundService.guiError(seller, core);
            return;
        }

        if (seller.getUniqueId().equals(order.ownerId())) {
            seller.sendMessage(TextColor.color("&cYou cannot deliver to your own order"));
            SoundService.guiError(seller, core);
            return;
        }

        int available = countItems(seller, order.material());
        int deliverAmount = Math.min(available, order.remainingAmount());

        if (deliverAmount <= 0) {
            seller.sendMessage(TextColor.color("&cYou do not have the required item"));
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
        seller.sendMessage(TextColor.color("&#ccccccDelivered &d" + deliverAmount + "x " + pretty(order.material()) + " &#ccccccfor &a+" + amount));
        SoundService.economyReceive(seller, core);
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

        order.cancel();

        EconomyService economy = EconomyModule.economyService();

        if (economy != null && order.escrowRemainingCents() > 0L) {
            economy.give(player.getUniqueId(), order.escrowRemainingCents());
        }

        repository.put(order);

        player.sendMessage(TextColor.color("&#ccccccCancelled order and refunded escrow"));
        SoundService.guiCancel(player, core);
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

    private int countItems(Player player, Material material) {
        int count = 0;

        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != material) {
                continue;
            }

            count += item.getAmount();
        }

        return count;
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
}
