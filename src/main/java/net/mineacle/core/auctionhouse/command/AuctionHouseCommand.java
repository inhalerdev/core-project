package net.mineacle.core.auctionhouse.command;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.gui.AuctionHouseGui;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.common.text.TextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class AuctionHouseCommand implements CommandExecutor, TabCompleter {

    private final Core core;
    private final AuctionHouseService service;

    public AuctionHouseCommand(Core core, AuctionHouseService service) {
        this.core = core;
        this.service = service;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(core.getMessage("general.players-only"));
            return true;
        }

        if (!service.enabled()) {
            player.sendMessage(TextColor.color("&cAuction house is currently disabled"));
            return true;
        }

        if (!player.hasPermission("mineacleauctionhouse.use")) {
            player.sendMessage(core.getMessage("general.no-permission"));
            return true;
        }

        if (args.length == 0) {
            AuctionHouseGui.openBrowse(player, service, 0, AuctionHouseService.SortMode.LOWEST_PRICE, "");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("sell") || sub.equals("list")) {
            sell(player, args);
            return true;
        }

        if (sub.equals("items") || sub.equals("myitems") || sub.equals("listings")) {
            AuctionHouseGui.openOwn(player, service);
            return true;
        }

        if (sub.equals("reload") && player.hasPermission("mineacleauctionhouse.admin")) {
            service.load();
            player.sendMessage(TextColor.color("&#bbbbbbAuction house reloaded"));
            return true;
        }

        String query = String.join(" ", args);
        AuctionHouseGui.openBrowse(player, service, 0, AuctionHouseService.SortMode.LOWEST_PRICE, query);
        return true;
    }

    private void sell(Player player, String[] args) {
        if (!service.canList(player)) {
            player.sendMessage(TextColor.color("&cYou cannot list auction items"));
            return;
        }

        if (args.length < 2) {
            player.sendMessage(TextColor.color("&cUsage: /ah sell <price>"));
            return;
        }

        ItemStack held = player.getInventory().getItemInMainHand();

        if (held == null || held.getType().isAir()) {
            player.sendMessage(TextColor.color("&cHold the item you want to sell"));
            return;
        }

        int limit = service.listingLimit(player);

        if (service.activeListingCount(player.getUniqueId()) >= limit) {
            player.sendMessage(TextColor.color("&cYou do not have any auction slots available"));
            return;
        }

        long price = service.parsePriceCents(args[1]);

        if (price < service.minPriceCents()) {
            player.sendMessage(TextColor.color("&cMinimum auction price is &a" + service.format(service.minPriceCents())));
            return;
        }

        if (price > service.maxPriceCents()) {
            player.sendMessage(TextColor.color("&cMaximum auction price is &a" + service.format(service.maxPriceCents())));
            return;
        }

        ItemStack saleItem = held.clone();
        player.getInventory().setItemInMainHand(null);

        service.createListing(player, saleItem, price);
        player.sendMessage(TextColor.color(
                "&#bbbbbbListed &d" + service.itemName(saleItem)
                        + " &#bbbbbbfor &a" + service.format(price)
        ));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }

        if (!player.hasPermission("mineacleauctionhouse.use")) {
            return List.of();
        }

        if (args.length == 1) {
            String partial = args[0].toLowerCase(Locale.ROOT);
            List<String> suggestions = new ArrayList<>();

            for (String option : List.of("sell", "items", "mending", "cobble", "diamond", "netherite")) {
                if (option.startsWith(partial)) {
                    suggestions.add(option);
                }
            }

            if (player.hasPermission("mineacleauctionhouse.admin") && "reload".startsWith(partial)) {
                suggestions.add("reload");
            }

            return suggestions;
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("sell") || args[0].equalsIgnoreCase("list"))) {
            return List.of("100", "1k", "10k", "100k");
        }

        return List.of();
    }
}
