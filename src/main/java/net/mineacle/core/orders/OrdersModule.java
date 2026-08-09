package net.mineacle.core.orders;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.orders.command.OrderCommand;
import net.mineacle.core.orders.gui.OrdersViewState;
import net.mineacle.core.orders.listener.OrderCreateInputListener;
import net.mineacle.core.orders.listener.OrderSearchInputListener;
import net.mineacle.core.orders.listener.OrdersGuiListener;
import net.mineacle.core.orders.service.OrderService;
import net.mineacle.core.orders.storage.YamlOrdersRepository;
import org.bukkit.command.PluginCommand;

public final class OrdersModule extends Module {

    private static OrderService orderService;

    public static OrderService orderService() {
        return orderService;
    }

    @Override
    public String name() {
        return "Orders";
    }

    @Override
    public void enable(Core core) {
        orderService = new OrderService(
                core,
                new YamlOrdersRepository(core)
        );

        PluginCommand command = core.getCommand("order");

        if (command == null) {
            orderService = null;
            throw new IllegalStateException(
                    "Missing command in plugin.yml: order"
            );
        }

        OrderCommand executor = new OrderCommand(
                core,
                orderService
        );
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        core.getServer().getPluginManager().registerEvents(
                new OrdersGuiListener(
                        core,
                        orderService
                ),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new OrderSearchInputListener(
                        core,
                        orderService
                ),
                core
        );
        core.getServer().getPluginManager().registerEvents(
                new OrderCreateInputListener(
                        core,
                        orderService
                ),
                core
        );
    }

    @Override
    public void disable() {
        if (orderService != null) {
            orderService.save();
        }

        OrdersViewState.clearAll();
        OrderSearchInputListener.clearAll();
        OrderCreateInputListener.clearAll();
        orderService = null;
    }
}
