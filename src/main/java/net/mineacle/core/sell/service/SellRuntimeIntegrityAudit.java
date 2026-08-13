package net.mineacle.core.sell.service;

import net.mineacle.core.Core;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.ShulkerBox;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

public final class SellRuntimeIntegrityAudit {

    private static final int REQUIRED_CATALOG_REVISION = 8;
    private static final int MAX_WAIT_ATTEMPTS = 120;

    private final Core core;
    private final SellService sellService;

    private BukkitTask task;
    private int attempts;

    public SellRuntimeIntegrityAudit(
            Core core,
            SellService sellService
    ) {
        this.core = core;
        this.sellService = sellService;
    }

    public void start() {
        if (task != null) {
            return;
        }

        task = core.getServer()
                .getScheduler()
                .runTaskTimer(
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

        if (sellService.catalogRevision()
                < REQUIRED_CATALOG_REVISION) {
            attempts++;

            if (attempts >= MAX_WAIT_ATTEMPTS) {
                core.getLogger().severe(
                        "Sell launch integrity audit FAIL — "
                                + "catalog revision "
                                + REQUIRED_CATALOG_REVISION
                                + " did not activate within the audit window"
                );
                shutdown();
            }
            return;
        }

        AuditResult result =
                runAudit();

        if (result.failures().isEmpty()) {
            core.getLogger().info(
                    "Sell launch integrity audit PASS — catalog v"
                            + sellService.catalogRevision()
                            + ", "
                            + result.serverSellable()
                            + "/"
                            + result.visible()
                            + " visible items server-sellable, "
                            + "item safety gates verified, "
                            + "legacy Sell architecture removal verified, "
                            + "sell-enabled payout invariant verified, "
                            + "v8 safety-floor payout invariant verified, "
                            + "iron/gold commodity normalization verified"
            );
        } else {
            core.getLogger().severe(
                    "Sell launch integrity audit FAIL — "
                            + String.join(
                            "; ",
                            result.failures()
                    )
            );
        }

        shutdown();
    }

    private AuditResult runAudit() {
        List<String> failures =
                new ArrayList<>();

        SellService.CatalogCoverage coverage =
                sellService.catalogCoverage();

        if (sellService.catalogRevision()
                != REQUIRED_CATALOG_REVISION) {
            failures.add(
                    "unexpected catalog revision "
                            + sellService.catalogRevision()
            );
        }

        if (coverage.visibleItems() <= 0
                || coverage.serverSellableItems() <= 0) {
            failures.add(
                    "catalog coverage is empty"
            );
        }

        verifyNormalAndCustomItemGate(
                failures
        );
        verifyFilledShulkerGate(
                failures
        );
        verifyVariantMetadataGate(
                failures
        );
        verifyBlockedItemGate(
                failures
        );
        verifyLegacyArchitectureRemoved(
                failures
        );
        verifySellEnabledPayoutInvariant(
                failures
        );
        verifySafetyFloorInvariant(
                failures
        );
        verifyAcceptedVariantFloor(
                failures
        );
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
                coverage.serverSellableItems()
        );
    }

    private void verifyNormalAndCustomItemGate(
            List<String> failures
    ) {
        ItemStack normal =
                new ItemStack(
                        Material.SUGAR_CANE
                );

        if (!sellService.canSell(
                normal
        )) {
            failures.add(
                    "normal curated item gate rejected SUGAR_CANE"
            );
            return;
        }

        ItemStack custom =
                normal.clone();
        ItemMeta meta =
                custom.getItemMeta();

        if (meta == null) {
            failures.add(
                    "could not construct custom-item audit metadata"
            );
            return;
        }

        meta.getPersistentDataContainer()
                .set(
                        new NamespacedKey(
                                core,
                                "sell_integrity_audit"
                        ),
                        PersistentDataType.BYTE,
                        (byte) 1
                );
        custom.setItemMeta(
                meta
        );

        if (sellService.canSell(
                custom
        )) {
            failures.add(
                    "custom/PDC item gate accepted a tagged SUGAR_CANE"
            );
        }
    }

