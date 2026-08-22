package net.mineacle.core.market.service;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.market.model.MarketSellExecutionResult;
import net.mineacle.core.market.model.MarketSellPlan;
import net.mineacle.core.market.model.MarketTransaction;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Shared market authority connecting Sell, Orders and Auction House. */
public final class MarketExchangeService {

    private static final Set<Material> METADATA_MARKET_FAMILIES = Set.of(
            Material.POTION,
            Material.SPLASH_POTION,
            Material.LINGERING_POTION,
            Material.TIPPED_ARROW,
            Material.ENCHANTED_BOOK,
            Material.FILLED_MAP,
            Material.FIREWORK_ROCKET,
            Material.FIREWORK_STAR,
            Material.GOAT_HORN,
            Material.SUSPICIOUS_STEW,
            Material.WRITTEN_BOOK,
            Material.WRITABLE_BOOK,
            Material.PLAYER_HEAD
    );

    private final Core core;
    private final SellService sellService;
    private final MarketSettlementService settlementService;

    private volatile OrderService orderService;
    private volatile AuctionHouseService auctionHouseService;

    public MarketExchangeService(
            Core core,
            SellService sellService
    ) {
        this.core = Objects.requireNonNull(core, "core");
        this.sellService = Objects.requireNonNull(
                sellService,
                "sellService"
        );
        this.settlementService =
                new MarketSettlementService(core);
    }

    public Core core() {
        return core;
    }

    public SellService sellService() {
        return sellService;
    }

    public void bindOrders(OrderService service) {
        orderService = service;
        settlementService.bindOrders(service);
    }

    public void unbindOrders(OrderService service) {
        settlementService.unbindOrders(service);

        if (orderService == service) {
            orderService = null;
        }
    }

    @SuppressWarnings("unused")
    public List<String> recoverySummaries() {
        return settlementService.recoverySummaries();
    }

    public MarketSettlementService settlementService() {
        return settlementService;
    }

    public void shutdown() {
        settlementService.shutdown();
    }

    public void bindAuctionHouse(
            AuctionHouseService service
    ) {
        auctionHouseService = service;
    }

    public void unbindAuctionHouse(
            AuctionHouseService service
    ) {
        if (auctionHouseService == service) {
            auctionHouseService = null;
        }
    }

