package net.mineacle.core.orders.gui;

import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.List;

public final class OrdersMainGui {

    public static final String TITLE = "Orders";

    private OrdersMainGui() {
    }

    public static void open(Player player, OrderService service) {
        Inventory inventory = Bukkit.createInventory(null, 54, TITLE);

        List<OrderRecord> orders = service.activeOrders();
        int slot = 0;

        for (OrderRecord order : orders) {
            if (slot >= 45) {
                break;
            }

            inventory.setItem(slot, orderItem(service, order));
            slot++;
        }

        inventory.setItem(45, item(Material.PLAYER_HEAD, "&dYour Orders", List.of(
                "&#ccccccView and cancel your active orders",
                "",
                "&d➥ &#ccccccClick to open"
        )));

        inventory.setItem(49, item(Material.SUNFLOWER, "&dRefresh", List.of(
                "&#ccccccRefresh the public order board",
                "",
                "&d➥ &#ccccccClick to refresh"
        )));

        inventory.setItem(53, item(Material.WRITABLE_BOOK, "&dCreate Order", List.of(
                "&#ccccccHold the item you want to order",
                "",
                "&d/order create <amount> <price_each>"
        )));

        player.openInventory(inventory);
    }

    public static ItemStack orderItem(OrderService service, OrderRecord order) {
        EconomyService economy = EconomyModule.economyService();
        String price = economy == null ? "$" + order.pricePerItemCents() : economy.format(order.pricePerItemCents());

        return item(order.material(), "&d" + service.pretty(order.material()), List.of(
                "&#ccccccBuyer: &d" + order.ownerName(),
                "&#ccccccRemaining: &d" + order.remainingAmount() + "&8/&d" + order.requestedAmount(),
                "&#ccccccPrice Each: &a" + price,
                "",
                "&d➥ &#ccccccClick to deliver"
        ));
    }

    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return item;
        }

        meta.setDisplayName(TextColor.color(name));
        meta.setLore(lore.stream().map(TextColor::color).toList());
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }
}