    private void verifyFilledShulkerGate(
            List<String> failures
    ) {
        ItemStack item =
                new ItemStack(
                        Material.SHULKER_BOX
                );
        ItemMeta rawMeta =
                item.getItemMeta();

        if (!(rawMeta
                instanceof BlockStateMeta meta)
                || !(meta.getBlockState()
                instanceof ShulkerBox shulker)) {
            failures.add(
                    "could not construct filled-shulker audit item"
            );
            return;
        }

        shulker.getInventory()
                .setItem(
                        0,
                        new ItemStack(
                                Material.DIAMOND
                        )
                );
        meta.setBlockState(
                shulker
        );
        item.setItemMeta(
                meta
        );

        if (sellService.canSell(
                item
        )) {
            failures.add(
                    "filled-container gate accepted a populated shulker"
            );
        }
    }

    private void verifyVariantMetadataGate(
            List<String> failures
    ) {
        ItemStack item =
                new ItemStack(
                        Material.POTION
                );
        ItemMeta rawMeta =
                item.getItemMeta();

        if (!(rawMeta
                instanceof PotionMeta meta)) {
            failures.add(
                    "could not construct potion variant audit item"
            );
            return;
        }

        meta.setColor(
                Color.FUCHSIA
        );
        item.setItemMeta(
                meta
        );

        if (sellService.canSell(
                item
        )) {
            failures.add(
                    "variant metadata gate accepted a custom-color potion"
            );
        }
    }

    private void verifyBlockedItemGate(
            List<String> failures
    ) {
        if (sellService.canSell(
                new ItemStack(
                        Material.BEDROCK
                )
        )) {
            failures.add(
                    "blocked-item gate accepted BEDROCK"
            );
        }
    }

    private void verifyLegacyArchitectureRemoved(
            List<String> failures
    ) {
        if (core.getCommand(
                "sellmulti"
        ) != null) {
            failures.add(
                    "legacy /sellmulti command is still registered"
            );
        }

        String[] retiredClasses = {
                "net.mineacle.core.sell.gui.SellMultiGui",
                "net.mineacle.core.sell.listener.SellMultiGuiListener",
                "net.mineacle.core.sell.listener.SellWorthRefreshListener",
                "net.mineacle.core.sell.storage.SellMarketRepository",
                "net.mineacle.core.sell.storage.SqlSellMarketRepository",
                "net.mineacle.core.sell.storage.YamlSellMarketRepository"
        };

        ClassLoader loader =
                SellRuntimeIntegrityAudit.class
                        .getClassLoader();

        for (String className
                : retiredClasses) {
            try {
                Class.forName(
                        className,
                        false,
                        loader
                );
                failures.add(
                        "retired Sell architecture is still packaged: "
                                + className
                );
            } catch (ClassNotFoundException ignored) {
                // Correct: retired class is absent from the final JAR.
            } catch (LinkageError error) {
                failures.add(
                        "retired Sell class has a broken residual linkage: "
                                + className
                );
            }
        }
    }

    private void verifySellEnabledPayoutInvariant(
            List<String> failures
    ) {
        int checked = 0;

        for (Material material
                : sellService.worthCatalogMaterials()) {
            if (!sellService.isServerSellableMaterial(
                    material
            )) {
                continue;
            }

            checked++;
            long unit =
                    sellService.serverUnitSellCents(
                            (org.bukkit.entity.Player) null,
                            material
                    );

            if (unit <= 0L) {
                failures.add(
                        material
                                + " is server-sell-enabled but has a zero-cent unit payout"
                );
            }
        }

        if (checked <= 0) {
            failures.add(
                    "no server-sell-enabled materials were available for payout audit"
            );
        }
    }

