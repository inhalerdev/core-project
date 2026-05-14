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

public final class OrderSearchInputListener implements Listener {

    private static final Set<UUID> SEARCHING = new HashSet<>();

    private final Core core;
    private final OrderService service;

    public OrderSearchInputListener(Core core, OrderService service) {
        this.core = core;
        this.service = service;
    }

    public static void begin(Player player) {
        SEARCHING.add(player.getUniqueId());
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        if (!SEARCHING.remove(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);

        String message = event.getMessage();

        core.getServer().getScheduler().runTask(core, () -> {
            OrdersMainGui.setSearch(player, message);
            OrdersMainGui.open(player, service);
        });
    }
}
