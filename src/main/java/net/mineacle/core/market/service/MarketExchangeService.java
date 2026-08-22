package net.mineacle.core.market.service;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.market.model.MarketSellPlan;
import net.mineacle.core.orders.model.OrderRecord;
import net.mineacle.core.orders.service.OrderService;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
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
    }

    public Core core() {
        return core;
    }

    public SellService sellService() {
        return sellService;
    }

    public void bindOrders(OrderService service) {
        orderService = service;
    }

    public void unbindOrders(OrderService service) {
        if (orderService == service) {
            orderService = null;
        }
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
                        (java.util.UUID) null,
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
     * items, Orders, or balances. The caller must revalidate at commit time.
     *
     * <p>Automatic matching is exact-limit only, excludes the seller's own
     * Orders, and uses the repository's price-time priority. A player Order at
     * exactly the server floor wins because the seller receives the same money
     * without Mineacle issuing new currency.</p>
     */
    @SuppressWarnings("unused")
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

    /**
     * Orders are intentionally restricted to fungible, stackable commodities.
     * Metadata-sensitive families remain Auction House only until the market
     * has exact metadata-aware keys.
     */
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

    /**
     * A fungible Order can only consume a plain stack. Renames, custom model
     * data, PDC, enchantments, block-state data and other metadata remain with
     * the player instead of being flattened into a Material-only Order.
     */
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