    private void verifySafetyFloorInvariant(
            List<String> failures
    ) {
        var floors =
                sellService
                        .safetyFloorMaterialsSnapshot();

        if (floors.isEmpty()) {
            failures.add(
                    "catalog v8 has no automatic safety-floor materials"
            );
            return;
        }

        int runtimeChecked = 0;

        for (Material material : floors) {
            int amount =
                    Math.clamp(
                            material.getMaxStackSize(),
                            1,
                            64
                    );

            if (sellService.safetyFloorPayoutCents(
                    material,
                    1
            ) != 1L
                    || sellService.safetyFloorPayoutCents(
                    material,
                    amount
            ) != amount) {
                failures.add(
                        material
                                + " safety-floor cash invariant is not 1 cent/item"
                );
                continue;
            }

            if (Math.abs(
                    sellService.demandMultiplier(
                            material
                    )
                            - 1.0D
            ) > 0.0001D) {
                failures.add(
                        material
                                + " safety-floor item has a moving market multiplier"
                );
            }

            if (isVariantMaterial(
                    material
            )) {
                continue;
            }

            ItemStack sample =
                    new ItemStack(
                            material,
                            amount
                    );
            var valuation =
                    sellService.appraise(
                            (org.bukkit.entity.Player) null,
                            sample
                    );

            runtimeChecked++;

            if (!valuation.sellable()
                    || valuation.serverSellCents()
                    != amount) {
                failures.add(
                        material
                                + " is catalog floor-approved but runtime payout is "
                                + valuation.serverSellCents()
                                + " cents for "
                                + amount
                                + " item(s)"
                );
            }
        }

        if (runtimeChecked <= 0) {
            failures.add(
                    "no non-variant safety-floor runtime sample was available"
            );
        }
    }

    private void verifyAcceptedVariantFloor(
            List<String> failures
    ) {
        ItemStack item =
                new ItemStack(
                        Material.POTION
                );
        ItemMeta rawMeta =
                item.getItemMeta();

        if (!(rawMeta
                instanceof PotionMeta meta)) {
            failures.add(
                    "could not construct accepted potion audit metadata"
            );
            return;
        }

        meta.setBasePotionType(
                PotionType.WATER
        );
        item.setItemMeta(
                meta
        );

        var valuation =
                sellService.appraise(
                        (org.bukkit.entity.Player) null,
                        item
                );

        if (!valuation.sellable()
                || valuation.serverSellCents()
                != 1L) {
            failures.add(
                    "accepted POTION variant does not pay the v8 1-cent floor"
            );
        }
    }

    private boolean isVariantMaterial(
            Material material
    ) {
        return switch (material) {
            case POTION,
                 SPLASH_POTION,
                 LINGERING_POTION,
                 TIPPED_ARROW,
                 SUSPICIOUS_STEW,
                 FIREWORK_ROCKET,
                 FIREWORK_STAR,
                 WRITTEN_BOOK,
                 FILLED_MAP,
                 GOAT_HORN -> true;
            default -> false;
        };
    }

    private void verifyCommodityGroup(
            List<String> failures,
            String label,
            Material small,
            Material medium,
            Material large
    ) {
        String smallKey =
                sellService.marketKey(
                        small
                );
        String mediumKey =
                sellService.marketKey(
                        medium
                );
        String largeKey =
                sellService.marketKey(
                        large
                );

        if (!smallKey.equals(
                mediumKey
        )
                || !smallKey.equals(
                largeKey
        )) {
            failures.add(
                    label
                            + " commodity forms do not share one market_key"
            );
            return;
        }

        long smallUnits =
                sellService.marketUnits(
                        small
                );
        long mediumUnits =
                sellService.marketUnits(
                        medium
                );
        long largeUnits =
                sellService.marketUnits(
                        large
                );

        if (timesNine(
                smallUnits
        ) != mediumUnits
                || timesNine(
                mediumUnits
        ) != largeUnits) {
            failures.add(
                    label
                            + " commodity market_units are not 1:9:81 normalized"
            );
        }
    }

    private long timesNine(
            long value
    ) {
        try {
            return Math.multiplyExact(
                    value,
                    9L
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private record AuditResult(
            List<String> failures,
            int visible,
            int serverSellable
    ) {
    }
}
