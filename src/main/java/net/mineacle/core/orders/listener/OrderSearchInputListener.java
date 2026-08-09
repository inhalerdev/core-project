package net.mineacle.core.orders.listener;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.orders.gui.OrderCreateGui;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrderSearchInputListener
        implements Listener {

    private static final Set<UUID> MAIN_SEARCH =
            ConcurrentHashMap.newKeySet();
    private static final Set<UUID> CREATE_SEARCH =
            ConcurrentHashMap.newKeySet();

    private final Core core;
    private final OrderService service;

    public OrderSearchInputListener(
            Core core,
            OrderService service
    ) {
        this.core = core;
        this.service = service;
    }

    public static void beginMain(Player player) {
        clear(player);
        OrderCreateInputListener.clear(player);
        MAIN_SEARCH.add(player.getUniqueId());
    }

    public static void beginCreate(Player player) {
        clear(player);
        OrderCreateInputListener.clear(player);
        CREATE_SEARCH.add(player.getUniqueId());
    }

    public static void clear(Player player) {
        if (player != null) {
            clear(player.getUniqueId());
        }
    }

    public static void clear(UUID playerId) {
        if (playerId == null) {
            return;
        }

        MAIN_SEARCH.remove(playerId);
        CREATE_SEARCH.remove(playerId);
    }

    public static void clearAll() {
        MAIN_SEARCH.clear();
        CREATE_SEARCH.clear();
    }

    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = false
    )
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        boolean main = MAIN_SEARCH.remove(playerId);
        boolean create = CREATE_SEARCH.remove(playerId);

        if (!main && !create) {
            return;
        }

        event.setCancelled(true);

        String input = PlainTextComponentSerializer
                .plainText()
                .serialize(event.message())
                .trim();

        if (input.length() > 64) {
            input = input.substring(0, 64);
        }

        String result = input;
        core.getServer().getScheduler().runTask(
                core,
                () -> process(
                        player,
                        main,
                        create,
                        result
                )
        );
    }

    private void process(
            Player player,
            boolean main,
            boolean create,
            String input
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (isCancel(input)) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbOrder search cancelled"
            ));
            SoundService.guiCancel(player, core);
            reopen(player, main, create);
            return;
        }

        if (isClear(input)) {
            if (create) {
                OrderCreateGui.clearSearch(player);
            } else {
                OrdersMainGui.clearSearch(player);
            }

            player.sendMessage(TextColor.color(
                    "&#bbbbbbOrder search cleared"
            ));
            SoundService.guiCancel(player, core);
            reopen(player, main, create);
            return;
        }

        if (create) {
            OrderCreateGui.setSearch(player, input);
        } else {
            OrdersMainGui.setSearch(player, input);
        }

        player.sendMessage(TextColor.color(
                "&#bbbbbbSearching orders for &#ff88ff"
                        + input
        ));
        SoundService.guiClick(player, core);
        reopen(player, main, create);
    }

    private void reopen(
            Player player,
            boolean main,
            boolean create
    ) {
        if (create) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> OrderCreateGui.open(
                            player,
                            service
                    )
            );
            return;
        }

        if (main) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> OrdersMainGui.open(
                            player,
                            service
                    )
            );
        }
    }

    private boolean isCancel(String input) {
        return input.equalsIgnoreCase("cancel")
                || input.equalsIgnoreCase("cancelled");
    }

    private boolean isClear(String input) {
        return input.isBlank()
                || input.equalsIgnoreCase("clear")
                || input.equalsIgnoreCase("reset");
    }
}
