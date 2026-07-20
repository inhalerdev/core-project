package net.mineacle.core.orders.command;

import net.mineacle.core.Core;
import net.mineacle.core.common.gui.MenuHistory;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.orders.gui.OrderCreateGui;
import net.mineacle.core.orders.gui.OrdersMainGui;
import net.mineacle.core.orders.gui.OrdersViewState;
import net.mineacle.core.orders.listener.OrderCreateInputListener;
import net.mineacle.core.orders.listener.OrderSearchInputListener;
import net.mineacle.core.orders.service.OrderService;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class OrderCommand
        implements CommandExecutor, TabCompleter {

    private final Core core;
    private final OrderService service;

    public OrderCommand(
            Core core,
            OrderService service
    ) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (!player.hasPermission("mineacleorders.use")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (!service.enabled()) {
            error(
                    player,
                    core.getConfig().getString(
                            "orders.messages.disabled",
                            "&cOrders are currently disabled"
                    )
            );
            return true;
        }

        if (args.length == 0) {
            MenuHistory.openRoot(
                    core,
                    player,
                    () -> OrdersMainGui.open(
                            player,
                            service
                    )
            );
            return true;
        }

        String subcommand = args[0]
                .toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "search" -> handleSearch(player, args);
            case "clear" -> handleClear(player, args);
            case "create" -> handleCreate(player, args);
            case "reload" -> handleReload(player, args);
            default -> usage(player);
        }

        return true;
    }

    private void handleSearch(
            Player player,
            String[] args
    ) {
        if (args.length == 1) {
            OrderSearchInputListener.beginMain(player);
            MenuHistory.closeForInput(core, player);
            SoundService.guiClick(player, core);

            player.sendMessage(TextColor.color(
                    "&#bbbbbbType an item name to search orders"
            ));
            player.sendMessage(TextColor.color(
                    "&#bbbbbbType &#ff88ffclear "
                            + "&#bbbbbbto reset or "
                            + "&#ff88ffcancel "
                            + "&#bbbbbbto stop"
            ));
            return;
        }

        String query = String.join(
                " ",
                Arrays.copyOfRange(
                        args,
                        1,
                        args.length
                )
        );

        OrdersMainGui.setSearch(player, query);
        MenuHistory.openRoot(
                core,
                player,
                () -> OrdersMainGui.open(
                        player,
                        service
                )
        );
    }

    private void handleClear(
            Player player,
            String[] args
    ) {
        if (args.length != 1) {
            usage(player);
            return;
        }

        OrdersMainGui.clearSearch(player);
        player.sendMessage(TextColor.color(
                "&#bbbbbbOrder search cleared"
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
    }

    private void handleCreate(
            Player player,
            String[] args
    ) {
        if (args.length == 1) {
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

        if (args.length != 4) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbUsage: &d/order create "
                            + "<item> <amount> <total price>"
            ));
            player.sendMessage(TextColor.color(
                    "&#bbbbbbExample: &d/order create "
                            + "oak_log 64 100k"
            ));
            SoundService.guiError(player, core);
            return;
        }

        Material material = Material.matchMaterial(
                args[1].trim()
                        .toUpperCase(Locale.ROOT)
        );

        if (material == null
                || material == Material.AIR
                || !material.isItem()) {
            error(player, "&cUnknown item");
            return;
        }

        int amount;

        try {
            amount = Integer.parseInt(
                    args[2]
                            .replace(",", "")
                            .replace("_", "")
            );
        } catch (NumberFormatException exception) {
            error(player, "&cInvalid amount");
            return;
        }

        OrderService.CreationResult result =
                service.createDetailed(
                        player,
                        material,
                        amount,
                        args[3]
                );

        if (result == OrderService.CreationResult.SUCCESS) {
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

    private void handleReload(
            Player player,
            String[] args
    ) {
        if (args.length != 1
                || !player.hasPermission(
                "mineacleorders.admin"
        )) {
            usage(player);
            return;
        }

        core.reloadConfig();
        service.reload();
        OrdersViewState.clearAll();
        OrderSearchInputListener.clearAll();
        OrderCreateInputListener.clearAll();

        player.sendMessage(TextColor.color(
                "&#bbbbbbOrders reloaded"
        ));
        SoundService.guiConfirm(player, core);
    }

    private void usage(Player player) {
        player.sendMessage(TextColor.color(
                "&#bbbbbbUsage: &d/order "
                        + "[create|search|clear]"
        ));
        SoundService.guiError(player, core);
    }

    private void error(
            Player player,
            String message
    ) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!sender.hasPermission("mineacleorders.use")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options = new ArrayList<>(
                    List.of(
                            "clear",
                            "create",
                            "search"
                    )
            );

            if (sender.hasPermission(
                    "mineacleorders.admin"
            )) {
                options.add("reload");
            }

            return PlayerTabComplete.options(
                    args[0],
                    options
            );
        }

        if (args.length == 2
                && args[0].equalsIgnoreCase("create")) {
            String partial = args[1] == null
                    ? ""
                    : args[1].toLowerCase(Locale.ROOT);
            List<String> completions =
                    new ArrayList<>();

            for (Material material : Material.values()) {
                if (material == Material.AIR
                        || !material.isItem()) {
                    continue;
                }

                String name = material.name()
                        .toLowerCase(Locale.ROOT);

                if (partial.isBlank()
                        || name.startsWith(partial)) {
                    completions.add(name);
                }
            }

            completions.sort(
                    String.CASE_INSENSITIVE_ORDER
            );
            return List.copyOf(completions);
        }

        if (args.length == 3
                && args[0].equalsIgnoreCase("create")) {
            return PlayerTabComplete.options(
                    args[2],
                    List.of(
                            "64",
                            "128",
                            "256",
                            "512",
                            "2304"
                    )
            );
        }

        if (args.length == 4
                && args[0].equalsIgnoreCase("create")) {
            return PlayerTabComplete.options(
                    args[3],
                    List.of(
                            "10k",
                            "100k",
                            "1M",
                            "10M"
                    )
            );
        }

        return List.of();
    }
}
