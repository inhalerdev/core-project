package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Registry;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.CookingRecipe;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.RecipeChoice;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.SmithingTransformRecipe;
import org.bukkit.inventory.StonecuttingRecipe;
import org.bukkit.inventory.TransmuteRecipe;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Startup integrity and economy-quality audit for catalog revision 9. */
public final class SellRuntimeIntegrityAudit {

    private static final int MAX_WAIT_ATTEMPTS = 120;
    private static final double MAX_ONE_CENT_SHARE = 0.05D;

    private final Core core;
    private final SellService sellService;
    private BukkitTask task;
    private int attempts;

    public SellRuntimeIntegrityAudit(Core core, SellService sellService) {
        this.core = core;
        this.sellService = sellService;
    }

    public void start() {
        if (task != null) {
            return;
        }
        task = core.getServer().getScheduler().runTaskTimer(
                core,
                this::tick,
                40L,
                20L
        );
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void tick() {
        if (!core.isEnabled()) {
            shutdown();
            return;
        }

        if (sellService.catalogRevision() < SellPricingPolicy.CATALOG_REVISION) {
            attempts++;
            if (attempts >= MAX_WAIT_ATTEMPTS) {
                core.getLogger().severe(
                        "Sell launch integrity audit FAIL — catalog v9 did not activate within the audit window"
                );
                shutdown();
            }
            return;
        }

        AuditResult result = runAudit();
        if (result.failures().isEmpty()) {
            core.getLogger().info(
                    "Sell launch integrity audit PASS — catalog v"
                            + sellService.catalogRevision()
                            + ", " + result.serverSellable() + "/" + result.visible()
                            + " visible items server-sellable, "
                            + result.oneCent() + " one-cent values, "
                            + "Worth/payout equivalence verified, recipe conversion safety verified, "
                            + "variant/custom/container gates verified, reversible commodities verified"
            );
        } else {
            core.getLogger().severe(
                    "Sell launch integrity audit FAIL — "
                            + String.join("; ", result.failures())
            );
        }
        shutdown();
    }

    private AuditResult runAudit() {
        List<String> failures = new ArrayList<>();
        SellService.CatalogCoverage coverage = sellService.catalogCoverage();

        if (sellService.catalogRevision() != SellPricingPolicy.CATALOG_REVISION) {
            failures.add("unexpected catalog revision " + sellService.catalogRevision());
        }
        if (coverage.visibleItems() <= 0 || coverage.serverSellableItems() <= 0) {
            failures.add("catalog coverage is empty");
        }
        if (coverage.playerMarketOnlyItems() > 0) {
            failures.add(
                    coverage.playerMarketOnlyItems()
                            + " normal Worth-catalog item(s) are not server-sellable"
            );
        }

        int oneCent = verifyPositiveWorthDistribution(failures);
        verifyWorthPayoutEquivalence(failures);
        verifyNormalAndCustomItemGate(failures);
        verifyFilledShulkerGate(failures);
        verifyVariantMetadataGate(failures);
        verifyBlockedItemGate(failures);
        verifyRecipeSafety(failures);
        verifyCommodityGroup(
                failures,
                "iron",
                Material.IRON_NUGGET,
                Material.IRON_INGOT,
                Material.IRON_BLOCK
        );
        verifyCommodityGroup(
                failures,
                "gold",
                Material.GOLD_NUGGET,
                Material.GOLD_INGOT,
                Material.GOLD_BLOCK
        );

        return new AuditResult(
                List.copyOf(failures),
                coverage.visibleItems(),
                coverage.serverSellableItems(),
                oneCent
        );
    }

    private int verifyPositiveWorthDistribution(List<String> failures) {
        int priced = 0;
        int oneCent = 0;

        for (Material material : sellService.worthCatalogMaterials()) {
            long value = sellService.serverUnitSellCents(
                    (org.bukkit.entity.Player) null,
                    material
            );
            if (value <= 0L) {
                failures.add(material + " has no positive server Worth");
                continue;
            }
            priced++;
            if (value == 1L) {
                oneCent++;
            }
        }

        if (priced > 0
                && oneCent / (double) priced > MAX_ONE_CENT_SHARE) {
            failures.add(
                    "one-cent values exceed "
                            + Math.round(MAX_ONE_CENT_SHARE * 100.0D)
                            + "% of the sellable catalog ("
                            + oneCent + "/" + priced + ")"
            );
        }
        return oneCent;
    }

    private void verifyWorthPayoutEquivalence(List<String> failures) {
        for (Material material : sellService.worthCatalogMaterials()) {
            if (SellVariantValuationService.supportsMaterial(material)) {
                continue;
            }
            ItemStack item = new ItemStack(material);
            var valuation = sellService.appraise(
                    (org.bukkit.entity.Player) null,
                    item
            );
            long unit = sellService.serverUnitSellCents(
                    (org.bukkit.entity.Player) null,
                    material
            );
            if (!valuation.sellable()
                    || valuation.serverSellCents() != unit) {
                failures.add(
                        material + " Worth/server-payout mismatch: "
                                + unit + " vs " + valuation.serverSellCents()
                );
            }
        }
    }

    private void verifyNormalAndCustomItemGate(List<String> failures) {
        ItemStack normal = new ItemStack(Material.SUGAR_CANE);
        if (!sellService.canSell(normal)) {
            failures.add("normal vanilla item gate rejected SUGAR_CANE");
            return;
        }

        ItemStack custom = normal.clone();
        ItemMeta meta = custom.getItemMeta();
        if (meta == null) {
            failures.add("could not construct custom-item audit metadata");
            return;
        }
        meta.getPersistentDataContainer().set(
                new NamespacedKey(core, "sell_integrity_audit"),
                PersistentDataType.BYTE,
                (byte) 1
        );
        custom.setItemMeta(meta);
        if (sellService.canSell(custom)) {
            failures.add("custom/PDC item gate accepted a tagged SUGAR_CANE");
        }
    }

    private void verifyFilledShulkerGate(List<String> failures) {
        ItemStack item = new ItemStack(Material.SHULKER_BOX);
        ItemMeta rawMeta = item.getItemMeta();
        if (!(rawMeta instanceof BlockStateMeta meta)
                || !(meta.getBlockState() instanceof ShulkerBox shulker)) {
            failures.add("could not construct filled-shulker audit item");
            return;
        }
        shulker.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        meta.setBlockState(shulker);
        item.setItemMeta(meta);
        if (sellService.canSell(item)) {
            failures.add("filled-container gate accepted a populated shulker");
        }
    }

    private void verifyVariantMetadataGate(List<String> failures) {
        ItemStack water = new ItemStack(Material.POTION);
        ItemMeta rawMeta = water.getItemMeta();
        if (!(rawMeta instanceof PotionMeta meta)) {
            failures.add("could not construct potion audit metadata");
            return;
        }
        meta.setBasePotionType(PotionType.WATER);
        water.setItemMeta(meta);
        var valuation = sellService.appraise(
                (org.bukkit.entity.Player) null,
                water
        );
        if (!valuation.sellable() || valuation.serverSellCents() <= 1L) {
            failures.add("normal WATER potion did not receive a meaningful v9 payout");
        }

        ItemStack custom = water.clone();
        ItemMeta customRaw = custom.getItemMeta();
        if (!(customRaw instanceof PotionMeta customMeta)) {
            failures.add("could not construct custom potion audit metadata");
            return;
        }
        customMeta.setColor(Color.FUCHSIA);
        custom.setItemMeta(customMeta);
        if (sellService.canSell(custom)) {
            failures.add("variant metadata gate accepted a custom-color potion");
        }
    }

    private void verifyBlockedItemGate(List<String> failures) {
        if (sellService.canSell(new ItemStack(Material.BEDROCK))) {
            failures.add("blocked-item gate accepted BEDROCK");
        }
    }

    private void verifyRecipeSafety(List<String> failures) {
        IteratorState state = new IteratorState();
        Iterator<Recipe> iterator = Bukkit.recipeIterator();

        while (iterator.hasNext()) {
            RecipeSeed recipe = recipeSeed(iterator.next());
            if (recipe == null
                    || SellVariantValuationService.supportsMaterial(recipe.output())) {
                continue;
            }

            long outputUnit = sellService.serverUnitSellCents(
                    (org.bukkit.entity.Player) null,
                    recipe.output()
            );
            if (outputUnit <= 0L) {
                continue;
            }

            long input = 0L;
            boolean complete = true;
            boolean customRecipe = false;

            for (IngredientChoice choice : recipe.ingredients()) {
                if (choice.untrusted()) {
                    customRecipe = true;
                    break;
                }

                long cheapest = Long.MAX_VALUE;
                for (Material material : choice.materials()) {
                    long value = currentNetIngredientPayout(
                            material,
                            recipe.craftingRemainders()
                    );
                    if (value >= 0L) {
                        cheapest = Math.min(cheapest, value);
                    }
                }
                if (cheapest == Long.MAX_VALUE) {
                    complete = false;
                    break;
                }
                input = safeAdd(input, cheapest);
            }

            /*
             * Exact metadata-bound recipes belong to the plugin/custom-item
             * economy boundary. They are intentionally excluded from the
             * automatic vanilla liquidation graph rather than disabling the
             * ordinary output Material.
             */
            if (customRecipe) {
                continue;
            }

            if (!complete || input <= 0L) {
                state.failures++;
                if (state.failures <= 20) {
                    failures.add(
                            "recipe has no positive net liquidatable input: "
                                    + recipe.output()
                    );
                }
                continue;
            }

            long output = safeMultiply(outputUnit, recipe.outputAmount());
            if (output > input) {
                state.failures++;
                if (state.failures <= 20) {
                    failures.add(
                            "recipe payout exceeds ingredients: "
                                    + recipe.output() + " " + output + ">" + input
                    );
                }
            }
        }

        if (state.failures > 20) {
            failures.add(
                    "+" + (state.failures - 20)
                            + " additional recipe payout violation(s)"
            );
        }
    }

    private long currentNetIngredientPayout(
            Material material,
            boolean craftingRemainders
    ) {
        long input = sellService.serverUnitSellCents(
                (org.bukkit.entity.Player) null,
                material
        );

        if (input <= 0L) {
            return -1L;
        }

        if (!craftingRemainders) {
            return input;
        }

        Material remainder = material.getCraftingRemainingItem();

        if (remainder == null || remainder == Material.AIR) {
            return input;
        }

        long returned = sellService.serverUnitSellCents(
                (org.bukkit.entity.Player) null,
                remainder
        );

        if (returned <= 0L) {
            return input;
        }

        return Math.max(0L, input - returned);
    }

    @SuppressWarnings("IfCanBeSwitch")
    private RecipeSeed recipeSeed(Recipe recipe) {
        if (recipe == null) {
            return null;
        }
        ItemStack result = recipe.getResult();
        if (result.getType().isAir() || !result.getType().isItem()) {
            return null;
        }
        List<IngredientChoice> ingredients = new ArrayList<>();
        boolean craftingRemainders = false;

        if (recipe instanceof ShapedRecipe shaped) {
            craftingRemainders = true;
            Map<Character, RecipeChoice> choices = shaped.getChoiceMap();
            for (String row : shaped.getShape()) {
                for (int index = 0; index < row.length(); index++) {
                    char key = row.charAt(index);
                    if (key == ' ') {
                        continue;
                    }
                    IngredientChoice choice = choice(choices.get(key));
                    if (choice == null) {
                        return null;
                    }
                    ingredients.add(choice);
                }
            }
        } else if (recipe instanceof ShapelessRecipe shapeless) {
            craftingRemainders = true;
            for (RecipeChoice raw : shapeless.getChoiceList()) {
                IngredientChoice choice = choice(raw);
                if (choice == null) {
                    return null;
                }
                ingredients.add(choice);
            }
        } else if (recipe instanceof CookingRecipe<?> cooking) {
            IngredientChoice choice = choice(cooking.getInputChoice());
            if (choice == null) {
                return null;
            }
            ingredients.add(choice);
        } else if (recipe instanceof StonecuttingRecipe stonecutting) {
            IngredientChoice choice = choice(stonecutting.getInputChoice());
            if (choice == null) {
                return null;
            }
            ingredients.add(choice);
        } else if (recipe instanceof TransmuteRecipe transmute) {
            craftingRemainders = true;
            IngredientChoice input = choice(transmute.getInput());
            IngredientChoice material = choice(transmute.getMaterial());
            if (input == null || material == null) {
                return null;
            }
            ingredients.add(input);
            ingredients.add(material);
        } else if (recipe instanceof SmithingTransformRecipe smithing) {
            IngredientChoice template = choice(smithing.getTemplate());
            IngredientChoice base = choice(smithing.getBase());
            IngredientChoice addition = choice(smithing.getAddition());
            if (template == null || base == null || addition == null) {
                return null;
            }
            ingredients.add(template);
            ingredients.add(base);
            ingredients.add(addition);
        } else {
            return null;
        }

        return ingredients.isEmpty()
                ? null
                : new RecipeSeed(
                result.getType(),
                Math.max(1, result.getAmount()),
                List.copyOf(ingredients),
                craftingRemainders
        );
    }

    @SuppressWarnings("UnstableApiUsage")
    private IngredientChoice choice(RecipeChoice choice) {
        if (choice instanceof RecipeChoice.ItemTypeChoice itemTypes) {
            List<Material> values = itemTypes.itemTypes()
                    .resolve(Registry.ITEM)
                    .stream()
                    .map(type -> Material.matchMaterial(type.getKey().toString()))
                    .filter(java.util.Objects::nonNull)
                    .filter(Material::isItem)
                    .filter(material -> material != Material.AIR)
                    .distinct()
                    .toList();
            return values.isEmpty()
                    ? null
                    : new IngredientChoice(values, false);
        }
        if (choice instanceof RecipeChoice.MaterialChoice materials) {
            List<Material> values = materials.getChoices().stream()
                    .filter(Material::isItem)
                    .filter(material -> material != Material.AIR)
                    .distinct()
                    .toList();
            return values.isEmpty()
                    ? null
                    : new IngredientChoice(values, false);
        }
        if (choice instanceof RecipeChoice.ExactChoice exact) {
            boolean untrusted = exact.getChoices().stream()
                    .anyMatch(ItemStack::hasItemMeta);
            List<Material> values = exact.getChoices().stream()
                    .map(ItemStack::getType)
                    .filter(Material::isItem)
                    .filter(material -> material != Material.AIR)
                    .distinct()
                    .toList();
            return values.isEmpty()
                    ? null
                    : new IngredientChoice(values, untrusted);
        }
        return null;
    }

    private void verifyCommodityGroup(
            List<String> failures,
            String label,
            Material small,
            Material medium,
            Material large
    ) {
        String smallKey = sellService.marketKey(small);
        String mediumKey = sellService.marketKey(medium);
        String largeKey = sellService.marketKey(large);
        if (!smallKey.equals(mediumKey) || !smallKey.equals(largeKey)) {
            failures.add(label + " commodity forms do not share one market_key");
            return;
        }

        long smallUnits = sellService.marketUnits(small);
        long mediumUnits = sellService.marketUnits(medium);
        long largeUnits = sellService.marketUnits(large);
        if (timesNine(smallUnits) != mediumUnits
                || timesNine(mediumUnits) != largeUnits) {
            failures.add(label + " commodity market_units are not 1:9:81 normalized");
            return;
        }

        long smallWorth = sellService.serverUnitSellCents(
                (org.bukkit.entity.Player) null,
                small
        );
        long mediumWorth = sellService.serverUnitSellCents(
                (org.bukkit.entity.Player) null,
                medium
        );
        long largeWorth = sellService.serverUnitSellCents(
                (org.bukkit.entity.Player) null,
                large
        );

        if (timesNine(smallWorth) != mediumWorth
                || timesNine(mediumWorth) != largeWorth) {
            failures.add(
                    label
                            + " current server payouts are not exact 1:9:81 conversion values"
            );
        }

        if (Math.abs(sellService.demandMultiplier(small) - 1.0D) > 0.0001D
                || Math.abs(sellService.demandMultiplier(medium) - 1.0D) > 0.0001D
                || Math.abs(sellService.demandMultiplier(large) - 1.0D) > 0.0001D) {
            failures.add(
                    label
                            + " reversible commodity family has moving per-material pricing"
            );
        }
    }

    private long timesNine(long value) {
        try {
            return Math.multiplyExact(value, 9L);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long safeAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long safeMultiply(long first, long second) {
        try {
            return Math.multiplyExact(first, second);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static final class IteratorState {
        private int failures;
    }

    private record RecipeSeed(
            Material output,
            int outputAmount,
            List<IngredientChoice> ingredients,
            boolean craftingRemainders
    ) {
    }

    private record IngredientChoice(
            List<Material> materials,
            boolean untrusted
    ) {
        private IngredientChoice {
            materials = List.copyOf(materials);
        }
    }

    private record AuditResult(
            List<String> failures,
            int visible,
            int serverSellable,
            int oneCent
    ) {
    }
}
