package net.mineacle.core.market.model;

import org.bukkit.Material;

import java.util.List;
import java.util.UUID;

/**
 * Immutable preview of one instant Sell route. It is never transaction
 * authority by itself; commit code must re-read the live server floor and every
 * referenced Order before moving items or money.
 */
public record MarketSellPlan(
        Material material,
        int requestedAmount,
        long serverFloorUnitCents,
        List<OrderFill> orderFills,
        int orderAmount,
        long orderPayoutCents,
        int serverAmount,
        long serverPayoutCents,
        long totalPayoutCents
) {

    public MarketSellPlan {
        orderFills = orderFills == null
                ? List.of()
                : List.copyOf(orderFills);
    }

    public static MarketSellPlan empty(
            Material material,
            int requestedAmount,
            long serverFloorUnitCents
    ) {
        return new MarketSellPlan(
                material,
                Math.max(0, requestedAmount),
                Math.max(0L, serverFloorUnitCents),
                List.of(),
                0,
                0L,
                0,
                0L,
                0L
        );
    }


    public record OrderFill(
            UUID orderId,
            UUID buyerId,
            int amount,
            long unitPriceCents,
            long payoutCents,
            long orderCreatedAtMillis
    ) {
    }
}
