package net.mineacle.core.orders.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class OrderConfirmGui {

    public static final int SIZE = 27;
    public static final int CANCEL_SLOT = 11;
    public static final int INFO_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private static final Map<UUID, PendingAction> PENDING = new HashMap<>();

    private OrderConfirmGui() {
    }

    public static void openDeliver(Player player, OrderService service, OrderRecord order) {
        PENDING.put(player.getUniqueId(), new PendingAction(PendingType.DELIVER, order.id()));

        int available = service.countItems(player, order.material());
        int deliverAmount = Math.min(available, order.remainingAmount());
        EconomyService economy = EconomyModule.economyService();
        String payout = economy == null ? "$" + (deliverAmount * order.pricePerItemCents()) : economy.format(deliverAmount * order.pricePerItemCents());

        Inventory inventory = Bukkit.createInventory(null, SIZE, title("deliver"));

        inventory.setItem(CANCEL_SLOT, OrdersMainGui.item(Material.RED_STAINED_GLASS_PANE, cfg("orders.gui.confirm.cancel.name", "&cCANCEL"), List.of(
                cfg("orders.gui.confirm.cancel.lore", "&fClick to cancel")
        )));

        inventory.setItem(INFO_SLOT, OrdersMainGui.item(order.material(), cfg("orders.gui.confirm.deliver.name", "&aCONFIRM DELIVERY"), List.of(
                cfg("orders.gui.confirm.deliver.lore-1", "&fDelivering: &a%amount%x %item%")
                        .replace("%amount%", String.valueOf(deliverAmount))
                        .replace("%item%", service.pretty(order.material())),
                cfg("orders.gui.confirm.deliver.lore-2", "&fPayout: &a%payout%").replace("%payout%", payout),
                "",
                cfg("orders.gui.confirm.deliver.lore-3", "&fClick confirm to deliver")
        )));

        inventory.setItem(CONFIRM_SLOT, OrdersMainGui.item(Material.LIME_STAINED_GLASS_PANE, cfg("orders.gui.confirm.confirm.name", "&aCONFIRM"), List.of(
                cfg("orders.gui.confirm.confirm.lore", "&fClick to confirm")
        )));

        player.openInventory(inventory);
    }

    public static void openCancel(Player player, OrderRecord order) {
        PENDING.put(player.getUniqueId(), new PendingAction(PendingType.CANCEL, order.id()));

        EconomyService economy = EconomyModule.economyService();
        String refund = economy == null ? "$" + order.escrowRemainingCents() : economy.format(order.escrowRemainingCents());

        Inventory inventory = Bukkit.createInventory(null, SIZE, title("cancel"));

        inventory.setItem(CANCEL_SLOT, OrdersMainGui.item(Material.RED_STAINED_GLASS_PANE, cfg("orders.gui.confirm.cancel.name", "&cCANCEL"), List.of(
                cfg("orders.gui.confirm.cancel.lore", "&fClick to cancel")
        )));

        inventory.setItem(INFO_SLOT, OrdersMainGui.item(order.material(), cfg("orders.gui.confirm.cancel-order.name", "&cCANCEL ORDER"), List.of(
                cfg("orders.gui.confirm.cancel-order.lore-1", "&fRefund: &a%refund%").replace("%refund%", refund),
                "",
                cfg("orders.gui.confirm.cancel-order.lore-2", "&fClick confirm to cancel this order")
        )));

        inventory.setItem(CONFIRM_SLOT, OrdersMainGui.item(Material.LIME_STAINED_GLASS_PANE, cfg("orders.gui.confirm.confirm.name", "&aCONFIRM"), List.of(
                cfg("orders.gui.confirm.confirm.lore", "&fClick to confirm")
        )));

        player.openInventory(inventory);
    }

    public static boolean isTitle(String title) {
        if (title == null) {
            return false;
        }

        return title.equals(title("deliver")) || title.equals(title("cancel"));
    }

    public static PendingAction pending(Player player) {
        return PENDING.get(player.getUniqueId());
    }

    public static void clear(Player player) {
        PENDING.remove(player.getUniqueId());
    }

    private static String title(String type) {
        if (type.equalsIgnoreCase("cancel")) {
            return TextColor.color(cfg("orders.gui.titles.confirm-cancel", "CONFIRM CANCEL"));
        }

        return TextColor.color(cfg("orders.gui.titles.confirm-deliver", "CONFIRM DELIVERY"));
    }

    private static String cfg(String path, String fallback) {
        Core core = Core.instance();

        if (core == null) {
            return fallback;
        }

        return core.getConfig().getString(path, fallback);
    }

    public record PendingAction(PendingType type, UUID orderId) {
    }

    public enum PendingType {
        DELIVER,
        CANCEL
    }
}
