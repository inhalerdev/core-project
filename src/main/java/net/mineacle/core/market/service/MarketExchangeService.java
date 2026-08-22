package net.mineacle.core.market.service;

import net.mineacle.core.Core;
import net.mineacle.core.auctionhouse.service.AuctionHouseService;
import net.mineacle.core.orders.service.OrderService;
import net.mineacle.core.sell.service.SellService;
import org.bukkit.Material;

import java.util.Objects;

/**
 * Shared market authority connecting Sell, Orders and Auction House.
 *
 * <p>R1 intentionally centralizes only invariants and service bindings. Trade
 * matching is added in later stages after Orders receives the same durable
 * transaction semantics already used by Auction House.</p>
 */
public final class MarketExchangeService {

    private final Core core;
    private final SellService sellService;

    private volatile OrderService orderService;
    private volatile AuctionHouseService auctionHouseService;

    public MarketExchangeService(
            Core core,
            SellService sellService
    ) {
        this.core = Objects.requireNonNull(
                core,
                "core"
        );
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

    public void bindOrders(
            OrderService service
    ) {
        orderService = service;
    }

    public void unbindOrders(
            OrderService service
    ) {
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
                || !sellService.isServerSellableMaterial(
                material
        )) {
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
     * Returns the hard minimum resting bid for an order. The configurable
     * minimum remains a secondary administrative floor, while live /sell is
     * always the economic floor when it is higher.
     */
    public long minimumOrderUnitCents(
            Material material,
            long configuredMinimumUnitCents
    ) {
        return Math.max(
                Math.max(
                        1L,
                        configuredMinimumUnitCents
                ),
                serverGuaranteedUnitCents(
                        material
                )
        );
    }


}