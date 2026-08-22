package net.mineacle.core.market.service;

import java.util.UUID;

/** Internal non-player accounts used only as zero-balance durable bridges. */
public final class MarketAccounts {

    /**
     * This account is funded only immediately before a durable settlement and
     * normally returns to zero in the same transaction snapshot.
     */
    public static final UUID SETTLEMENT_RESERVE =
            UUID.fromString(
                    "0e655d25-b0a6-50a0-a319-14251d732485"
            );

    private MarketAccounts() {
    }

    @SuppressWarnings("unused")
    public static boolean isInternal(UUID playerId) {
        return SETTLEMENT_RESERVE.equals(playerId);
    }
}
