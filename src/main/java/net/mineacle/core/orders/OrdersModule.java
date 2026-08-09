package net.mineacle.core.orders;

import net.mineacle.core.Core;
import net.mineacle.core.bootstrap.Module;
import net.mineacle.core.orders.command.OrderCommand;
import net.mineacle.core.orders.listener.OrderCreateInputListener;
import net.mineacle.core.orders.listener.OrderSearchInputListener;
import net.mineacle.core.orders.listener.OrdersGuiListener;
import net.mineacle.core.orders.service.OrderService;
import net.mineacle.core.orders.storage.YamlOrdersRepository;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;

public final class OrdersModule extends Module {

    private static OrderService orderService;

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

        OrderCommand command =
                new OrderCommand(
                        core,
                        orderService
                );

        registerOrderCommand(core, command);

        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new OrdersGuiListener(
                                core,
                                orderService
                        ),
                        core
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
                        new OrderSearchInputListener(
                                core,
                                orderService
                        ),
                        core
                );
        core.getServer()
                .getPluginManager()
                .registerEvents(
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
            orderService.shutdown();
            orderService = null;
        }
    }

    private void registerOrderCommand(
            Core core,
            CommandExecutor executor
    ) {
        PluginCommand command =
                core.getCommand("order");

        if (command == null) {
            throw new IllegalStateException(
                    "Missing command in plugin.yml: order"
            );
        }

        command.setExecutor(executor);

        if (executor instanceof TabCompleter completer) {
            command.setTabCompleter(completer);
        }
    }
}