    /**
     * Direct /sell activation for plain fungible player-inventory sources.
     * The legacy Sell engine remains the fallback whenever Market cannot safely
     * own the whole selected source set.
     */
    public MarketSellExecutionResult executePlayerSell(
            Player seller,
            List<MarketTransaction.SourceItem> rawSources
    ) {
        if (seller == null
                || rawSources == null
                || rawSources.isEmpty()
                || settlementService.executionBlocked()) {
            return MarketSellExecutionResult.passthrough();
        }

        List<MarketTransaction.SourceItem> sources =
                new ArrayList<>();
        Map<Material, Integer> amounts =
                new EnumMap<>(Material.class);
        long totalAmount = 0L;

        try {
            for (MarketTransaction.SourceItem rawSource
                    : rawSources) {
                if (rawSource == null) {
                    return MarketSellExecutionResult.passthrough();
                }

                ItemStack clean =
                        sellService.stripWorthLore(
                                rawSource.item()
                        );

                if (clean == null
                        || clean.getType() == Material.AIR
                        || isNonCanonicalOrderStack(clean)) {
                    return MarketSellExecutionResult.passthrough();
                }

                var quote = sellService.quote(
                        seller.getUniqueId(),
                        clean
                );

                if (!quote.sellable()
                        || quote.totalCents() <= 0L) {
                    return MarketSellExecutionResult.passthrough();
                }

                sources.add(
                        new MarketTransaction.SourceItem(
                                rawSource.slot(),
                                clean
                        )
                );
                amounts.merge(
                        clean.getType(),
                        clean.getAmount(),
                        Math::addExact
                );
                totalAmount = Math.addExact(
                        totalAmount,
                        clean.getAmount()
                );
            }
        } catch (ArithmeticException exception) {
            return MarketSellExecutionResult.rejected(
                    "&cThis sale is too large to process"
            );
        }

        List<MarketTransaction.SellLeg> sellLegs =
                new ArrayList<>(amounts.size());
        long orderPayout = 0L;
        long serverPayout = 0L;
        long orderAmount = 0L;
        long serverAmount = 0L;

        try {
            for (Map.Entry<Material, Integer> entry
                    : amounts.entrySet()) {
                MarketSellPlan plan =
                        planInstantSell(
                                seller.getUniqueId(),
                                entry.getKey(),
                                entry.getValue()
                        );

                if (plan.totalPayoutCents() <= 0L
                        || plan.requestedAmount()
                        != entry.getValue()
                        || plan.serverFloorUnitCents() <= 0L) {
                    return MarketSellExecutionResult.passthrough();
                }

                List<MarketTransaction.OrderLeg> orderLegs =
                        plan.orderFills().stream()
                                .map(fill ->
                                        new MarketTransaction.OrderLeg(
                                                fill.orderId(),
                                                fill.buyerId(),
                                                fill.amount(),
                                                fill.unitPriceCents(),
                                                fill.payoutCents(),
                                                fill.orderCreatedAtMillis()
                                        )
                                )
                                .toList();

                sellLegs.add(
                        new MarketTransaction.SellLeg(
                                plan.material(),
                                plan.requestedAmount(),
                                plan.serverFloorUnitCents(),
                                orderLegs,
                                plan.orderAmount(),
                                plan.orderPayoutCents(),
                                plan.serverAmount(),
                                plan.serverPayoutCents()
                        )
                );

                orderPayout = Math.addExact(
                        orderPayout,
                        plan.orderPayoutCents()
                );
                serverPayout = Math.addExact(
                        serverPayout,
                        plan.serverPayoutCents()
                );
                orderAmount = Math.addExact(
                        orderAmount,
                        plan.orderAmount()
                );
                serverAmount = Math.addExact(
                        serverAmount,
                        plan.serverAmount()
                );
            }
        } catch (ArithmeticException exception) {
            return MarketSellExecutionResult.rejected(
                    "&cThis sale is too large to process"
            );
        }

        long totalPayout;

        try {
            totalPayout = Math.addExact(
                    orderPayout,
                    serverPayout
            );
        } catch (ArithmeticException exception) {
            return MarketSellExecutionResult.rejected(
                    "&cThis sale is too large to process"
            );
        }

        if (totalPayout <= 0L) {
            return MarketSellExecutionResult.passthrough();
        }

        MarketTransaction transaction =
                new MarketTransaction(
                        UUID.randomUUID(),
                        MarketTransaction.State.PREPARED,
                        seller.getUniqueId(),
                        List.copyOf(sources),
                        List.copyOf(sellLegs),
                        orderPayout,
                        serverPayout,
                        totalPayout,
                        System.currentTimeMillis(),
                        ""
                );

        MarketSettlementService.ExecutionStatus status =
                settlementService.executePlayerSource(
                        seller,
                        transaction
                );

        if (status
                == MarketSettlementService.ExecutionStatus.SAFE_FAILURE) {
            return MarketSellExecutionResult.passthrough();
        }

        return new MarketSellExecutionResult(
                true,
                true,
                status
                        == MarketSettlementService.ExecutionStatus.COMPLETED,
                totalPayout,
                totalAmount,
                orderPayout,
                serverPayout,
                orderAmount,
                serverAmount,
                ""
        );
    }

    /**
     * Current guaranteed server liquidation value for one canonical material.
     * A value of zero means the server does not currently guarantee a buyout.
     */
    public long serverGuaranteedUnitCents(
            Material material
    ) {
        if (material == null
                || material == Material.AIR
                || !material.isItem()
                || !sellService.isServerSellableMaterial(material)) {
            return 0L;
        }

        return Math.max(
                0L,
                sellService.serverUnitSellCents(
                        (UUID) null,
                        material
                )
        );
    }

