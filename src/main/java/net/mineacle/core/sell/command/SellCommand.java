package net.mineacle.core.sell.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.mineacle.core.Core;
import net.mineacle.core.common.player.PlayerTabComplete;
import net.mineacle.core.common.sound.SoundService;
import net.mineacle.core.common.text.TextColor;
import net.mineacle.core.sell.gui.SellGui;
import net.mineacle.core.sell.gui.SellHistoryGui;
import net.mineacle.core.sell.gui.SellMultiGui;
import net.mineacle.core.sell.gui.WorthGui;
import net.mineacle.core.sell.model.ItemValuation;
import net.mineacle.core.sell.model.SaleResult;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SellCommand
        implements CommandExecutor, TabCompleter {

    private static final List<String> PLAYER_SUBCOMMANDS =
            List.of(
                    "gui",
                    "hand",
                    "all",
                    "history",
                    "worth"
            );

    private static final List<String> MARKET_SUBCOMMANDS =
            List.of(
                    "reprice",
                    "rotate",
                    "reset",
                    "audit"
            );

    private final Core core;
    private final SellService sellService;

    public SellCommand(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        String commandName = command.getName()
                .toLowerCase(Locale.ROOT);

        if (!(sender instanceof Player player)) {
            sender.sendMessage(
                    core.getMessage("general.players-only")
            );
            return true;
        }

        if (!player.hasPermission("mineaclesell.use")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return true;
        }

        if (commandName.equals("worth")) {
            handleWorth(player, args);
            return true;
        }

        if (commandName.equals("sellmulti")) {
            SellMultiGui.open(core, player, sellService);
            return true;
        }

        if (args.length == 0
                || args[0].equalsIgnoreCase("gui")) {
            SellGui.open(core, player, sellService);
            return true;
        }

        String subcommand = args[0]
                .toLowerCase(Locale.ROOT);

        switch (subcommand) {
            case "hand" -> sellHand(player);
            case "all", "inventory" -> sellInventory(player);
            case "history" -> SellHistoryGui.open(
                    core,
                    player,
                    sellService,
                    0
            );
            case "multi", "multipliers" ->
                    SellMultiGui.open(
                            core,
                            player,
                            sellService
                    );
            case "worth" -> handleWorth(
                    player,
                    dropFirst(args)
            );
            case "reload" -> reload(player, args);
            case "market", "demand" ->
                    market(player, args);
            default -> error(
                    player,
                    "&cUsage: /sell "
                            + "<gui|hand|all|history|worth>"
            );
        }

        return true;
    }

    private void handleWorth(
            Player player,
            String[] args
    ) {
        if (args.length == 0) {
            WorthGui.open(
                    core,
                    player,
                    sellService,
                    0
            );
            return;
        }

        if (args.length == 1
                && args[0].equalsIgnoreCase("hand")) {
            sendHeldWorth(player);
            return;
        }

        Material material = material(
                String.join("_", args)
        );

        if (material == null || !material.isItem()) {
            error(player, "&cUnknown item");
            return;
        }

        ItemStack item = new ItemStack(material);
        ItemValuation valuation = sellService.appraise(
                player,
                item
        );

        if (!valuation.priced()
                || valuation.appraisedTotalCents() <= 0L) {
            error(player, "&cThat item has no worth");
            return;
        }

        int stackSize = Math.max(
                1,
                material.getMaxStackSize()
        );
        long stackAppraisal = multiply(
                valuation.appraisedTotalCents(),
                stackSize
        );
        long stackSell = multiply(
                valuation.serverSellCents(),
                stackSize
        );

        player.sendMessage(TextColor.color(
                "&#bbbbbbItem: &#ff88ff"
                        + sellService.pretty(material)
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbAppraised Worth: &a"
                        + sellService.format(
                        valuation.appraisedTotalCents()
                )
        ));

        if (stackSize > 1) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbStack Appraisal: &a"
                            + sellService.format(
                            stackAppraisal
                    )
            ));
        }

        if (valuation.sellable()) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbServer Sell: &a"
                            + sellService.format(
                            valuation.serverSellCents()
                    )
            ));

            if (stackSize > 1) {
                player.sendMessage(TextColor.color(
                        "&#bbbbbbStack Sell: &a"
                                + sellService.format(
                                stackSell
                        )
                ));
            }
        } else {
            player.sendMessage(TextColor.color(
                    "&cPlayer Market Only"
            ));
            player.sendMessage(TextColor.color(
                    "&#bbbbbbUse /ah or direct player trading"
            ));
        }

        if (!valuation.explicitlyPriced()) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbEstimated appraisal — not accepted by /sell"
            ));
        }

        SoundService.economyBalance(player, core);
    }

    private void sendHeldWorth(Player player) {
        ItemStack item = player.getInventory()
                .getItemInMainHand();

        if (item == null || item.getType().isAir()) {
            error(player, "&cHold an item to check its worth");
            return;
        }

        ItemValuation valuation = sellService.appraise(
                player,
                item
        );
        long visualWorth = sellService.visualWorthCents(
                player.getUniqueId(),
                item
        );

        if (!valuation.priced() || visualWorth <= 0L) {
            error(player, "&cThis item has no worth");
            return;
        }

        player.sendMessage(TextColor.color(
                "&#bbbbbbItem: &#ff88ff"
                        + item.getAmount()
                        + "x "
                        + sellService.pretty(item.getType())
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbAppraised Worth: &a"
                        + sellService.format(visualWorth)
        ));

        if (valuation.appraisedEnchantCents() > 0L) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbEnchant Value: &a"
                            + sellService.format(
                            valuation.appraisedEnchantCents()
                    )
            ));
        }

        if (valuation.sellable()) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbServer Sell: &a"
                            + sellService.format(
                            valuation.serverSellCents()
                    )
            ));
        } else {
            player.sendMessage(TextColor.color(
                    "&cPlayer Market Only"
            ));
            player.sendMessage(TextColor.color(
                    "&#bbbbbbUse /ah or direct player trading"
            ));
        }

        if (valuation.damaged()) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbDurability: &#ff88ff"
                            + valuation.durabilityPercent()
                            + "%"
            ));
        }

        if (valuation.mending()) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbMending: &#ff88ffValue protected"
            ));
        }

        if (!valuation.explicitlyPriced()) {
            player.sendMessage(TextColor.color(
                    "&#bbbbbbEstimated appraisal — not accepted by /sell"
            ));
        }

        SoundService.economyBalance(player, core);
    }

    private void sellHand(Player player) {
        ItemStack hand = player.getInventory()
                .getItemInMainHand();

        if (hand == null || hand.getType().isAir()) {
            error(player, "&cHold an item to sell");
            return;
        }

        Inventory temporary = Bukkit.createInventory(
                null,
                9
        );
        temporary.setItem(0, hand.clone());

        player.getInventory().setItemInMainHand(
                new ItemStack(Material.AIR)
        );

        SaleResult result;

        try {
            result = sellService.sellInventory(
                    player.getUniqueId(),
                    temporary
            );
        } catch (RuntimeException exception) {
            player.getInventory().setItemInMainHand(hand);
            throw exception;
        }

        if (!result.soldAnything()) {
            ItemStack returned = result.returnedItems()
                    .stream()
                    .findFirst()
                    .orElse(hand);
            player.getInventory().setItemInMainHand(returned);

            if (!result.failureMessage().isBlank()) {
                error(player, result.failureMessage());
            } else {
                error(player, "&cThis item cannot be sold");
            }
            return;
        }

        for (ItemStack returned : result.returnedItems()) {
            player.getInventory()
                    .addItem(returned)
                    .values()
                    .forEach(leftover ->
                            player.getWorld().dropItemNaturally(
                                    player.getLocation(),
                                    leftover
                            )
                    );
        }

        sendSaleResult(player, result);
    }

    private void sellInventory(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] storage = inventory.getStorageContents();
        Inventory temporary = Bukkit.createInventory(
                null,
                54
        );

        for (int index = 0; index < storage.length; index++) {
            ItemStack item = storage[index];

            if (item != null && !item.getType().isAir()) {
                temporary.setItem(index, item.clone());
            }
        }

        inventory.setStorageContents(
                new ItemStack[storage.length]
        );

        SaleResult result;

        try {
            result = sellService.sellInventory(
                    player.getUniqueId(),
                    temporary
            );
        } catch (RuntimeException exception) {
            inventory.setStorageContents(storage);
            throw exception;
        }

        ItemStack[] returned = new ItemStack[storage.length];
        int index = 0;

        for (ItemStack item : result.returnedItems()) {
            if (index >= returned.length) {
                break;
            }

            returned[index++] = item;
        }

        inventory.setStorageContents(returned);

        if (!result.soldAnything()) {
            if (!result.failureMessage().isBlank()) {
                error(player, result.failureMessage());
            } else {
                error(
                        player,
                        "&cYou do not have any sellable items"
                );
            }
            return;
        }

        sendSaleResult(player, result);
    }

    private void sendSaleResult(
            Player player,
            SaleResult result
    ) {
        String chat = sellService.message(
                "sold-chat",
                "&#bbbbbbSold &#ff88ff%amount%x items "
                        + "&#bbbbbbfor &a+%money%"
        )
                .replace(
                        "%amount%",
                        String.valueOf(result.totalAmount())
                )
                .replace(
                        "%money%",
                        sellService.format(result.totalCents())
                );
        String actionBar = sellService.message(
                "sold-actionbar",
                "&a+%money%"
        ).replace(
                "%money%",
                sellService.format(result.totalCents())
        );

        player.sendMessage(chat);
        player.sendActionBar(component(actionBar));
        SoundService.economyReceive(player, core);
    }

    private void reload(Player player, String[] args) {
        if (!player.hasPermission("mineaclesell.admin")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return;
        }

        if (args.length != 1) {
            error(player, "&cUsage: /sell reload");
            return;
        }

        sellService.reload();
        WorthGui.clearCatalogCache();
        player.sendMessage(TextColor.color(
                "&#bbbbbbSell system reloaded"
        ));
        SoundService.guiConfirm(player, core);
    }

    private void market(Player player, String[] args) {
        if (!player.hasPermission("mineaclesell.admin")) {
            error(
                    player,
                    core.getMessage("general.no-permission")
            );
            return;
        }

        if (args.length < 2) {
            error(
                    player,
                    "&cUsage: /sell market "
                            + "<item|reprice|rotate|reset|audit>"
            );
            return;
        }

        String operation = args[1]
                .toLowerCase(Locale.ROOT);

        switch (operation) {
            case "reprice", "recalc" -> {
                sellService.recalculateDemand();
                WorthGui.clearCatalogCache();
                player.sendMessage(TextColor.color(
                        "&#bbbbbbSell market repriced"
                ));
                SoundService.guiConfirm(player, core);
                return;
            }
            case "rotate" -> {
                sellService.rotateDemand();
                WorthGui.clearCatalogCache();
                player.sendMessage(TextColor.color(
                        "&#bbbbbbFeatured demand rotated"
                ));
                SoundService.guiConfirm(player, core);
                return;
            }
            case "reset" -> {
                sellService.resetDemandData();
                WorthGui.clearCatalogCache();
                player.sendMessage(TextColor.color(
                        "&#bbbbbbSell market data reset"
                ));
                SoundService.guiConfirm(player, core);
                return;
            }
            case "audit" -> {
                SellService.CatalogCoverage coverage =
                        sellService.catalogCoverage();
                player.sendMessage(TextColor.color(
                        "&#bbbbbbWorth Catalog: &#ff88ff"
                                + coverage.visibleItems()
                ));
                player.sendMessage(TextColor.color(
                        "&#bbbbbbExplicit Appraisals: &#ff88ff"
                                + coverage.explicitlyPricedItems()
                ));
                player.sendMessage(TextColor.color(
                        "&#bbbbbbFallback Estimates: &#ff88ff"
                                + coverage.fallbackAppraisals()
                ));
                player.sendMessage(TextColor.color(
                        "&#bbbbbbServer Sellable: &#ff88ff"
                                + coverage.serverSellableItems()
                ));
                player.sendMessage(TextColor.color(
                        "&#bbbbbbPlayer Market Only: &#ff88ff"
                                + coverage.playerMarketOnlyItems()
                ));
                SoundService.economyBalance(player, core);
                return;
            }
            default -> {
            }
        }

        Material material = material(
                String.join(
                        "_",
                        java.util.Arrays.copyOfRange(
                                args,
                                1,
                                args.length
                        )
                )
        );

        if (material == null || !material.isItem()) {
            error(player, "&cUnknown item");
            return;
        }

        double market =
                sellService.demandMultiplier(material);
        double supplyRatio =
                sellService.marketSupplyRatio(material);
        long sold = sellService.demandWindowAmount(material);
        long target =
                sellService.marketTargetUnits(material);

        player.sendMessage(TextColor.color(
                "&#bbbbbbItem: &#ff88ff"
                        + sellService.pretty(material)
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbBase Appraisal: &a"
                        + sellService.format(
                        sellService.baseWorthCents(material)
                )
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbMarket: &#ff88ff"
                        + SellService.formatMultiplier(market)
                        + "x"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbCurrent Appraisal: &a"
                        + sellService.format(
                        sellService.unitWorthCents(
                                player,
                                material
                        )
                )
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbServer Sell: &a"
                        + sellService.format(
                        sellService.serverUnitSellCents(
                                player,
                                material
                        )
                )
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbb24h Supply: &#ff88ff"
                        + sold
                        + "&#bbbbbb/"
                        + "&#ff88ff"
                        + target
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbSupply Ratio: &#ff88ff"
                        + SellService.formatMultiplier(
                        supplyRatio
                )
                        + "x"
        ));
        player.sendMessage(TextColor.color(
                "&#bbbbbbStatus: &#ff88ff"
                        + sellService.demandTierDisplay(
                        material
                )
        ));
        SoundService.economyBalance(player, core);
    }

    private long multiply(
            long value,
            int multiplier
    ) {
        try {
            return Math.multiplyExact(value, multiplier);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void error(Player player, String message) {
        player.sendMessage(TextColor.color(message));
        SoundService.guiError(player, core);
    }

    private Component component(String message) {
        return LegacyComponentSerializer.legacySection()
                .deserialize(TextColor.color(message));
    }

    private Material material(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }

        return Material.matchMaterial(
                raw.trim()
                        .replace(' ', '_')
                        .replace('-', '_')
        );
    }

    private String[] dropFirst(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }

        return java.util.Arrays.copyOfRange(
                args,
                1,
                args.length
        );
    }

    @Override
    public List<String> onTabComplete(
            CommandSender sender,
            Command command,
            String alias,
            String[] args
    ) {
        if (!(sender instanceof Player player)
                || !player.hasPermission("mineaclesell.use")) {
            return List.of();
        }

        String commandName = command.getName()
                .toLowerCase(Locale.ROOT);

        if (commandName.equals("worth")) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>();
                options.add("hand");
                options.addAll(itemCompletions(args));
                return PlayerTabComplete.options(
                        args[0],
                        options
                );
            }

            return itemCompletions(args);
        }

        if (commandName.equals("sellmulti")) {
            return List.of();
        }

        if (args.length == 1) {
            List<String> options =
                    new ArrayList<>(PLAYER_SUBCOMMANDS);

            if (player.hasPermission("mineaclesell.admin")) {
                options.add("market");
                options.add("reload");
            }

            return PlayerTabComplete.options(
                    args[0],
                    options
            );
        }

        if (args.length >= 2
                && args[0].equalsIgnoreCase("worth")) {
            return itemCompletions(dropFirst(args));
        }

        if (args.length == 2
                && (args[0].equalsIgnoreCase("market")
                || args[0].equalsIgnoreCase("demand"))
                && player.hasPermission("mineaclesell.admin")) {
            List<String> options =
                    new ArrayList<>(MARKET_SUBCOMMANDS);
            options.addAll(itemCompletions(
                    new String[]{args[1]}
            ));

            return PlayerTabComplete.options(
                    args[1],
                    options
            );
        }

        return List.of();
    }

    private List<String> itemCompletions(String[] args) {
        String partial = String.join("_", args)
                .toLowerCase(Locale.ROOT);
        List<String> completions = new ArrayList<>();

        for (Material material : Material.values()) {
            if (!material.isItem()
                    || material == Material.AIR
                    || material.name().endsWith("_SPAWN_EGG")) {
                continue;
            }

            String name = material.name()
                    .toLowerCase(Locale.ROOT);

            if (partial.isBlank()
                    || name.startsWith(partial)) {
                completions.add(name);
            }

            if (completions.size() >= 80) {
                break;
            }
        }

        return completions;
    }
}
