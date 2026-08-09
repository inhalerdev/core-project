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
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class OrderCreateInputListener
        implements Listener {

    private static final Map<UUID, PendingOrder> PENDING =
            new ConcurrentHashMap<>();

    private final Core core;
    private final OrderService service;

    public OrderCreateInputListener(
            Core core,
            OrderService service
    ) {
        this.core = core;
        this.service = service;
    }

    public static void beginAmount(
            Player player,
            Material material
    ) {
        clear(player);
        OrderSearchInputListener.clear(player);
        PENDING.put(
                player.getUniqueId(),
                new PendingOrder(
                        material,
                        0,
                        Step.AMOUNT
                )
        );
    }

    public static void clear(Player player) {
        if (player != null) {
            clear(player.getUniqueId());
        }
    }

    public static void clear(UUID playerId) {
        if (playerId != null) {
            PENDING.remove(playerId);
        }
    }

    public static void clearAll() {
        PENDING.clear();
    }

    @EventHandler(
            priority = EventPriority.LOWEST,
            ignoreCancelled = false
    )
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        UUID playerId = player.getUniqueId();
        PendingOrder pending = PENDING.remove(playerId);

        if (pending == null) {
            return;
        }

        event.setCancelled(true);

        String input = PlainTextComponentSerializer
                .plainText()
                .serialize(event.message())
                .trim();

        core.getServer().getScheduler().runTask(
                core,
                () -> process(player, pending, input)
        );
    }

    private void process(
            Player player,
            PendingOrder pending,
            String input
    ) {
        if (!player.isOnline()) {
            return;
        }

        if (isCancel(input)) {
            OrderCreateGui.clearSelected(player);
            player.sendMessage(TextColor.color(
                    "&#bbbbbbOrder creation cancelled"
            ));
            SoundService.guiCancel(player, core);
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> OrdersMainGui.open(
                            player,
                            service
                    )
            );
            return;
        }

        if (pending.step() == Step.AMOUNT) {
            processAmount(player, pending, input);
            return;
        }

        processPrice(player, pending, input);
    }

    private void processAmount(
            Player player,
            PendingOrder pending,
            String input
    ) {
        int amount;

        try {
            amount = Integer.parseInt(
                    input.replace(",", "")
                            .replace("_", "")
            );
        } catch (NumberFormatException exception) {
            error(
                    player,
                    "&cType a whole number like 64"
            );
            requeueAmount(player, pending.material());
            return;
        }

        int maximum = Math.max(
                1,
                core.getConfig().getInt(
                        "orders.limits.max-amount",
                        2304
                )
        );

        if (amount <= 0) {
            error(
                    player,
                    "&cAmount must be greater than 0"
            );
            requeueAmount(player, pending.material());
            return;
        }

        if (amount > maximum) {
            error(
                    player,
                    "&cMaximum order amount: "
                            + maximum
            );
            requeueAmount(player, pending.material());
            return;
        }

        PENDING.put(
                player.getUniqueId(),
                new PendingOrder(
                        pending.material(),
                        amount,
                        Step.PRICE
                )
        );

        player.sendMessage(TextColor.color(""));
        player.sendMessage(TextColor.color(
                "&#bbbbbbHow much total money should players earn?"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbRequest: &#ff88ff"
                        + amount
                        + "x "
                        + service.pretty(pending.material())
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbExamples: &#ff88ff100k&#bbbbbb, "
                        + "&#ff88ff11.5M&#bbbbbb, "
                        + "&#ff88ff250000"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbType &#ff88ffcancel "
                        + "&#bbbbbbto stop"
        ));
    }

    private void processPrice(
            Player player,
            PendingOrder pending,
            String input
    ) {
        OrderService.CreationResult result =
                service.createDetailed(
                        player,
                        pending.material(),
                        pending.amount(),
                        input
                );

        if (result == OrderService.CreationResult.SUCCESS) {
            OrderCreateGui.clearSelected(player);
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> OrdersMainGui.open(
                            player,
                            service
                    )
            );
            return;
        }

        if (result.retryPrice()) {
            PENDING.put(
                    player.getUniqueId(),
                    pending
            );
            player.sendMessage(TextColor.color(
                    "&#bbbbbbType another total price "
                            + "or &#ff88ffcancel"
            ));
            return;
        }

        OrderCreateGui.clearSelected(player);
        MenuHistory.openRoot(
                core,
                player,
                () -> OrdersMainGui.open(
                        player,
                        service
                )
        );
    }

    private void requeueAmount(
            Player player,
            Material material
    ) {
        PENDING.put(
                player.getUniqueId(),
                new PendingOrder(
                        material,
                        0,
                        Step.AMOUNT
                )
        );

        player.sendMessage(TextColor.color(
                "&#bbbbbbType another amount "
                        + "or &#ff88ffcancel"
        ));
    }

    private boolean isCancel(String input) {
        return input.equalsIgnoreCase("cancel")
                || input.equalsIgnoreCase("cancelled");
    }

    private void error(
            Player player,
            String message
    ) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }

    private record PendingOrder(
            Material material,
            int amount,
            Step step
    ) {
    }

    private enum Step {
        AMOUNT,
        PRICE
    }
}