    /**
     * Hard minimum resting bid. Live server Sell is always the economic floor
     * even when the configured administrative minimum is lower.
     */
    public long minimumOrderUnitCents(
            Material material,
            long configuredMinimumUnitCents
    ) {
        return Math.max(
                Math.max(1L, configuredMinimumUnitCents),
                serverGuaranteedUnitCents(material)
        );
    }

    /**
     * Builds an immutable execution plan for an instant Sell without mutating
     * items, Orders, or balances.
     */
    public MarketSellPlan planInstantSell(
            UUID sellerId,
            Material material,
            int requestedAmount
    ) {
        int amount = Math.max(0, requestedAmount);
        long floor = serverGuaranteedUnitCents(material);

        if (amount <= 0
                || floor <= 0L
                || !isFungibleOrderMaterial(material)) {
            return MarketSellPlan.empty(
                    material,
                    amount,
                    floor
            );
        }

        OrderService orders = orderService;
        List<MarketSellPlan.OrderFill> fills =
                new ArrayList<>();
        int remaining = amount;
        long orderPayout = 0L;

        if (orders != null) {
            for (OrderRecord order :
                    orders.automaticCandidates(
                            material,
                            sellerId
                    )) {
                if (remaining <= 0) {
                    break;
                }

                int fillAmount = Math.min(
                        remaining,
                        order.remainingAmount()
                );
                long expectedPayout = safeMultiply(
                        order.pricePerItemCents(),
                        fillAmount
                );
                long fillPayout =
                        order.payoutFor(fillAmount);

                if (fillAmount <= 0
                        || expectedPayout == Long.MAX_VALUE
                        || fillPayout != expectedPayout) {
                    continue;
                }

                long nextOrderPayout = safeAdd(
                        orderPayout,
                        fillPayout
                );

                if (nextOrderPayout == Long.MAX_VALUE) {
                    return MarketSellPlan.empty(
                            material,
                            amount,
                            floor
                    );
                }

                fills.add(
                        new MarketSellPlan.OrderFill(
                                order.id(),
                                order.ownerId(),
                                fillAmount,
                                order.pricePerItemCents(),
                                fillPayout,
                                order.createdAtMillis()
                        )
                );
                orderPayout = nextOrderPayout;
                remaining -= fillAmount;
            }
        }

        long serverPayout = safeMultiply(
                floor,
                remaining
        );

        if (serverPayout == Long.MAX_VALUE) {
            return MarketSellPlan.empty(
                    material,
                    amount,
                    floor
            );
        }

        long totalPayout = safeAdd(
                orderPayout,
                serverPayout
        );

        if (totalPayout == Long.MAX_VALUE) {
            return MarketSellPlan.empty(
                    material,
                    amount,
                    floor
            );
        }

        return new MarketSellPlan(
                material,
                amount,
                floor,
                List.copyOf(fills),
                amount - remaining,
                orderPayout,
                remaining,
                serverPayout,
                totalPayout
        );
    }

    private long safeMultiply(
            long left,
            int right
    ) {
        try {
            return Math.multiplyExact(
                    Math.max(0L, left),
                    Math.max(0, right)
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private long safeAdd(
            long left,
            long right
    ) {
        try {
            return Math.addExact(
                    Math.max(0L, left),
                    Math.max(0L, right)
            );
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    public boolean isFungibleOrderMaterial(
            Material material
    ) {
        if (material == null
                || material == Material.AIR
                || !material.isItem()
                || material.getMaxStackSize() <= 1
                || METADATA_MARKET_FAMILIES.contains(material)) {
            return false;
        }

        String name = material.name();

        if (name.endsWith("_BANNER")
                || name.endsWith("_WALL_BANNER")
                || name.endsWith("_HEAD")
                || name.endsWith("_SKULL")
                || name.endsWith("_SHULKER_BOX")) {
            return false;
        }

        return serverGuaranteedUnitCents(material) > 0L;
    }

    public boolean isNonCanonicalOrderStack(
            ItemStack item
    ) {
        if (item == null || item.getAmount() <= 0) {
            return true;
        }

        if (isFungibleOrderMaterial(item.getType())) {
            return item.hasItemMeta();
        }

        return true;
    }
}
