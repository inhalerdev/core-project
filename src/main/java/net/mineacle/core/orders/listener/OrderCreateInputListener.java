package net.mineacle.core.orders.listener;

import net.mineacle.core.Core;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class OrderCreateInputListener implements Listener {

    private static final Set<UUID> CREATING = new HashSet<>();

    private final Core core;
    private final OrderService service;

    public OrderCreateInputListener(Core core, OrderService service) {
        this.core = core;
        this.service = service;
    }

    public static void begin(Player player) {
        CREATING.add(player.getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (!CREATING.remove(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        String message = event.getMessage();

        core.getServer().getScheduler().runTask(core, () -> {
            if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("cancelled")) {
                OrdersMainGui.open(player, service);
                return;
            }

            String[] parts = message.trim().split("\\s+");

            if (parts.length < 2) {
                player.sendMessage("§cUse amount and price, like: 64 10");
                OrdersMainGui.open(player, service);
                return;
            }

            int amount;

            try {
                amount = Integer.parseInt(parts[0]);
            } catch (NumberFormatException exception) {
                player.sendMessage("§cInvalid amount");
                OrdersMainGui.open(player, service);
                return;
            }

            service.create(player, amount, parts[1]);
            OrdersMainGui.open(player, service);
        });
    }
}
