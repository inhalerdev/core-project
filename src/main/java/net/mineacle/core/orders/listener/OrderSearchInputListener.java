package net.mineacle.core.orders.listener;

import net.mineacle.core.Core;
import net.mineacle.core.orders.gui.OrderCreateGui;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class OrderSearchInputListener implements Listener {

    private static final Set<UUID> SEARCHING_MAIN = new HashSet<>();
    private static final Set<UUID> SEARCHING_CREATE = new HashSet<>();

    private final Core core;
    private final OrderService service;

    public OrderSearchInputListener(Core core, OrderService service) {
        this.core = core;
        this.service = service;
    }

    public static void begin(Player player) {
        SEARCHING_MAIN.add(player.getUniqueId());
    }

    public static void beginCreateSearch(Player player) {
        SEARCHING_CREATE.add(player.getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        boolean main = SEARCHING_MAIN.remove(uuid);
        boolean create = SEARCHING_CREATE.remove(uuid);

        if (!main && !create) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();

        core.getServer().getScheduler().runTask(core, () -> {
            if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("cancelled")) {
                if (create) {
                    OrderCreateGui.open(player, service);
                } else {
                    OrdersMainGui.open(player, service);
                }

                return;
            }

            if (create) {
                OrderCreateGui.setSearch(player, message);
                OrderCreateGui.open(player, service);
                return;
            }

            OrdersMainGui.setSearch(player, message);
            OrdersMainGui.open(player, service);
        });
    }
}
