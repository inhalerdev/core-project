package net.mineacle.core.orders.listener;

import net.mineacle.core.Core;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.orders.gui.OrderCreateGui;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class OrderCreateInputListener implements Listener {

    private static final Map<UUID, PendingOrder> PENDING = new HashMap<>();

    private final Core core;
    private final OrderService service;

    public OrderCreateInputListener(Core core, OrderService service) {
        this.core = core;
        this.service = service;
    }

    public static void beginAmount(Player player, Material material) {
        PENDING.put(player.getUniqueId(), new PendingOrder(material, 0, Step.AMOUNT));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        PendingOrder pending = PENDING.get(player.getUniqueId());

        if (pending == null) {
            return;
        }

        event.setCancelled(true);
        String message = event.getMessage();

        core.getServer().getScheduler().runTask(core, () -> {
            if (!player.isOnline()) {
                PENDING.remove(player.getUniqueId());
                return;
            }

            if (message.equalsIgnoreCase("cancel") || message.equalsIgnoreCase("cancelled")) {
                PENDING.remove(player.getUniqueId());
                OrderCreateGui.clear(player);
                player.sendMessage(TextColor.color("&#bbbbbbOrder creation cancelled"));
                OrdersMainGui.open(player, service);
                return;
            }

            if (pending.step() == Step.AMOUNT) {
                handleAmount(player, pending, message);
                return;
            }

            handlePrice(player, pending, message);
        });
    }

    private void handleAmount(Player player, PendingOrder pending, String message) {
        int amount;

        try {
            amount = Integer.parseInt(message.trim().replace(",", "").replace("_", ""));
        } catch (NumberFormatException exception) {
            player.sendMessage(TextColor.color("&cType a whole number like 64"));
            SoundService.guiError(player, core);
            askAmount(player, pending.material());
            return;
        }

        int maxAmount = core.getConfig().getInt("orders.limits.max-amount", 2304);

        if (amount <= 0) {
            player.sendMessage(TextColor.color("&cAmount must be greater than 0"));
            SoundService.guiError(player, core);
            askAmount(player, pending.material());
            return;
        }

        if (amount > maxAmount) {
            player.sendMessage(TextColor.color("&cThat order amount is too high"));
            player.sendMessage(TextColor.color("&#bbbbbbMax amount: &#ff88ff" + maxAmount));
            SoundService.guiError(player, core);
            askAmount(player, pending.material());
            return;
        }

        PENDING.put(player.getUniqueId(), new PendingOrder(pending.material(), amount, Step.PRICE));

        player.sendMessage(TextColor.color(""));
        player.sendMessage(TextColor.color("&#bbbbbbHow much total money should players earn?"));
        player.sendMessage(TextColor.color("&#bbbbbbYou are ordering &#ff88ff" + amount + "x " + service.pretty(pending.material())));
        player.sendMessage(TextColor.color("&#bbbbbbExamples: &#ff88ff100k&#bbbbbb, &#ff88ff11.5M&#bbbbbb, &#ff88ff250000"));
        player.sendMessage(TextColor.color("&#bbbbbbType &#ff88ffcancel &#bbbbbbto stop"));
    }

    private void handlePrice(Player player, PendingOrder pending, String message) {
        if (!service.create(player, pending.material(), pending.amount(), message)) {
            PENDING.remove(player.getUniqueId());
            OrderCreateGui.clear(player);
            return;
        }

        PENDING.remove(player.getUniqueId());
        OrderCreateGui.clear(player);
        OrdersMainGui.open(player, service);
    }

    private void askAmount(Player player, Material material) {
        PENDING.put(player.getUniqueId(), new PendingOrder(material, 0, Step.AMOUNT));

        player.sendMessage(TextColor.color(""));
        player.sendMessage(TextColor.color("&#bbbbbbHow many &#ff88ff" + service.pretty(material) + " &#bbbbbbdo you want?"));
        player.sendMessage(TextColor.color("&#bbbbbbExamples: &#ff88ff64&#bbbbbb, &#ff88ff2304"));
        player.sendMessage(TextColor.color("&#bbbbbbType &#ff88ffcancel &#bbbbbbto stop"));
    }

    private record PendingOrder(Material material, int amount, Step step) {
    }

    private enum Step {
        AMOUNT,
        PRICE
    }
}
