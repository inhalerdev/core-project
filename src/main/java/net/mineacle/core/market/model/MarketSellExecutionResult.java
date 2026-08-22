package net.mineacle.core.market.model;

/**
 * Result of the Market layer attempting to own a player /sell command.
 *
 * <p>handled=false means the existing Sell engine should process the command
 * unchanged. handled=true means Market owns the source items and the legacy
 * Sell command must not run.</p>
 */
public record MarketSellExecutionResult(
        boolean handled,
        boolean accepted,
        boolean completed,
        long totalCents,
        long totalAmount,
        long orderCents,
        long serverCents,
        long orderAmount,
        long serverAmount,
        String message
) {

    public MarketSellExecutionResult {
        totalCents = Math.max(0L, totalCents);
        totalAmount = Math.max(0L, totalAmount);
        orderCents = Math.max(0L, orderCents);
        serverCents = Math.max(0L, serverCents);
        orderAmount = Math.max(0L, orderAmount);
        serverAmount = Math.max(0L, serverAmount);
        message = message == null ? "" : message;
    }

    public static MarketSellExecutionResult passthrough() {
        return new MarketSellExecutionResult(
                false,
                false,
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                ""
        );
    }

    public static MarketSellExecutionResult rejected(
            String message
    ) {
        return new MarketSellExecutionResult(
                true,
                false,
                false,
                0L,
                0L,
                0L,
                0L,
                0L,
                0L,
                message
        );
    }
}
