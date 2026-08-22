package net.mineacle.core.orders.gui;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.GuiText;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.economy.EconomyModule;
import net.mineacle.core.economy.service.EconomyService;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;

import java.util.List;

public final class OrderConfirmGui {

    public static final int SIZE = 27;
    public static final int CANCEL_SLOT = 11;
    public static final int ACTION_SLOT = 13;
    public static final int CONFIRM_SLOT = 15;

    private OrderConfirmGui() {
    }

    public static void openDeliver(
            Player player,
            OrderService service,
            OrderRecord order
    ) {
        OrdersGuiHolder holder =
                OrdersGuiHolder.confirm(
                        OrdersGuiHolder.Confirmation.DELIVER,
                        order.id()
                );
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                GuiText.title(titleDeliver())
        );
        holder.setInventory(inventory);

        int available = service.countItems(
                player,
                order.material()
        );
        int amount = Math.min(
                available,
                order.remainingAmount()
        );
        long payoutCents = service.previewPayout(
                order,
                amount
        );
        EconomyService economy =
                EconomyModule.economyService();
        String payout = economy == null
                ? "$" + payoutCents
                : economy.format(payoutCents);

        inventory.setItem(
                CANCEL_SLOT,
                cancelItem()
        );
        inventory.setItem(
                ACTION_SLOT,
                OrdersGuiItems.item(
                        Material.LIME_DYE,
                        OrdersGuiItems.cfg(
                                "orders.gui.confirm.deliver.name",
                                "&#B078FFConfirm Delivery"
                        ),
                        OrdersGuiItems.cfg(
                                "orders.gui.confirm.deliver.lore-1",
                                "&#bbbbbbDelivering: "
                                        + "&#D0AFFF%amount%x %item%"
                        )
                                .replace(
                                        "%amount%",
                                        String.valueOf(amount)
                                )
                                .replace(
                                        "%item%",
                                        service.pretty(
                                                order.material()
                                        )
                                ),
                        moneyLine(
                                OrdersGuiItems.cfg(
                                        "orders.gui.confirm.deliver.lore-2",
                                        "&#bbbbbbPayout: &#11fc7b%payout%"
                                )
                        ).replace(
                                "%payout%",
                                payout
                        ),
                        "",
                        OrdersGuiItems.cfg(
                                "orders.gui.confirm.deliver.lore-3",
                                "&#bbbbbbConfirm twice to deliver"
                        )
                )
        );
        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(false)
        );

        player.openInventory(inventory);
    }

    public static void openCancel(
            Player player,
            OrderService service,
            OrderRecord order
    ) {
        OrdersGuiHolder holder =
                OrdersGuiHolder.confirm(
                        OrdersGuiHolder.Confirmation.CANCEL_ORDER,
                        order.id()
                );
        Inventory inventory = Bukkit.createInventory(
                holder,
                SIZE,
                GuiText.title(titleCancel())
        );
        holder.setInventory(inventory);

        String refund = service.formatMoney(
                order.escrowRemainingCents()
        );

        inventory.setItem(
                CANCEL_SLOT,
                cancelItem()
        );
        inventory.setItem(
                ACTION_SLOT,
                OrdersGuiItems.item(
                        Material.RED_DYE,
                        OrdersGuiItems.cfg(
                                "orders.gui.confirm.cancel-order.name",
                                "&cCancel Order"
                        ),
                        moneyLine(
                                OrdersGuiItems.cfg(
                                        "orders.gui.confirm.cancel-order.lore-1",
                                        "&#bbbbbbRefund: &#11fc7b%refund%"
                                )
                        ).replace(
                                "%refund%",
                                refund
                        ),
                        "",
                        OrdersGuiItems.cfg(
                                "orders.gui.confirm.cancel-order.lore-2",
                                "&#bbbbbbConfirm twice to cancel this order"
                        )
                )
        );
        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(false)
        );

        player.openInventory(inventory);
    }

    public static boolean confirmReady(
            OrdersGuiHolder holder
    ) {
        return holder != null
                && holder.armed(
                System.currentTimeMillis()
        );
    }

    public static void arm(
            Core core,
            Player player,
            OrdersGuiHolder holder
    ) {
        int seconds = Math.max(
                1,
                core.getConfig().getInt(
                        "orders.confirm-timeout-seconds",
                        5
                )
        );
        long until = System.currentTimeMillis()
                + seconds * 1000L;
        holder.arm(until);

        Inventory inventory = holder.getInventory();
        inventory.setItem(
                CONFIRM_SLOT,
                confirmItem(true)
        );

        player.sendActionBar(
                GuiText.component(
                        "&#bbbbbbClick confirm again to continue"
                )
        );
        SoundService.guiConfirm(player, core);

        core.getServer().getScheduler().runTaskLater(
                core,
                () -> {
                    if (!player.isOnline()
                            || holder.armedUntilMillis()
                            != until) {
                        return;
                    }

                    Inventory current = player
                            .getOpenInventory()
                            .getTopInventory();

                    if (current.getHolder()
                            != holder) {
                        return;
                    }

                    holder.disarm();
                    current.setItem(
                            CONFIRM_SLOT,
                            confirmItem(false)
                    );
                    player.sendActionBar(
                            GuiText.component(
                                    "&cAction timed out"
                            )
                    );
                    SoundService.guiError(player, core);
                },
                seconds * 20L
        );
    }

    public static void disarm(
            OrdersGuiHolder holder
    ) {
        if (holder == null) {
            return;
        }

        holder.disarm();
        holder.getInventory().setItem(
                CONFIRM_SLOT,
                confirmItem(false)
        );
    }

    private static org.bukkit.inventory.ItemStack cancelItem() {
        return OrdersGuiItems.item(
                Material.RED_STAINED_GLASS_PANE,
                OrdersGuiItems.cfg(
                        "orders.gui.confirm.cancel.name",
                        "&cCancel"
                ),
                List.of(
                        OrdersGuiItems.cfg(
                                "orders.gui.confirm.cancel.lore",
                                "&#bbbbbbClick to go back"
                        )
                )
        );
    }

    private static org.bukkit.inventory.ItemStack confirmItem(
            boolean armed
    ) {
        if (armed) {
            return OrdersGuiItems.item(
                    Material.LIME_STAINED_GLASS_PANE,
                    "&#D0AFFFConfirm Again",
                    "&#bbbbbbClick again before the timer ends"
            );
        }

        return OrdersGuiItems.item(
                Material.LIME_STAINED_GLASS_PANE,
                OrdersGuiItems.cfg(
                        "orders.gui.confirm.confirm.name",
                        "&#B078FFConfirm"
                ),
                OrdersGuiItems.cfg(
                        "orders.gui.confirm.confirm.lore",
                        "&#bbbbbbClick twice to confirm"
                )
        );
    }

    private static String moneyLine(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        return value.replace("&a", "&#11fc7b");
    }

    private static String titleDeliver() {
        return OrdersGuiItems.cfg(
                "orders.gui.titles.confirm-deliver",
                "Confirm Delivery"
        );
    }

    private static String titleCancel() {
        return OrdersGuiItems.cfg(
                "orders.gui.titles.confirm-cancel",
                "Confirm Cancel"
        );
    }
}
